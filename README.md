# BankFlow

BankFlow is a portfolio banking API. A customer registers, opens accounts, and transfers money. The transfer, the history row, and a transactional outbox event commit together in PostgreSQL. Kafka, the audit trail, and in-app notifications follow after that commit.

It exists to show a modular monolith that keeps money correct under concurrency, then publishes the result without letting a broker outage undo the transfer.

## Architecture

One Spring Boot process. Packages separate responsibilities. There is no generic repository layer, no CQRS, and no event sourcing.

```
com.bankflow
├── auth            registration, login, JWT
├── user            customers, admins, roles
├── account         accounts and balances
├── transfer        atomic transfers
├── transaction     transfer history
├── idempotency     request keys and fingerprints
├── outbox          transactional outbox and Kafka publishing
├── audit           append-only audit trail
├── notification    in-app notifications
├── event           Kafka decoding and consumer idempotency
└── common          errors, health, correlation id, OpenAPI
```

PostgreSQL is the source of truth for balances. Flyway owns the schema. Hibernate uses `ddl-auto: validate`.

A request may send `X-Correlation-Id` (1–64 letters, digits, `.`, `_`, or `-`). BankFlow echoes it and puts it in the log context. If the header is absent, BankFlow generates one. It is not an authenticator and it is not stored on a transfer.

The sequence for a transfer, and the failure cases, are in [docs/architecture.md](docs/architecture.md).

## Technology stack

- Java 21
- Spring Boot 3.5
- Maven
- PostgreSQL 16
- Spring Web, Spring Data JPA, Hibernate
- Spring Security, stateless JWT, BCrypt
- Jakarta Validation
- Flyway
- Spring Kafka
- Springdoc OpenAPI
- Spring Boot Actuator (health and readiness only)
- JUnit 5, Spring Boot test, EmbeddedKafka, Testcontainers

## Main features

- Customer registration and login
- Account ownership
- Atomic transfers with pessimistic locks
- Idempotent transfer requests
- Paginated transfer history
- Transactional outbox and at-least-once Kafka delivery
- Append-only audit trail
- In-app notifications with idempotent consumers

## Authentication

Sessions are not used. `POST /api/auth/register` and `POST /api/auth/login` are public and always create or authenticate a `CUSTOMER`. There is no public admin registration. Passwords are hashed with BCrypt and are never returned or logged.

Every other business route requires `Authorization: Bearer <token>`. `/api/admin/**` also requires `ADMIN`. A customer who calls an admin route receives **403**. A missing or invalid token receives **401**.

`JWT_SECRET` has no default in the application configuration. It must be at least 32 bytes. `JWT_EXPIRATION` is the access-token lifetime in **seconds**.

## Account ownership

A customer creates and reads only their own accounts. Opening an account sets the balance to `0.0000`. The API has no field that sets a balance. `ADMIN` can list and read every account. Reading another customer's account returns **404**.

## Atomic transfers

`POST /api/transfers` debits the source and credits the destination in one database transaction, together with the transfer row, the idempotency record, and the outbox row. The source account must belong to the caller. An admin cannot debit someone else's account. Both accounts must be active, the currencies must match, the amount must be greater than zero, and the source and destination must differ. The source balance must cover the amount. Balances cannot become negative. A failure rolls the whole transaction back.

## Concurrency control

Before balances change, BankFlow locks both account rows with `SELECT ... FOR UPDATE`, lower UUID first. That ordering is what keeps opposite-direction transfers from deadlocking each other. Two concurrent debits of the same balance cannot both succeed when only one can be paid.

## Idempotency

`Idempotency-Key` is required (1–128 characters after trimming) and is unique per user. The fingerprint is the SHA-256 of the source id, destination id, amount at scale 4, and currency. The same key and the same fingerprint return **200** and the original transfer, with no second debit and no second outbox row. The same key with a different fingerprint returns **409**. A business failure rolls the key back so it can be reused. Concurrent duplicates are serialized by the unique index.

## Transaction history

`GET /api/transactions` reads `Transfer` rows, not the unused ledger table. Customers see transfers they own on either side. The filter, the page, and the sort run in the database: `createdAt` descending, then `id` descending. Default page size is 20. Maximum is 100. An unrelated id returns **404**.

## Transactional outbox

The money transaction does not call Kafka. The publisher later reads `PENDING` rows with `FOR UPDATE SKIP LOCKED`, sends the record, and marks `PUBLISHED` only after Kafka acknowledges it. A failed publish increments `attempts`, stores `lastError`, and leaves the row `PENDING`. The transfer stays committed.

