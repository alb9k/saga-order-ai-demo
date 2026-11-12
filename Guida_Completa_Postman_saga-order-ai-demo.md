# Suite Postman — saga-order-ai-demo

**Guida operativa completa | 12 novembre 2026 | Java 21 · Spring Boot · Spring AI · Ollama · PostgreSQL · Kafka**

Documento elaborato a partire dal progetto ZIP allegato, dal docker-compose.yml, dai README e dalle due collection Postman effettivamente presenti. Gli esiti sono aspettative definite dagli script, non risultati di una esecuzione live.

## 1. Scopo e architettura

Il progetto implementa Saga Orchestration con Order Service (8081), Payment (8082), Inventory (8083), Shipping (8084), PostgreSQL 16 (5432), Kafka 3.8.1 (9092), Assistant Service (8090) e Ollama locale (11434, modello `qwen2.5:3b`). L'assistente usa Spring AI e un tool di lettura dello stato ordine; non modifica la Saga.

Le operazioni Saga sono chiamate HTTP sincrone. L'Order Service registra gli eventi nell'outbox PostgreSQL e un publisher li invia al topic `saga-order-events` con semantica at-least-once. L'assistente è avviato sull'host: non è nel docker-compose.yml.

## 2. Prerequisiti e avvio passo per passo

Prerequisiti: macOS/Linux, JDK 21, Maven 3.9+, Docker Desktop, Docker Compose, Postman, Ollama e porte libere 8081–8084, 8090, 5432, 9092, 11434.

Terminale A (solo se Ollama non è già attivo):
```bash
ollama serve
```

Terminale B, dalla radice del progetto:
```bash
java -version
mvn -version
docker compose version
ollama pull qwen2.5:3b
ollama list
curl http://localhost:11434/api/tags
mvn clean package
docker compose up --build -d
docker compose ps
curl --fail-with-body http://localhost:8081/actuator/health
```

Terminale C, dalla radice:
```bash
mvn -pl assistant-service spring-boot:run
```

Terminale D (smoke test):
```bash
curl -i -X POST http://localhost:8081/orders -H 'Content-Type: application/json' -d '{"failAt":"none"}'
curl -i -X POST http://localhost:8081/orders -H 'Content-Type: application/json' -d '{"failAt":"shipping"}'
ORDER_ID='SOSTITUISCI-CON-UUID'
curl -i "http://localhost:8081/orders/$ORDER_ID"
curl -i "http://localhost:8090/assistant/orders/$ORDER_ID"
```

Se Ollama è già avviato non avviare una seconda istanza. Aspetta l'health check UP prima di eseguire la suite.

## 3. Importazione e configurazione Postman

Importare da `postman/` le collection `Saga-Order-AI-TEST-POSITIVI.postman_collection.json` e `Saga-Order-AI-TEST-NEGATIVI.postman_collection.json`. Le variabili sono già definite a livello di collection. Eseguire prima la collection positiva e poi quella negativa, mantenendo l'ordine delle richieste.

**Variabili predefinite nelle collection:**
- `orderBase` = `http://localhost:8081`
- `paymentBase` = `http://localhost:8082`
- `inventoryBase` = `http://localhost:8083`
- `shippingBase` = `http://localhost:8084`
- `assistantBase` = `http://localhost:8090`
- `orderId_success` = ``
- `orderId_payment` = ``
- `orderId_inventory` = ``
- `orderId_shipping` = ``
- `missingId` = `00000000-0000-4000-8000-000000000001`
- `invalidId` = `not-a-uuid`
- `directPaymentId` = ``
- `directInventoryId` = ``
- `directShippingId` = ``

Usare Collection Runner, 1 iterazione e richieste in ordine. Le richieste POST che creano ordini memorizzano gli UUID per le verifiche successive. Le collection sono indipendenti: non è necessario trasferire manualmente le variabili tra le due.

## 4. Semantica HTTP e regole di test

- Saga riuscita: `POST /orders` → HTTP 201 e `status=COMPLETED`.
- Saga con errore simulato: `POST /orders` → HTTP 201 e `status=CANCELLED` (se le compensazioni riescono). Il 201 indica creazione della risorsa, non successo del processo.
- Errori API: HTTP 400 per input/UUID non validi, 404 per risorse assenti, 422 per errore controllato `?fail=true`, 409 per ri-esecuzione dopo compensazione.
- Gli endpoint interni servono per test tecnici e non devono essere esposti a produzione.
- AI: verificare campi strutturati `orderId`, `status`, `simulatedFailureStep`, `answer` non vuoto; non confrontare il testo naturale parola per parola. Il modello può restituire 502 se non invoca il tool Spring AI `getOrderStatus()`.

