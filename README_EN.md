# Saga Order AI Demo — English Guide

Java 21 demonstration project combining **Saga Orchestration**, **Transactional Outbox**, and a natural-language assistant powered by **Spring AI + local Ollama**. Based on [saga-order-demo](https://github.com/alb9k/saga-order-demo). The assistant is optional and does not change Saga execution.

## Technology stack and architecture

- Java 21, Maven 3.9+, Spring Boot 3.3.9 for the Saga services; Spring Boot **3.5.7** per `assistant-service`.
- Spring AI **1.1.0**, Ollama, modello **`qwen2.5:3b`** (local inference, no OpenAI key).
- PostgreSQL 16, Kafka 3.8.1, Docker Compose.
- `order-service` (8081): Saga orchestrator; `payment-service` (8082), `inventory-service` (8083), `shipping-service` (8084): operations and compensations.
- `assistant-service` (127.0.0.1:8090): REST API explaining order status; Ollama runs at `localhost:11434`.

```text
Postman / curl
     | GET /assistant/orders/{uuid}
     v
assistant-service :8090 -- Spring AI / ChatClient --> Ollama :11434 (qwen2.5:3b)
     |  @Tool getOrderStatus() (read-only)
     v
order-service :8081 --> payment :8082 --> inventory :8083 --> shipping :8084
     |                       PostgreSQL (separate databases)
     +--> Outbox PostgreSQL --> scheduled publisher --> Kafka (saga-order-events)
```

**Note:** Saga commands use synchronous HTTP calls; Kafka publishes outcome notifications. The Transactional Outbox stores events and publishes them with *at-least-once* delivery; consumers must deduplicate.

## Requirements

macOS/Linux, JDK 21, Maven 3.9+, Docker Desktop/Compose, `curl` e **Ollama** installed. Ensure ports 8081–8084, 8090, 5432, 9092 and 11434 are available. The assistant runs on the host; it is **not** part of `docker-compose.yml`.

## Getting started

From the project root:

```bash
# 1. Start Ollama (unless already running)
ollama serve
```

In a **second terminal**:

```bash
# 2. Download the configured model (first run only)
ollama pull qwen2.5:3b
ollama list

# 3. Build and start the four microservices and infrastructure
mvn clean package
docker compose up --build -d
docker compose ps
curl --fail-with-body http://localhost:8081/actuator/health

# 4. Run the AI assistant locally
mvn -pl assistant-service spring-boot:run
```

If Ollama is already running as an app/service, do not start a second `ollama serve`. Wait until `order-service` health returns `UP`. In IntelliJ, run `dev.demo.assistant.AssistantApplication` using JDK 21.

## REST tests with curl

Open a **third terminal**:

```bash
# Successful order
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"failAt":"none"}'

# Simulated shipping failure
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"failAt":"shipping"}'

# Replace with the UUID returned by POST
ORDER_ID='YOUR-UUID'
curl -i "http://localhost:8090/assistant/orders/$ORDER_ID"
```

**Illustrative** AI response (`answer` varies by model):

```json
{
  "orderId": "00000000-0000-0000-0000-000000000001",
  "status": "CANCELLED",
  "simulatedFailureStep": "shipping",
  "answer": "The order was cancelled. A shipping failure was configured for this test; the actual cause is not available."
}
```

Repeat with `failAt` values `payment`, `inventory`, `shipping`, and `none`. If compensation succeeds, expected statuses are `CANCELLED`, `CANCELLED`, `CANCELLED`, and `COMPLETED`, respectively. `COMPENSATION_FAILED` is also possible.

## Postman tests

Create the following requests in one collection:

| Request | Method | URL | Body / expected result |
|---|---|---|---|
| Successful order | POST | `http://localhost:8081/orders` | JSON `{"failAt":"none"}` → 201, `COMPLETED` |
| Payment failure | POST | `http://localhost:8081/orders` | JSON `{"failAt":"payment"}` → 201, `CANCELLED` if compensated |
| Inventory failure | POST | `http://localhost:8081/orders` | JSON `{"failAt":"inventory"}` → 201, `CANCELLED` if compensated |
| Shipping failure | POST | `http://localhost:8081/orders` | JSON `{"failAt":"shipping"}` → 201, `CANCELLED` if compensated |
| AI explanation | GET | `http://localhost:8090/assistant/orders/{{orderId}}` | 200, campi `orderId`, `status`, `simulatedFailureStep`, `answer` |

In the POST **Tests** tab, save the UUID:

```javascript
pm.test('HTTP 201', () => pm.response.to.have.status(201));
const data = pm.response.json();
pm.expect(data.id).to.be.a('string');
pm.collectionVariables.set('orderId', data.id);
```

For the assistant GET request:

```javascript
pm.test('HTTP 200', () => pm.response.to.have.status(200));
const data = pm.response.json();
pm.expect(data.orderId).to.eql(pm.collectionVariables.get('orderId'));
pm.expect(data.status).to.be.oneOf(['PENDING','COMPLETED','CANCELLED','COMPENSATION_FAILED']);
pm.expect(data.answer).to.be.a('string').and.not.empty;
```

## How the AI agent works

Il controller `OrderAssistantController` costruisce un `ChatClient` Spring AI e passa al modello una richiesta in italiano. Espone il metodo Java `OrderTool.getOrderStatus()` tramite `@Tool`: è il **tool calling**, cioè il modello può richiedere un'operazione applicativa definita dal programmatore. Il tool effettua `GET /orders/{id}` con `RestClient`, in read-only. L'UUID è vincolato alla richiesta HTTP, non è scelto dal modello; il risultato viene memorizzato per evitare letture ripetute nella stessa richiesta. Il modello genera una spiegazione di massimo quattro frasi, ma `status` e `simulatedFailureStep` sono restituiti dal backend sulla base dei dati del servizio ordini, non estratti dal testo generato.

`simulatedFailureStep` represents **only the test-configured failure**, not a verified root cause. The prompt forbids invented refunds, shipments, or causes. If the model does not call the tool or the order service is unavailable, the API returns an error.

## Configuration

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

`OPENAI_API_KEY` is not required: the Maven dependency is `spring-ai-starter-model-ollama`. To switch models, update `spring.ai.ollama.chat.options.model` and download the model with `ollama pull` first.

## Errors and troubleshooting

- **400**: Malformed UUID in the URL.
- **404**: Valid UUID but no matching order, after successful tool invocation.
- **502**: Ollama/model unavailable, empty AI response, tool not invoked, or order status unavailable.
- **Slow response**: the first inference may take longer while loading the model.

```bash
ollama list
curl http://localhost:11434/api/tags
docker compose logs -f order-service
docker compose ps
```

## Limitations and security

Demo didattica, **non pronta per produzione**. L'assistente è vincolato a `127.0.0.1`, non ha autenticazione e consulta lo stato in read-only. Ollama esegue il modello localmente, ma l'esecuzione non rende automaticamente sicuri dati, endpoint o infrastruttura. Le API `/internal/**` dei servizi Saga non sono protette per l'uso in produzione. Non sono inclusi recovery automatico dell'orchestratore dopo crash, tracing distribuito completo, politiche avanzate di retry e circuit breaker. La consegna degli eventi outbox è *at-least-once*, non *exactly-once*.

## Shutdown

Stop `assistant-service` with **Ctrl+C**, then:

```bash
docker compose down
# WARNING: also deletes persisted database data
docker compose down -v
```

If you started `ollama serve` manually, stop it with Ctrl+C in that terminal.


## Complete operations checklist: startup, verification, troubleshooting

> Run commands from the **project root**. Internal service checks are for this local demo only; never expose `/internal/**` to public networks.

### 1. Prerequisites and ports

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

### 2. Ollama (macOS host)

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

The model must appear in the list. `ollama serve` resta in primo piano; se Ollama è già avviato, **non** lanciarne una seconda istanza.

### 3. Build and Docker Compose

```bash
mvn clean package
docker compose config                # valida il file Compose
docker compose up --build -d
docker compose ps
```

L'ordine logico è PostgreSQL e Kafka, quindi payment, inventory, shipping e order. Compose attende il controllo di salute di PostgreSQL; per Kafka e i microservizi `service_started` **non garantisce** che siano già pronti. Attendere e verificare prima dei test.

### 4. Check every component

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

**Kafka note:** nel Compose il broker pubblicizza `kafka:9092`, un nome risolvibile dalla rete Docker. Il comando qui sopra viene quindi eseguito **dentro** il container Kafka; client Kafka esterni sull'host potrebbero richiedere un listener pubblicizzato diverso.

### 5. Start and check assistant-service

The assistant **is not part of Docker Compose**: avviarlo in un terminale separato, dalla radice del progetto:

```bash
mvn -pl assistant-service spring-boot:run
```

Oppure da IntelliJ eseguire `dev.demo.assistant.AssistantApplication` con JDK 21. Verificare Ollama e order-service prima di chiamare l'API dell'assistente. Il primo caricamento del modello può essere lento.

### 6. End-to-end tests: four Saga scenarios

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

Copy the `id` UUID from the response and replace `ORDER_ID`:

```bash
curl -i http://localhost:8081/orders/ORDER_ID
curl -i http://localhost:8090/assistant/orders/ORDER_ID
```

To inspect the internal operations for the same order (**read-only** endpoints):

```bash
curl -i http://localhost:8082/internal/payment/ORDER_ID
curl -i http://localhost:8083/internal/inventory/ORDER_ID
curl -i http://localhost:8084/internal/shipping/ORDER_ID
```

Le risposte dei singoli servizi possono essere diverse o assenti quando la Saga si interrompe prima di raggiungere quello step. Non chiamare manualmente gli endpoint POST interni di compensazione durante il test ordinario.

### 7. Verify PostgreSQL and Transactional Outbox

The project creates four databases: `orderdb`, `paymentdb`, `inventorydb`, `shippingdb`. Run:

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

### 8. Verify Kafka events

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

### 9. Postman tests

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

### 10. Troubleshooting

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

Useful logs:

```bash
docker compose logs --tail=100 order-service
docker compose logs --tail=100 payment-service inventory-service shipping-service
docker compose logs --tail=100 postgres kafka
docker compose logs -f order-service
```

### 11. Safe shutdown and restart

```bash
# Terminare assistant-service con Ctrl+C nel suo terminale
# Fermare e rimuovere i container, mantenendo i dati PostgreSQL
docker compose down

# Riavvio successivo
docker compose up --build -d
```

**Only if you intend to delete persistent data:**

```bash
docker compose down -v
```

`down -v` deletes the `pgdata` volume and its databases. Stop `ollama serve` with Ctrl+C only if manually started; the downloaded model remains installed.