## Kafka

The topic is `bankflow.kafka.transfer-topic` (default `bankflow.transfer-events`). The record key is the transfer id. The payload includes `eventVersion` 1 and the amount as a decimal. It does not include passwords, tokens, or idempotency keys. Bootstrap servers come from `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`). The broker in this project does not use SASL. Credentials are not logged.

Consumers use two groups, `bankflow-audit-consumer` and `bankflow-notification-consumer`. Each acknowledges only after its database work commits. This is at-least-once delivery with idempotent consumers. It is not exactly-once.

## Audit trail

Recorded actions are `USER_REGISTERED`, `USER_LOGIN_SUCCESS`, `USER_LOGIN_FAILED`, `ACCOUNT_CREATED`, and `TRANSFER_COMPLETED`. Metadata does not contain a password, password hash, JWT, or `Authorization` header. A failed login stores neither the user id nor the email. `GET /api/admin/audit-logs` is admin-only, newest first, page size 20, maximum 100. There is no update or delete API.

## Notifications

In-app rows only. No email or SMS. A transfer creates `TRANSFER_SENT` for the sender and `TRANSFER_RECEIVED` for the receiver. The same person on both sides gets one notification. `GET /api/notifications` returns only the caller's rows, newest first, page size 20, maximum 100. Another user's id returns **404**. `PATCH /api/notifications/{id}/read` and `PATCH /api/notifications/read-all` update only the caller's rows.

## API endpoints

| Method | Path | Auth | Success |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | public | 201 |
| POST | `/api/auth/login` | public | 200 |
| GET | `/api/auth/me` | bearer | 200 |
| POST | `/api/accounts` | bearer | 201 |
| GET | `/api/accounts` | bearer | 200 |
| GET | `/api/accounts/{id}` | bearer | 200 |
| POST | `/api/transfers` | bearer + `Idempotency-Key` | 201, or 200 on replay |
| GET | `/api/transactions` | bearer | 200 |
| GET | `/api/transactions/{id}` | bearer | 200 |
| GET | `/api/notifications` | bearer | 200 |
| GET | `/api/notifications/{id}` | bearer | 200 |
| PATCH | `/api/notifications/{id}/read` | bearer | 200 |
| PATCH | `/api/notifications/read-all` | bearer | 200 |
| GET | `/api/admin/audit-logs` | admin | 200 |
| GET | `/api/health` | public | 200 |
| GET | `/api/health/kafka` | public | 200 |
| GET | `/actuator/health/liveness` | public | 200 |
| GET | `/actuator/health/readiness` | public | 200 |

Status codes used by the API: **400** invalid input or a business rule, **401** missing or invalid authentication, **403** authenticated without permission, **404** the resource is not available to the caller, **405** the method is not supported, **409** an idempotency or uniqueness conflict, **500** an unexpected error with a generic message. Error bodies use `ApiErrorResponse`. SQL text, stack traces, and exception class names are not returned.

## Database migrations

Flyway applies `src/main/resources/db/migration` on startup. Existing scripts `V1` through `V6` are not edited in place.

| Script | Contents |
| --- | --- |
| V1 | users, accounts, transfers, ledger table, audit logs |
| V2 | idempotency keys, unique `(user_id, idempotency_key)` |
| V3 | outbox events, partial index for pending rows |
| V4 | notifications, unique per user and transfer |
| V5 | `processed_events`, unique `(event_id, consumer_name)` |
| V6 | audit index `(created_at DESC, id DESC)` |

Money columns are `NUMERIC(19, 4)`. Java uses `BigDecimal` at scale 4. JSON writes decimals as plain numbers, not floating point. Notification text rounds to two places for display only.

Indexes that match current queries: `accounts(user_id)` for ownership, transfer source and destination indexes for history, `notifications(user_id, created_at DESC, id DESC)`, `audit_logs(created_at DESC, id DESC)`, the pending-outbox partial index, and the processed-event unique key.

## Running locally

JDK 21 and Maven 3.9+. PostgreSQL 16 on the host, or the Compose database when that port is free.

```powershell
copy .env.example .env
# export the variables, then:
mvn spring-boot:run
```

Spring Boot does not load `.env` by itself. The API listens on `SERVER_PORT` (default 8080).

```powershell
curl http://localhost:8080/api/health
```

## Environment variables