## 5. Suite POSITIVA — 18 richieste

La collection contiene **18 richieste**. Per ogni richiesta seguono metodo, URL, eventuale body e script di controllo realmente presenti nel JSON.

### 01. 01 - Saga completata / Crea ordine COMPLETED

**POST** `{{orderBase}}/orders`

**Body:**
```json
{"failAt": "none"}
```

**Script test:**
```javascript
pm.test("HTTP 201", () => pm.response.to.have.status(201));
const d=pm.response.json();
pm.test("ID UUID",()=>pm.expect(d.id).to.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i));
pm.test("Stato COMPLETED",()=>pm.expect(d.status).to.eql("COMPLETED"));
pm.test("failureStep none",()=>pm.expect(d.failureStep).to.eql("none"));
if(d.id && d.status==="COMPLETED")pm.collectionVariables.set("orderId_success",d.id);
```

### 02. 01 - Saga completata / Leggi ordine COMPLETED

**GET** `{{orderBase}}/orders/{{orderId_success}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("ID coerente",()=>pm.expect(d.id).to.eql(pm.collectionVariables.get("orderId_success")));pm.test("Stato COMPLETED",()=>pm.expect(d.status).to.eql("COMPLETED"));pm.test("Failure step",()=>pm.expect(d.failureStep).to.eql("none"));
```

### 03. 01 - Saga completata / Pagamento ACTIVE

**GET** `{{paymentBase}}/internal/payment/{{orderId_success}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa ACTIVE",()=>pm.expect(d.status).to.eql("ACTIVE"));
```

### 04. 01 - Saga completata / Magazzino ACTIVE

**GET** `{{inventoryBase}}/internal/inventory/{{orderId_success}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa ACTIVE",()=>pm.expect(d.status).to.eql("ACTIVE"));
```

### 05. 01 - Saga completata / Spedizione ACTIVE

**GET** `{{shippingBase}}/internal/shipping/{{orderId_success}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa ACTIVE",()=>pm.expect(d.status).to.eql("ACTIVE"));
```

### 06. 01 - Saga completata / Assistente AI: ordine completato

**GET** `{{assistantBase}}/assistant/orders/{{orderId_success}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("ID coerente",()=>pm.expect(d.orderId).to.eql(pm.collectionVariables.get("orderId_success")));pm.test("Stato",()=>pm.expect(d.status).to.eql("COMPLETED"));pm.test("Step simulato",()=>pm.expect(d.simulatedFailureStep).to.be.oneOf([null, "none"]));pm.test("Risposta AI non vuota",()=>pm.expect(d.answer).to.be.a("string").and.not.empty);
```

### 07. 02 - Controllo stato saga PAYMENT / Crea ordine con guasto simulato payment

**POST** `{{orderBase}}/orders`

**Body:**
```json
{"failAt": "payment"}
```

**Script test:**
```javascript
pm.test("HTTP 201", () => pm.response.to.have.status(201));
const d=pm.response.json();
pm.test("ID UUID",()=>pm.expect(d.id).to.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i));
pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
pm.test("failureStep payment",()=>pm.expect(d.failureStep).to.eql("payment"));
if(d.id && d.status==="CANCELLED")pm.collectionVariables.set("orderId_payment",d.id);
```

### 08. 02 - Controllo stato saga PAYMENT / Leggi ordine annullato

**GET** `{{orderBase}}/orders/{{orderId_payment}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("ID coerente",()=>pm.expect(d.id).to.eql(pm.collectionVariables.get("orderId_payment")));pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));pm.test("Failure step",()=>pm.expect(d.failureStep).to.eql("payment"));
```

### 09. 02 - Controllo stato saga PAYMENT / Assistente AI: annullamento

**GET** `{{assistantBase}}/assistant/orders/{{orderId_payment}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("ID coerente",()=>pm.expect(d.orderId).to.eql(pm.collectionVariables.get("orderId_payment")));pm.test("Stato",()=>pm.expect(d.status).to.eql("CANCELLED"));pm.test("Step simulato",()=>pm.expect(d.simulatedFailureStep).to.eql("payment"));pm.test("Risposta AI non vuota",()=>pm.expect(d.answer).to.be.a("string").and.not.empty);
```

