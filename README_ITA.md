# Saga Order AI Demo — Guida italiana

Progetto dimostrativo Java 21 con **Saga Orchestration**, **Transactional Outbox** e un assistente AI in linguaggio naturale basato su **Spring AI + Ollama locale**. Deriva da [saga-order-demo](https://github.com/alb9k/saga-order-demo). L'assistente è opzionale e non modifica il flusso Saga.

## Tecnologie e architettura

- Java 21, Maven 3.9+, Spring Boot 3.3.9 per i servizi Saga; Spring Boot **3.5.7** per `assistant-service`.
- Spring AI **1.1.0**, Ollama, modello **`qwen2.5:3b`** (esecuzione locale, senza chiave OpenAI).
- PostgreSQL 16, Kafka 3.8.1, Docker Compose.
- `order-service` (8081): orchestratore Saga; `payment-service` (8082), `inventory-service` (8083), `shipping-service` (8084): operazioni e compensazioni.
- `assistant-service` (127.0.0.1:8090): REST API per spiegare lo stato degli ordini; Ollama ascolta su `localhost:11434`.

```text
Postman / curl
     | GET /assistant/orders/{uuid}
     v
assistant-service :8090 -- Spring AI / ChatClient --> Ollama :11434 (qwen2.5:3b)
     |  @Tool getOrderStatus() (sola lettura)
     v
order-service :8081 --> payment :8082 --> inventory :8083 --> shipping :8084
     |                       PostgreSQL (database separati)
     +--> Outbox PostgreSQL --> publisher schedulato --> Kafka (saga-order-events)
```

**Nota:** i comandi Saga sono chiamate HTTP sincrone; Kafka serve a pubblicare notifiche degli esiti. Il Transactional Outbox salva gli eventi e li pubblica con semantica *at-least-once*; i consumer devono gestire duplicati.

## Prerequisiti

macOS/Linux, JDK 21, Maven 3.9+, Docker Desktop/Compose, `curl` e **Ollama** installato. Liberare le porte 8081–8084, 8090, 5432, 9092 e 11434. L'assistente è avviato sull'host, **non** è definito nel `docker-compose.yml`.

## Avvio passo per passo

Dalla directory radice del progetto:

```bash
# 1. Avvia Ollama (se non è già attivo)
ollama serve
```

In un **secondo terminale**:

```bash
# 2. Scarica il modello richiesto dal progetto (solo al primo avvio)
ollama pull qwen2.5:3b
ollama list

# 3. Compila e avvia i quattro microservizi e l'infrastruttura
mvn clean package
docker compose up --build -d
docker compose ps
curl --fail-with-body http://localhost:8081/actuator/health

# 4. Avvia l'assistente AI in locale
mvn -pl assistant-service spring-boot:run
```

Se Ollama è già avviato come applicazione/servizio, non eseguire una seconda istanza di `ollama serve`. Attendere che l'health check di `order-service` restituisca `UP` prima dei test. In IntelliJ, avviare `dev.demo.assistant.AssistantApplication` con JDK 21.

## Test REST con curl

Aprire un **terzo terminale**:

```bash
# Caso positivo
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"failAt":"none"}'

# Caso con errore simulato nella spedizione
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"failAt":"shipping"}'

# Sostituire con l'id UUID restituito dalla POST
ORDER_ID='INSERISCI-UUID'
curl -i "http://localhost:8090/assistant/orders/$ORDER_ID"
```

Esempio **illustrativo** della risposta AI (il testo `answer` varia in base al modello):

```json
{
  "orderId": "00000000-0000-0000-0000-000000000001",
  "status": "CANCELLED",
  "simulatedFailureStep": "shipping",
  "answer": "L'ordine risulta annullato. Per il test era stato configurato un errore nella spedizione; la causa effettiva non è disponibile."
}
```

Ripetere con `failAt` pari a `payment`, `inventory`, `shipping` e `none`. Gli esiti attesi, se le compensazioni riescono, sono rispettivamente `CANCELLED`, `CANCELLED`, `CANCELLED`, `COMPLETED`. È possibile anche lo stato `COMPENSATION_FAILED`.

## Test con Postman

Creare le seguenti richieste nella stessa collection:

| Richiesta | Metodo | URL | Body / risultato atteso |
|---|---|---|---|
| Ordine riuscito | POST | `http://localhost:8081/orders` | JSON `{"failAt":"none"}` → 201, `COMPLETED` |
| Errore pagamento | POST | `http://localhost:8081/orders` | JSON `{"failAt":"payment"}` → 201, `CANCELLED` se compensato |
| Errore inventario | POST | `http://localhost:8081/orders` | JSON `{"failAt":"inventory"}` → 201, `CANCELLED` se compensato |
| Errore spedizione | POST | `http://localhost:8081/orders` | JSON `{"failAt":"shipping"}` → 201, `CANCELLED` se compensato |
| Spiegazione AI | GET | `http://localhost:8090/assistant/orders/{{orderId}}` | 200, campi `orderId`, `status`, `simulatedFailureStep`, `answer` |

Nella scheda **Tests** delle POST, salvare l'UUID:

```javascript
pm.test('HTTP 201', () => pm.response.to.have.status(201));
const data = pm.response.json();
pm.expect(data.id).to.be.a('string');
pm.collectionVariables.set('orderId', data.id);
```

Nella GET dell'assistente:

```javascript
pm.test('HTTP 200', () => pm.response.to.have.status(200));
const data = pm.response.json();
pm.expect(data.orderId).to.eql(pm.collectionVariables.get('orderId'));
pm.expect(data.status).to.be.oneOf(['PENDING','COMPLETED','CANCELLED','COMPENSATION_FAILED']);
pm.expect(data.answer).to.be.a('string').and.not.empty;
```

## API Testing with Postman

Il repository include **due collection Postman indipendenti**, dedicate rispettivamente agli scenari positivi e negativi. Conservale nella directory `postman/`:

- [Collection test positivi](postman/Saga-Order-AI-TEST-POSITIVI.postman_collection.json)
- [Collection test negativi](postman/Saga-Order-AI-TEST-NEGATIVI.postman_collection.json)
- [Guida ai test Postman](postman/GUIDA-POSTMAN-IT.md)

### Importazione ed esecuzione

1. Avvia i servizi Docker Compose, Ollama e `assistant-service` seguendo le istruzioni precedenti.
2. In Postman scegli **Import** e importa entrambi i file `.postman_collection.json`.
3. Controlla le variabili delle collection (URL dei servizi e ID degli ordini) e adattale all'ambiente locale.
4. Avvia il **Collection Runner**, eseguendo **prima la suite positiva** e **poi quella negativa**.
5. Controlla codici HTTP, asserzioni JSON, ID salvati ed eventuali test falliti.

### Risultati attesi

| Suite | Scenario | Risultato atteso |
| --- | --- | --- |
| Positiva | Creazione ordine (`failAt: none`) | HTTP 201; `COMPLETED` |
| Positiva | Lettura ordine completato | HTTP 200; `COMPLETED` |
| Positiva | Verifiche pagamento, magazzino e spedizione | Risposte corrette e risorse attive |
| Positiva | Interrogazione assistente AI su ordine esistente | Risposta valida con spiegazione |
| Negativa | Errore simulato nel pagamento | Ordine creato; Saga `CANCELLED` |
| Negativa | Errore simulato nel magazzino | Ordine creato; Saga `CANCELLED` |
| Negativa | Errore simulato nella spedizione | Ordine creato; Saga `CANCELLED` |
| Negativa | Input non valido / risorsa assente | Errore client appropriato all'endpoint |

**Attenzione:** una Saga annullata intenzionalmente può restituire **HTTP 201** alla creazione dell'ordine. Il test deve verificare lo stato applicativo `CANCELLED`, non interpretare automaticamente HTTP 201 come un fallimento.

### Verifica PostgreSQL e Kafka

Dopo l'esecuzione delle collection, verifica in PostgreSQL lo stato degli ordini, le compensazioni e la pubblicazione degli eventi nella tabella `outbox_events`. Controlla nel topic Kafka `saga-order-events` gli eventi di completamento o annullamento. I comandi sono descritti nella checklist operativa e nella guida Postman.

Le collection eseguono test di integrazione: i risultati dipendono dai servizi avviati, dallo stato del database e dalla disponibilità di Ollama e del modello configurato. Non pubblicare credenziali reali, token o dati sensibili.

## Come funziona l'agente AI

Il controller `OrderAssistantController` costruisce un `ChatClient` Spring AI e passa al modello una richiesta in italiano. Espone il metodo Java `OrderTool.getOrderStatus()` tramite `@Tool`: è il **tool calling**, cioè il modello può richiedere un'operazione applicativa definita dal programmatore. Il tool effettua `GET /orders/{id}` con `RestClient`, in sola lettura. L'UUID è vincolato alla richiesta HTTP, non è scelto dal modello; il risultato viene memorizzato per evitare letture ripetute nella stessa richiesta. Il modello genera una spiegazione di massimo quattro frasi, ma `status` e `simulatedFailureStep` sono restituiti dal backend sulla base dei dati del servizio ordini, non estratti dal testo generato.

`simulatedFailureStep` indica **solo il guasto impostato nel test**, non una causa tecnica accertata. Il prompt vieta di inventare rimborsi, spedizioni o motivazioni. Se il modello non invoca il tool o il servizio ordini è indisponibile, l'API risponde con errore.

## Configurazione

File: `assistant-service/src/main/resources/application.yml`.

```yaml
server:
  address: 127.0.0.1
  port: 8090
orders:
  base-url: ${ORDER_SERVICE_URL:http://localhost:8081}
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5:3b
    retry:
      max-attempts: 1
  http:
    client:
      connect-timeout: 5s
      read-timeout: 120s
```

Non è richiesta `OPENAI_API_KEY`: la dipendenza Maven utilizzata è `spring-ai-starter-model-ollama`. Per cambiare modello modificare la proprietà `spring.ai.ollama.chat.options.model` e scaricare prima il modello con `ollama pull`.

## Errori e diagnosi

- **400**: UUID malformato nell'URL.
- **404**: UUID formalmente valido ma ordine non trovato, dopo una consultazione riuscita del tool.
- **502**: Ollama/modello non raggiungibile, risposta AI vuota, tool non invocato oppure stato ordine indisponibile.
- **Lentezza**: la prima inferenza può richiedere più tempo per caricare il modello in memoria.

```bash
ollama list
curl http://localhost:11434/api/tags
docker compose logs -f order-service
docker compose ps
```

## Limiti e sicurezza

Demo didattica, **non pronta per produzione**. L'assistente è vincolato a `127.0.0.1`, non ha autenticazione e consulta lo stato in sola lettura. Ollama esegue il modello localmente, ma l'esecuzione non rende automaticamente sicuri dati, endpoint o infrastruttura. Le API `/internal/**` dei servizi Saga non sono protette per l'uso in produzione. Non sono inclusi recovery automatico dell'orchestratore dopo crash, tracing distribuito completo, politiche avanzate di retry e circuit breaker. La consegna degli eventi outbox è *at-least-once*, non *exactly-once*.

## Arresto

Interrompere `assistant-service` con **Ctrl+C**, quindi:

```bash
docker compose down
# ATTENZIONE: elimina anche i dati persistenti del database
docker compose down -v
```

Se `ollama serve` è stato avviato manualmente, interromperlo con Ctrl+C nel relativo terminale.


## Checklist operativa completa: avvio, controlli e diagnosi

> Eseguire i comandi dalla **radice del progetto**. I controlli dei servizi interni servono solo per questa demo locale; non esporre `/internal/**` su reti pubbliche.

### 1. Prerequisiti e porte

```bash
java -version                  # Java 21
mvn -version                   # Maven 3.9+
docker version
docker compose version
ollama --version
```

Verificare che Docker Desktop sia in esecuzione e che le porte `5432`, `9092`, `8081`–`8084`, `8090`, `11434` non siano già occupate. Su macOS:

```bash
lsof -nP -iTCP:8081 -sTCP:LISTEN
lsof -nP -iTCP:8090 -sTCP:LISTEN
lsof -nP -iTCP:11434 -sTCP:LISTEN
```

### 2. Ollama (host macOS)

```bash
# Solo se Ollama non è già in esecuzione
ollama serve
```

In un altro terminale:

```bash
ollama pull qwen2.5:3b
ollama list
curl -fsS http://localhost:11434/api/tags
```

Il modello deve comparire nell'elenco. `ollama serve` resta in primo piano; se Ollama è già avviato, **non** lanciarne una seconda istanza.

### 3. Compilazione e Docker Compose

```bash
mvn clean package
docker compose config                # valida il file Compose
docker compose up --build -d
docker compose ps
```

L'ordine logico è PostgreSQL e Kafka, quindi payment, inventory, shipping e order. Compose attende il controllo di salute di PostgreSQL; per Kafka e i microservizi `service_started` **non garantisce** che siano già pronti. Attendere e verificare prima dei test.

### 4. Verifica di ogni componente

```bash
# PostgreSQL: pronto ad accettare connessioni
docker compose exec -T postgres pg_isready -U saga

# Kafka: broker raggiungibile nel container
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --list

# Microservizi Spring Boot: tutti devono restituire status UP
for port in 8081 8082 8083 8084; do
  echo "--- localhost:$port ---"
  curl -fsS "http://localhost:$port/actuator/health" || echo "Servizio non pronto"
  echo
done
```

**Nota Kafka:** nel Compose il broker pubblicizza `kafka:9092`, un nome risolvibile dalla rete Docker. Il comando qui sopra viene quindi eseguito **dentro** il container Kafka; client Kafka esterni sull'host potrebbero richiedere un listener pubblicizzato diverso.

### 5. Avvio e controllo assistant-service

L'assistente **non è nel Docker Compose**: avviarlo in un terminale separato, dalla radice del progetto:

```bash
mvn -pl assistant-service spring-boot:run
```

Oppure da IntelliJ eseguire `dev.demo.assistant.AssistantApplication` con JDK 21. Verificare Ollama e order-service prima di chiamare l'API dell'assistente. Il primo caricamento del modello può essere lento.

### 6. Test end-to-end: quattro scenari Saga

```bash
# Ordine COMPLETED
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"failAt":"none"}'

# Fallimento simulato payment → CANCELLED
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"failAt":"payment"}'

# Fallimento simulato inventory → CANCELLED
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"failAt":"inventory"}'

# Fallimento simulato shipping → CANCELLED
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"failAt":"shipping"}'
```

Prendere l'`id` UUID dalla risposta e sostituire `ORDER_ID`:

```bash
curl -i http://localhost:8081/orders/ORDER_ID
curl -i http://localhost:8090/assistant/orders/ORDER_ID
```

Per controllare gli stati delle operazioni interne relative allo stesso ordine (endpoint di **sola lettura**):

```bash
curl -i http://localhost:8082/internal/payment/ORDER_ID
curl -i http://localhost:8083/internal/inventory/ORDER_ID
curl -i http://localhost:8084/internal/shipping/ORDER_ID
```

Le risposte dei singoli servizi possono essere diverse o assenti quando la Saga si interrompe prima di raggiungere quello step. Non chiamare manualmente gli endpoint POST interni di compensazione durante il test ordinario.

### 7. Verifica PostgreSQL e Transactional Outbox

Il progetto crea quattro database: `orderdb`, `paymentdb`, `inventorydb`, `shippingdb`. Eseguire:

```bash
docker compose exec -T postgres psql -U saga -d orderdb -c \
  'SELECT id, status, failure_step, details FROM orders ORDER BY id;'

docker compose exec -T postgres psql -U saga -d orderdb -c \
  'SELECT id, aggregate_id, event_type, payload, created_at, published_at FROM outbox_events ORDER BY created_at DESC LIMIT 20;'

docker compose exec -T postgres psql -U saga -d paymentdb -c \
  'SELECT order_id, status FROM payment_authorizations;'

docker compose exec -T postgres psql -U saga -d inventorydb -c \
  'SELECT order_id, status FROM stock_reservations;'

docker compose exec -T postgres psql -U saga -d shippingdb -c \
  'SELECT order_id, status FROM shipments;'
```

`published_at` valorizzato indica che il publisher ha marcato l'evento come pubblicato. Se resta `NULL`, attendere e controllare i log dell'order-service. La pubblicazione è *at-least-once*: eventuali consumer devono essere idempotenti.

### 8. Verifica eventi Kafka

```bash
# Elenco topic
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --list

# Legge eventi storici e termina dopo 5 secondi senza nuovi messaggi
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic saga-order-events --from-beginning --timeout-ms 5000
```

Controllare gli eventi `COMPLETED` e `CANCELLED` associati agli UUID creati. Un timeout del consumer dopo la lettura non implica necessariamente un errore dell'applicazione.

### 9. Test Postman

Creare una collection con variabili `baseUrl=http://localhost:8081`, `assistantUrl=http://localhost:8090` e `orderId`. Creare quattro POST `{{baseUrl}}/orders` con body JSON `{"failAt":"none"}`, `payment`, `inventory`, `shipping`, e una GET `{{assistantUrl}}/assistant/orders/{{orderId}}`.

Nella scheda **Scripts → Post-response** della POST:

```javascript
pm.test('HTTP 201', () => pm.response.to.have.status(201));
const body = pm.response.json();
pm.test('UUID presente', () => pm.expect(body.id).to.be.a('string').and.not.empty);
pm.collectionVariables.set('orderId', body.id);
```

Per la GET dell'assistente:

```javascript
pm.test('HTTP 200', () => pm.response.to.have.status(200));
const body = pm.response.json();
pm.test('Ordine corretto', () => pm.expect(body.orderId).to.eql(pm.collectionVariables.get('orderId')));
pm.test('Risposta AI non vuota', () => pm.expect(body.answer).to.be.a('string').and.not.empty);
```

Eseguire prima il POST, poi la GET usando l'UUID salvato. Per ogni scenario verificare anche il database e Kafka con i comandi precedenti.

### 10. Diagnostica e risoluzione problemi

| Sintomo | Controllo | Intervento |
|---|---|---|
| Docker non risponde | `docker version` | Avviare Docker Desktop |
| Porta occupata | `lsof -nP -iTCP:PORT -sTCP:LISTEN` | Fermare il processo in conflitto o riconfigurare la porta |
| PostgreSQL non pronto | `docker compose logs postgres` | Attendere l'health check; controllare volume e credenziali |
| Microservizio non risponde | `docker compose ps` e `docker compose logs --tail=100 NOME_SERVIZIO` | Verificare errori di startup, DB e dipendenze |
| Kafka non raggiungibile | `docker compose logs kafka` | Controllare broker e listener; usare `kafka:9092` nella rete Docker |
| Nessun evento outbox pubblicato | `docker compose logs order-service` e query su `outbox_events` | Verificare publisher e Kafka; attendere il ciclo schedulato |
| Ollama non risponde | `curl http://localhost:11434/api/tags` | Avviare Ollama; verificare la porta |
| Modello non disponibile | `ollama list` | `ollama pull qwen2.5:3b` |
| Assistente lento / 502 | log terminale `assistant-service` | Verificare Ollama, memoria, modello e `order-service` |
| Risposta 404 su ordine | `GET /orders/{id}` | Verificare UUID creato e database corretto |

Log utili:

```bash
docker compose logs --tail=100 order-service
docker compose logs --tail=100 payment-service inventory-service shipping-service
docker compose logs --tail=100 postgres kafka
docker compose logs -f order-service
```

### 11. Arresto sicuro e riavvio

```bash
# Terminare assistant-service con Ctrl+C nel suo terminale
# Fermare e rimuovere i container, mantenendo i dati PostgreSQL
docker compose down

# Riavvio successivo
docker compose up --build -d
```

**Solo se si desidera cancellare i dati persistenti:**

```bash
docker compose down -v
```

`down -v` elimina il volume `pgdata` e quindi i dati dei database. Interrompere `ollama serve` con Ctrl+C solo se avviato manualmente; il modello scaricato rimane installato.
