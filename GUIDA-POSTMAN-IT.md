# Suite Postman — saga-order-ai-demo

## File
- `Saga-Order-AI-TEST-POSITIVI.postman_collection.json`: percorso completo, verifiche dei tre servizi, assistente AI, idempotenza.
- `Saga-Order-AI-TEST-NEGATIVI.postman_collection.json`: HTTP 400/404/422/409, compensazioni, scenari di annullamento.

## Prerequisiti
1. Da root: `mvn clean package` e `docker compose up --build -d`.
2. Verificare `docker compose ps` e i servizi su 8081–8084.
3. Avviare Ollama, assicurarsi che `ollama list` contenga `qwen2.5:3b`, quindi avviare `mvn -pl assistant-service spring-boot:run` (porta 8090).
4. Importare entrambe le collection in Postman. Usare **Collection Runner**, eseguendo prima i test positivi e poi quelli negativi; mantenere l'ordine delle richieste. Le variabili sono impostate automaticamente dai test.

## Semantica degli esiti
Un guasto simulato Saga restituisce **HTTP 201** con `status: CANCELLED`: il codice HTTP indica che l'ordine è stato creato; l'esito di business è negativo. I veri test HTTP negativi sono separati nella seconda collection.

La risposta dell'AI non è confrontata parola per parola: si validano `orderId`, `status`, `simulatedFailureStep` e `answer` non vuoto. Il modello deve invocare il tool Spring AI `getOrderStatus()`; se non lo fa, il controller può restituire 502.

## Verifiche SQL aggiuntive (terminale)
Sostituire `<UUID>` con un ID salvato in Postman:

```bash
docker compose exec -T postgres psql -U saga -d orderdb -c "SELECT id,status,failure_step,details FROM orders ORDER BY id DESC LIMIT 10;"
docker compose exec -T postgres psql -U saga -d orderdb -c "SELECT aggregate_id,event_type,payload,published_at FROM outbox_events ORDER BY created_at DESC LIMIT 10;"
docker compose exec -T postgres psql -U saga -d paymentdb -c "SELECT * FROM payment_authorizations;"
docker compose exec -T postgres psql -U saga -d inventorydb -c "SELECT * FROM stock_reservations;"
docker compose exec -T postgres psql -U saga -d shippingdb -c "SELECT * FROM shipments;"
```

## Verifica Kafka
```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic saga-order-events --from-beginning --timeout-ms 5000
```
L'outbox è pubblicata in modo asincrono: attendere qualche secondo prima di verificare `published_at` e Kafka.

## Note
- Gli endpoint `/internal/...` sono usati direttamente per test tecnico: non eseguire queste suite su ambienti di produzione.
- I test negativi con `?fail=true` verificano un errore controllato 422; poi creano, compensano e riprovano la stessa risorsa per ottenere 409.
- Se un servizio non è pronto, la Saga potrebbe fallire per indisponibilità infrastrutturale invece del guasto simulato.
- La suite non è stata eseguita contro un ambiente live: le aspettative sono ricavate dal codice sorgente fornito.
- Per fermare: `docker compose down` (mantiene i volumi). `docker compose down -v` elimina i dati persistenti.