### 10. 02 - Controllo stato saga INVENTORY / Crea ordine con guasto simulato inventory

**POST** `{{orderBase}}/orders`

**Body:**
```json
{"failAt": "inventory"}
```

**Script test:**
```javascript
pm.test("HTTP 201", () => pm.response.to.have.status(201));
const d=pm.response.json();
pm.test("ID UUID",()=>pm.expect(d.id).to.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i));
pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
pm.test("failureStep inventory",()=>pm.expect(d.failureStep).to.eql("inventory"));
if(d.id && d.status==="CANCELLED")pm.collectionVariables.set("orderId_inventory",d.id);
```

### 11. 02 - Controllo stato saga INVENTORY / Leggi ordine annullato

**GET** `{{orderBase}}/orders/{{orderId_inventory}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("ID coerente",()=>pm.expect(d.id).to.eql(pm.collectionVariables.get("orderId_inventory")));pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));pm.test("Failure step",()=>pm.expect(d.failureStep).to.eql("inventory"));
```

### 12. 02 - Controllo stato saga INVENTORY / Assistente AI: annullamento

**GET** `{{assistantBase}}/assistant/orders/{{orderId_inventory}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("ID coerente",()=>pm.expect(d.orderId).to.eql(pm.collectionVariables.get("orderId_inventory")));pm.test("Stato",()=>pm.expect(d.status).to.eql("CANCELLED"));pm.test("Step simulato",()=>pm.expect(d.simulatedFailureStep).to.eql("inventory"));pm.test("Risposta AI non vuota",()=>pm.expect(d.answer).to.be.a("string").and.not.empty);
```

### 13. 02 - Controllo stato saga SHIPPING / Crea ordine con guasto simulato shipping

**POST** `{{orderBase}}/orders`

**Body:**
```json
{"failAt": "shipping"}
```

**Script test:**
```javascript
pm.test("HTTP 201", () => pm.response.to.have.status(201));
const d=pm.response.json();
pm.test("ID UUID",()=>pm.expect(d.id).to.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i));
pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
pm.test("failureStep shipping",()=>pm.expect(d.failureStep).to.eql("shipping"));
if(d.id && d.status==="CANCELLED")pm.collectionVariables.set("orderId_shipping",d.id);
```

### 14. 02 - Controllo stato saga SHIPPING / Leggi ordine annullato

**GET** `{{orderBase}}/orders/{{orderId_shipping}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("ID coerente",()=>pm.expect(d.id).to.eql(pm.collectionVariables.get("orderId_shipping")));pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));pm.test("Failure step",()=>pm.expect(d.failureStep).to.eql("shipping"));
```

### 15. 02 - Controllo stato saga SHIPPING / Assistente AI: annullamento

**GET** `{{assistantBase}}/assistant/orders/{{orderId_shipping}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("ID coerente",()=>pm.expect(d.orderId).to.eql(pm.collectionVariables.get("orderId_shipping")));pm.test("Stato",()=>pm.expect(d.status).to.eql("CANCELLED"));pm.test("Step simulato",()=>pm.expect(d.simulatedFailureStep).to.eql("shipping"));pm.test("Risposta AI non vuota",()=>pm.expect(d.answer).to.be.a("string").and.not.empty);
```

### 16. 03 - Endpoint interni: idempotenza su ordine COMPLETED / Pagamento authorize ripetuto

**POST** `{{paymentBase}}/internal/payment/{{orderId_success}}/authorize`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```

### 17. 03 - Endpoint interni: idempotenza su ordine COMPLETED / Magazzino reserve ripetuto

**POST** `{{inventoryBase}}/internal/inventory/{{orderId_success}}/reserve`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```

### 18. 03 - Endpoint interni: idempotenza su ordine COMPLETED / Spedizione create ripetuto