| Variable | Purpose |
| --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL. No credentials are hardcoded in Java. |
| `JWT_SECRET` | Required HMAC key, at least 32 bytes. Replace the example value. |
| `JWT_EXPIRATION` | Access-token lifetime in seconds. |
| `SERVER_PORT` | HTTP port. Default 8080. |
| `KAFKA_BOOTSTRAP_SERVERS` | Broker address. Default `localhost:9092`. |
| `BANKFLOW_TRANSFER_TOPIC` | Transfer topic. |
| `BANKFLOW_AUDIT_GROUP`, `BANKFLOW_NOTIFICATION_GROUP` | Consumer groups. |
| `BANKFLOW_OUTBOX_BATCH_SIZE`, `BANKFLOW_OUTBOX_POLL_INTERVAL`, `BANKFLOW_OUTBOX_PUBLISHER_ENABLED` | Publisher. |
| `BANKFLOW_ALLOWED_ORIGINS` | Comma-separated browser origins. Empty means no CORS headers. `*` is rejected. |
| `BANKFLOW_API_DOCS_ENABLED` | Swagger UI and `/v3/api-docs`. |
| `SPRING_PROFILES_ACTIVE=prod` | Turns API docs off. Still requires real `JWT_SECRET` and database variables from the environment. |

`.env.example` contains placeholders only. Do not commit a real `.env`.

## Docker Compose

`docker-compose.yml` starts PostgreSQL 16 and Kafka 3.9.1 (KRaft, no ZooKeeper). Both services have health checks. The application is not a Compose service. It reads the same environment variables on the host.

Do not publish Compose PostgreSQL onto a port a local PostgreSQL server already uses.

Runtime `docker compose` was not executed in this workspace because Docker is not installed here. The file was reviewed statically. Treat a live Compose run as still pending.

## Running tests

```bash
mvn test
```

The default suite uses the PostgreSQL from the Surefire environment (`localhost:5432`, database `bankflow`) and EmbeddedKafka where a broker is required. Publisher and listener auto-start are off unless a test turns them on.

`ContainerInfrastructureTest` starts a PostgreSQL 16 container and a Kafka container, applies Flyway, commits a transfer, and publishes the outbox row. It is skipped when Docker is not available, so the rest of the suite still runs. It does not replace the concurrency, rollback, idempotency, or consumer tests.

## Swagger / OpenAPI

With API docs enabled:

- `http://localhost:8080/swagger-ui.html`
- `http://localhost:8080/v3/api-docs`

Those documentation routes are public so the contract can be read. Business routes stay authenticated. In Swagger UI, choose **Authorize** and enter the access token. The scheme is HTTP bearer, which sends `Authorization: Bearer <token>`. Examples use fake emails, passwords, and ids. The `prod` profile disables the docs.

## Security considerations

- Stateless JWT. CSRF, HTTP Basic, and form login are off.
- Security response headers stay on, including `X-Content-Type-Options` and `X-Frame-Options: DENY`.
- CORS allows only origins listed in `BANKFLOW_ALLOWED_ORIGINS`, and credentials are not enabled.
- Actuator exposes `health` only, with details hidden. Readiness includes the database. It does not include Kafka.
- Logs record authentication success and failure, completed transfers, outbox publication, consumer processing, and notification creation. They do not record passwords, tokens, `Authorization` headers, `JWT_SECRET`, or database passwords.
- Unexpected errors return `An unexpected error occurred` and log the exception on the server.

## Health and readiness

`GET /api/health` means the process can answer HTTP. It does not check PostgreSQL or Kafka.

`GET /actuator/health/liveness` is the process. `GET /actuator/health/readiness` is the process plus PostgreSQL. A database outage means transfers cannot be accepted, so readiness goes down.

`GET /api/health/kafka` reports whether the broker answered. `DOWN` there does not fail `/api/health` or readiness. A transfer still commits, and the outbox row stays `PENDING` until the broker returns.

## Eventual consistency

The transfer response does not include the audit row or the notifications. Those appear after the publisher and the consumers run. Consumers load the transfer from PostgreSQL. A duplicate Kafka record does not create a second audit row or a second notification. A database error is not acknowledged, so Kafka can redeliver. A malformed payload, an unsupported version, or a transfer that no longer exists is logged and acknowledged so one bad record does not block the partition.

## Known limitations

- No deposits, withdrawals, currency conversion, refunds, scheduled transfers, or payment gateway.
- The `transactions` ledger table is not written by the API.
- No email, SMS, or mobile client.
- Kafka is at-least-once, not exactly-once.
- `FOR UPDATE SKIP LOCKED` is in the publisher query. Multi-instance publishing was not run as two live processes.
- Docker Compose and the Testcontainers test were not executed on this machine.
- There is no public admin registration.
