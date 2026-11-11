# PayBridge API

![CI](https://github.com/gopichand-retail-apps/paybridge-backend/actions/workflows/ci.yml/badge.svg)

[Architecture guide](docs/architecture.md) — source map, workflows, contracts, data, security, and checks.

## Repository layout and deployment boundary

This independent repository owns PayBridge's Spring Boot / Java 17 API and authoritative H2 state. The UI is the separate [paybridge-frontend](https://github.com/gopichand-retail-apps/paybridge-frontend) repository. Keep the current directory names, Git histories, origins, and independent builds.

The Vercel configuration belongs to the frontend only. This API has no verified Vercel deployment adapter. Run the Java service on a suitable separate host; no Java runtime configuration is invented here. The local file database is not shared durable storage for stateless function instances. Do not deploy the local simulator as a public API or point it at real records.

Use Java 17 and Maven; the POM is the dependency authority. From the full Retail Apps workspace, `./scripts/Start-Apps.ps1 -App PayBridge` uses the correct built/native entry after setup. From this repository alone, the documented Maven command below starts the service.

Health: http://127.0.0.1:5602/actuator/health. Direct startup stores data under `.local/paybridge`; the workspace launcher can select a separate app-owned path. The [API contract](docs/openapi.yaml) and [canonical architecture](docs/architecture.md) describe the existing routes and trust boundaries. The architecture guide is inside this repository.

Known release gaps: No processor is contacted and no funds move. The additional responsive check fails with horizontal overflow; formatting, durable webhook secrets, identity, and hosting still need work.

Spring Boot 3.5 / Java 17 modular monolith for PayBridge payment orchestration. This API and the React frontend are separate Git repositories.

## Run locally

Prerequisites: JDK 17 and Maven 3.9+. The default local profile uses file-backed H2 so no database service is required.

```sh
mvn test
mvn org.springframework.boot:spring-boot-maven-plugin:3.5.6:run
```

The API listens on `http://127.0.0.1:5602`; health is available at `/actuator/health`. H2 data is stored under `.local/` and Flyway applies `src/main/resources/db/migration` on startup. Start `../paybridge-frontend` separately for the UI at `http://127.0.0.1:5601`. The default mock-mode frontend uses its Vite proxy; an explicitly configured API origin connects directly and must match `FRONTEND_ORIGIN` for credentialed CORS.

## Verify

```sh
mvn -B -Dspring.profiles.active=test verify
sh scripts/check-source-lines.sh
```

The test profile uses in-memory H2. JaCoCo reports coverage under `target/site/jacoco/` and fails
the verify gate if overall instruction, branch, method, or line coverage is below 90%. JaCoCo's
instruction counter is used as the bytecode-level statement proxy; no production packages are
excluded. Critical payment, auth, repository, and webhook classes have explicit 95% instruction,
branch, line, and method gates where those counters apply. The latest local verification passed
60 tests with **100% instruction, 99.54% branch, 100% method, and 100% line coverage**. Java
source and test files are checked against the 120-column policy by `scripts/check-source-lines.sh`,
which also runs in backend CI.

The frontend's isolated Playwright test passed 1/1 in each API-origin mode for the
create/authorize/capture/webhook/reconcile workflow. It uses separate ports and in-memory H2; it
does not connect to the configured PostgreSQL profile. The separate backend CI workflow does not
run the frontend E2E test.

## Workspace profiles

`GET /api/v1/auth/profiles` lists the server's fixed synthetic profile catalog when local profile
login is enabled. The frontend's build-time `VITE_IS_MOCK` setting selects only the configured
PayBridge API origin; both modes fetch profiles from this endpoint. The content model supplies
presentation labels for those API-owned profiles, not an alternate role list.
`POST /api/v1/auth/profile-login` accepts only an account ID, looks it up in the server catalog,
assigns the role server-side, rotates an existing session ID, and creates the server-managed
session. The profile list contains no credentials and local profile sign-in is not an identity
provider. The PostgreSQL profile disables local profile login by default.

| Account ID           | Display name | Role                      |
| -------------------- | ------------ | ------------------------- |
| `merchant-northstar` | Alex Morgan  | Merchant, Northstar Goods |
| `merchant-cedar`     | Casey Rivera | Merchant, Cedar & Coast   |
| `operator`           | Morgan Patel | Operator                  |
| `auditor`            | Sam Rivera   | Auditor, read-only        |

## Payment workflow

1. A merchant creates a payment intent with an `Idempotency-Key`.
2. Merchant or operator simulates authorization; the selected synthetic outcome is approve or decline.
3. An approved intent can be captured. Capture writes a transactional outbox event.
4. A merchant or operator delivers pending events through the in-process HMAC-signed webhook receiver.
5. An operator reconciles captured/refunded totals against verified events.
6. An operator can void an authorized intent or record a bounded simulated refund.

No network call to a payment processor occurs and no real funds move. Refunds are records only. The webhook key is generated in memory for the local H2 profile when no secret is configured; configure a stable secret for a persistent environment.

## Integration with other applications

CommerceHub and RetailFlow can call the payment-intent API from their backend services. The caller owns its order and customer records; PayBridge owns payment intents, attempts, captures, refunds, webhook events, reconciliation, and payment audit records. Use idempotency keys for commands and correlate requests with `X-Request-ID`. Do not share database credentials or query another application's database.

The current application exposes a local profile sign-in path and a signed webhook receiver; it does not yet provide federated service authentication, production callback delivery, or a live processor adapter. A production integration must add a reviewed service identity, explicit network/CORS policy, managed webhook secrets, versioned external contracts, and cross-app authorization tests. The canonical system architecture, data-flow diagrams, and narrated role scenarios are in [repository architecture](docs/architecture.md); the frontend mode/session contract is in [README.md](https://github.com/gopichand-retail-apps/paybridge-frontend/blob/main/README.md).

## PostgreSQL profile

The `postgres` Spring profile uses PostgreSQL, requires a webhook secret, enables Secure session cookies, and disables local profile sign-in by default. Configure a dedicated least-privilege database role and the following environment before running it:

```sh
export SPRING_PROFILES_ACTIVE=postgres
export DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/paybridge'
export DATABASE_USERNAME='paybridge_app'
export DATABASE_PASSWORD='replace-with-a-secret-from-your-secret-manager'
export PAYBRIDGE_WEBHOOK_SECRET='at-least-32-random-bytes-from-your-secret-manager'
export LOCAL_PROFILE_LOGIN_ENABLED=false
mvn org.springframework.boot:spring-boot-maven-plugin:3.5.6:run
```

The local environment used to build this project has no Docker or PostgreSQL server, so the PostgreSQL profile has not been exercised here. H2 is used for local execution and integration tests, not as a production database substitute.

## API and security notes

See [`docs/openapi.yaml`](docs/openapi.yaml), [repository architecture](docs/architecture.md), and [`docs/security.md`](docs/security.md). Session authentication uses HttpOnly, SameSite cookies and CSRF tokens. CORS is restricted to the configured frontend origin. State-changing payment operations require idempotency keys; authorization, ownership scope, input validation, and payment-state transitions are enforced server-side. Structured logs include request IDs and safe event fields; request bodies, secrets, and payment credentials are not logged.

This is a portfolio prototype, not a payment processor. Automated tests and standards-oriented controls do not certify legal or regulatory compliance or guarantee that a deployment will avoid fines. No real card or bank data is permitted. Obtain independent security, legal, tax, privacy, and payment-provider review before production use.