**POST** `{{shippingBase}}/internal/shipping/{{orderId_success}}/create`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```


## 6. Suite NEGATIVA — 28 richieste

La collection contiene **28 richieste**. Per ogni richiesta seguono metodo, URL, eventuale body e script di controllo realmente presenti nel JSON.

### 01. 01 - Input non validi e risorse assenti / failAt non supportato -> 400

**POST** `{{orderBase}}/orders`

**Body:**
```json
{"failAt": "unknown"}
```

**Script test:**
```javascript
pm.test("HTTP 400", () => pm.response.to.have.status(400));
```

### 02. 01 - Input non validi e risorse assenti / Ordine UUID inesistente -> 404

**GET** `{{orderBase}}/orders/{{missingId}}`

**Script test:**
```javascript
pm.test("HTTP 404", () => pm.response.to.have.status(404));
```

### 03. 01 - Input non validi e risorse assenti / Ordine ID non UUID -> 400

**GET** `{{orderBase}}/orders/{{invalidId}}`

**Script test:**
```javascript
pm.test("HTTP 400", () => pm.response.to.have.status(400));
```

### 04. 01 - Input non validi e risorse assenti / Pagamento inesistente -> 404

**GET** `{{paymentBase}}/internal/payment/{{missingId}}`

**Script test:**
```javascript
pm.test("HTTP 404", () => pm.response.to.have.status(404));
```

### 05. 01 - Input non validi e risorse assenti / Magazzino inesistente -> 404

**GET** `{{inventoryBase}}/internal/inventory/{{missingId}}`

**Script test:**
```javascript
pm.test("HTTP 404", () => pm.response.to.have.status(404));
```

### 06. 01 - Input non validi e risorse assenti / Spedizione inesistente -> 404

**GET** `{{shippingBase}}/internal/shipping/{{missingId}}`

**Script test:**
```javascript
pm.test("HTTP 404", () => pm.response.to.have.status(404));
```

### 07. 01 - Input non validi e risorse assenti / Assistente AI ID non UUID -> 400

**GET** `{{assistantBase}}/assistant/orders/{{invalidId}}`

**Script test:**
```javascript
pm.test("HTTP 400", () => pm.response.to.have.status(400));
```

### 08. 02 - PAYMENT errore 422 e conflitto 409 / Fallimento simulato -> 422

**POST** `{{paymentBase}}/internal/payment/{{directPaymentId}}/authorize?fail=true`

**Script prerequest:**
```javascript
pm.collectionVariables.set("directPaymentId", pm.variables.replaceIn("{$guid}"));
```

**Script test:**
```javascript
pm.test("HTTP 422", () => pm.response.to.have.status(422));
```

### 09. 02 - PAYMENT errore 422 e conflitto 409 / Creazione corretta per preparare compensazione -> 200

**POST** `{{paymentBase}}/internal/payment/{{directPaymentId}}/authorize`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```

### 10. 02 - PAYMENT errore 422 e conflitto 409 / Compensazione -> 200

