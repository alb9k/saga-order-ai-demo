# Transactional Outbox — Saga
Apply this overlay to the **main** branch checkout (copy files preserving paths).
Run `mvn clean test` and `docker compose up --build`.
The existing `saga-order-events` payload format (`UUID|STATUS`) is preserved.
`V2__outbox.sql` is additive and keeps existing orders.
`SagaOrchestrator` calls the transactional `OrderCompletionWriter` only after remote steps/compensations.
A scheduled publisher retries pending rows every 2 seconds, with PostgreSQL `FOR UPDATE SKIP LOCKED`.
Delivery is **at least once**: Kafka consumers must deduplicate. This is not end-to-end exactly once.
Important: saga step execution is still synchronous and remote compensations are best-effort.
No tests/build were executed in this environment. Review and test before merging.