**POST** `{{paymentBase}}/internal/payment/{{directPaymentId}}/void`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```

### 11. 02 - PAYMENT errore 422 e conflitto 409 / Ripetizione dopo compensazione -> 409

**POST** `{{paymentBase}}/internal/payment/{{directPaymentId}}/authorize`

**Script test:**
```javascript
pm.test("HTTP 409", () => pm.response.to.have.status(409));
```

### 12. 02 - PAYMENT errore 422 e conflitto 409 / Verifica stato CANCELLED

**GET** `{{paymentBase}}/internal/payment/{{directPaymentId}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
```

### 13. 02 - INVENTORY errore 422 e conflitto 409 / Fallimento simulato -> 422

**POST** `{{inventoryBase}}/internal/inventory/{{directInventoryId}}/reserve?fail=true`

**Script prerequest:**
```javascript
pm.collectionVariables.set("directInventoryId", pm.variables.replaceIn("{$guid}"));
```

**Script test:**
```javascript
pm.test("HTTP 422", () => pm.response.to.have.status(422));
```

### 14. 02 - INVENTORY errore 422 e conflitto 409 / Creazione corretta per preparare compensazione -> 200

**POST** `{{inventoryBase}}/internal/inventory/{{directInventoryId}}/reserve`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```

### 15. 02 - INVENTORY errore 422 e conflitto 409 / Compensazione -> 200

**POST** `{{inventoryBase}}/internal/inventory/{{directInventoryId}}/release`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```

### 16. 02 - INVENTORY errore 422 e conflitto 409 / Ripetizione dopo compensazione -> 409

**POST** `{{inventoryBase}}/internal/inventory/{{directInventoryId}}/reserve`

**Script test:**
```javascript
pm.test("HTTP 409", () => pm.response.to.have.status(409));
```

### 17. 02 - INVENTORY errore 422 e conflitto 409 / Verifica stato CANCELLED

**GET** `{{inventoryBase}}/internal/inventory/{{directInventoryId}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
```

### 18. 02 - SHIPPING errore 422 e conflitto 409 / Fallimento simulato -> 422

**POST** `{{shippingBase}}/internal/shipping/{{directShippingId}}/create?fail=true`

**Script prerequest:**
```javascript
pm.collectionVariables.set("directShippingId", pm.variables.replaceIn("{$guid}"));
```

**Script test:**
```javascript
pm.test("HTTP 422", () => pm.response.to.have.status(422));
```

### 19. 02 - SHIPPING errore 422 e conflitto 409 / Creazione corretta per preparare compensazione -> 200

**POST** `{{shippingBase}}/internal/shipping/{{directShippingId}}/create`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```

### 20. 02 - SHIPPING errore 422 e conflitto 409 / Compensazione -> 200

**POST** `{{shippingBase}}/internal/shipping/{{directShippingId}}/cancel`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
```

### 21. 02 - SHIPPING errore 422 e conflitto 409 / Ripetizione dopo compensazione -> 409

**POST** `{{shippingBase}}/internal/shipping/{{directShippingId}}/create`

**Script test:**
```javascript
pm.test("HTTP 409", () => pm.response.to.have.status(409));
```

### 22. 02 - SHIPPING errore 422 e conflitto 409 / Verifica stato CANCELLED

**GET** `{{shippingBase}}/internal/shipping/{{directShippingId}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
```

### 23. 03 - Saga fallite: risultato HTTP positivo ma business negativo / Guasto pagamento -> CANCELLED

**POST** `{{orderBase}}/orders`

**Body:**
```json
{"failAt": "payment"}
```

**Script test:**
```javascript
pm.test("HTTP 201", () => pm.response.to.have.status(201));
const d=pm.response.json();
pm.test("ID UUID",()=>pm.expect(d.id).to.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i));
pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
pm.test("failureStep payment",()=>pm.expect(d.failureStep).to.eql("payment"));
if(d.id && d.status==="CANCELLED")pm.collectionVariables.set("orderId_payment",d.id);
```

### 24. 03 - Saga fallite: risultato HTTP positivo ma business negativo / Guasto magazzino -> CANCELLED

**POST** `{{orderBase}}/orders`

**Body:**
```json
{"failAt": "inventory"}
```

**Script test:**
```javascript
pm.test("HTTP 201", () => pm.response.to.have.status(201));
const d=pm.response.json();
pm.test("ID UUID",()=>pm.expect(d.id).to.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i));
pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
pm.test("failureStep inventory",()=>pm.expect(d.failureStep).to.eql("inventory"));
if(d.id && d.status==="CANCELLED")pm.collectionVariables.set("orderId_inventory",d.id);
```

### 25. 03 - Saga fallite: risultato HTTP positivo ma business negativo / Guasto spedizione -> CANCELLED

**POST** `{{orderBase}}/orders`

**Body:**
```json
{"failAt": "shipping"}
```

**Script test:**
```javascript
pm.test("HTTP 201", () => pm.response.to.have.status(201));
const d=pm.response.json();
pm.test("ID UUID",()=>pm.expect(d.id).to.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i));
pm.test("Stato CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
pm.test("failureStep shipping",()=>pm.expect(d.failureStep).to.eql("shipping"));
if(d.id && d.status==="CANCELLED")pm.collectionVariables.set("orderId_shipping",d.id);
```

### 26. 03 - Saga fallite: risultato HTTP positivo ma business negativo / Pagamento compensato dopo guasto magazzino

**GET** `{{paymentBase}}/internal/payment/{{orderId_inventory}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
```

### 27. 03 - Saga fallite: risultato HTTP positivo ma business negativo / Pagamento compensato dopo guasto spedizione

**GET** `{{paymentBase}}/internal/payment/{{orderId_shipping}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
```

### 28. 03 - Saga fallite: risultato HTTP positivo ma business negativo / Magazzino compensato dopo guasto spedizione

**GET** `{{inventoryBase}}/internal/inventory/{{orderId_shipping}}`

**Script test:**
```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const d=pm.response.json();pm.test("Stato risorsa CANCELLED",()=>pm.expect(d.status).to.eql("CANCELLED"));
```


## 7. Verifiche SQL — PostgreSQL e DBeaver

Connessione DBeaver: PostgreSQL su `localhost:5432`, utente `saga`, password `saga` (credenziali solo per ambiente locale demo). I database sono `orderdb`, `paymentdb`, `inventorydb`, `shippingdb`. Per il controllo rapido da terminale:

```bash
docker compose exec -T postgres psql -U saga -d orderdb -c "SELECT id,status,failure_step,details FROM orders ORDER BY id DESC LIMIT 10;"
docker compose exec -T postgres psql -U saga -d orderdb -c "SELECT aggregate_id,event_type,payload,published_at FROM outbox_events ORDER BY created_at DESC LIMIT 10;"
docker compose exec -T postgres psql -U saga -d orderdb -c "SELECT * FROM outbox_events WHERE published_at IS NULL;"
docker compose exec -T postgres psql -U saga -d paymentdb -c "SELECT * FROM payment_authorizations;"
docker compose exec -T postgres psql -U saga -d inventorydb -c "SELECT * FROM stock_reservations;"
docker compose exec -T postgres psql -U saga -d shippingdb -c "SELECT * FROM shipments;"
```

Controlli: COMPLETED deve avere operazioni attive sui tre servizi; fallimento payment non deve completare gli step successivi; fallimento inventory deve compensare il pagamento; fallimento shipping deve compensare inventory e payment. Confrontare l'UUID tra le quattro basi dati. `published_at` può restare NULL per pochi istanti, perché la pubblicazione è asincrona.

## 8. Verifiche Kafka e Transactional Outbox

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic saga-order-events --from-beginning --timeout-ms 5000
docker compose logs --tail=100 order-service
docker compose logs --tail=100 kafka
```

Controllare l'`aggregate_id`, il payload, il tipo di evento e il timestamp di pubblicazione. La semantica at-least-once consente duplicati: non dedurre un errore dalla sola ripetizione di un evento. Attendere qualche secondo dopo la creazione dell'ordine.

## 9. Diagnostica e problemi frequenti

```bash
docker compose ps
docker compose logs --tail=150 order-service
docker compose logs --tail=150 payment-service
docker compose logs --tail=150 inventory-service
docker compose logs --tail=150 shipping-service
docker compose logs --tail=150 postgres
docker compose restart order-service
curl http://localhost:11434/api/tags
lsof -nP -iTCP:8090 -sTCP:LISTEN
```

- Connessione rifiutata su 8090: avviare `assistant-service` sull'host.
- Errore modello: controllare `ollama list`, servizio Ollama e `qwen2.5:3b`.
- HTTP 502 dall'assistente: controllare che Spring AI richiami il tool di stato e che Order Service sia raggiungibile.
- Stato inatteso della Saga: leggere i log dei servizi, distinguere guasto simulato da indisponibilità infrastrutturale.
- Test Postman dipendenti da UUID: eseguire le richieste nel loro ordine; evitare di lanciare solo la GET prima della POST.
- Outbox non pubblicata: verificare publisher, Kafka e `published_at`.

## 10. Arresto e pulizia

```bash
# Arresta i container, conserva i dati PostgreSQL
docker compose down

# Solo se vuoi cancellare anche il volume persistente
docker compose down -v

# In alternativa, arresto senza rimuovere container
docker compose stop

# Per ripartire da container esistenti
docker compose start
```

Arrestare manualmente l'Assistant Service e `ollama serve` con Ctrl+C nei terminali in cui sono stati lanciati. `docker compose down -v` elimina il volume `pgdata`: usare solo se si desidera perdere i dati locali.

## 11. Checklist e report

- [ ] Ollama attivo e modello presente
- [ ] Quattro microservizi Docker attivi
- [ ] Assistant Service avviato su 8090
- [ ] Collection positiva eseguita senza errori
- [ ] Collection negativa eseguita senza errori
- [ ] Verificati COMPLETED, CANCELLED e compensazioni
- [ ] Verificati HTTP 400/404/409/422
- [ ] Verificati record PostgreSQL nei quattro database
- [ ] Verificati outbox e topic Kafka
- [ ] Annotati gli UUID e salvato il report Postman

**Report consigliato:** data/ora, versione Git, ambiente, collection, numero richieste, asserzioni pass/fail, UUID, query SQL, anomalie e log rilevanti. Nessuna collection è stata eseguita live durante la preparazione di questa guida.
