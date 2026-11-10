# PayBridge Security Notes

## Scope and limits

PayBridge is a portfolio payment orchestration implementation using synthetic records. Its controls are not a security certification or legal opinion. No real payment processing, PAN, CVV, bank credentials, or processor tokens are supported. Passing tests or following standards does not guarantee legal compliance, prevent fines, or make this design production-ready.

## Assets and trust boundaries

- **Browser boundary:** untrusted UI input, session cookie, CSRF token, and request ID.
- **API boundary:** authenticated requests, role checks, validation, payment state changes, and safe error responses.
- **Persistence boundary:** payment intents, integer-minor-unit amounts, attempts, captures, refunds, webhook/outbox records, idempotency records, and audit events.
- **Webhook boundary:** exact serialized payload and HMAC-SHA256 signature; receiver verifies before recording and deduplicates event IDs.
- **Logging boundary:** request IDs and allowlisted event fields only; no request/response body logging, session cookies, secrets, or card/bank data.

## Roles and authorization

| Role     | Scope                                                                                     |
| -------- | ----------------------------------------------------------------------------------------- |
| Merchant | Own merchant's intents; create, authorize, capture, and deliver their simulated webhooks. |
| Operator | Cross-merchant operations; authorize/capture, void, refund, deliver webhooks, reconcile.  |
| Auditor  | Read-only access; no create or state-changing actions.                                    |

The backend derives the principal from the server session. It does not trust a client-supplied role.
The frontend's build-time `VITE_IS_MOCK` setting selects the configured PayBridge API origin only.
It does not select a role catalog or grant authority. In either mode, the client obtains profile
choices from `GET /api/v1/auth/profiles`, submits only an account ID to
`POST /api/v1/auth/profile-login`, and receives a server-managed session after the API validates
the account and assigns its role. The content model supplies presentation labels for those
API-owned profiles; it contains no credentials. Merchant-scoped reads and mutations return
not-found for another merchant's intent.

## Abuse cases and controls

- `VITE_IS_MOCK` defaults to true and is resolved at build time. It selects the configured PayBridge API origin, not payment behavior, profile source, or authorization. Both modes obtain profiles and create sessions through the API and use API-owned payment state, ownership, and idempotency. Configured origins must be PayBridge APIs; no live processor or external identity provider is used.

| Abuse case                                | Control in this prototype                                                                                                                                             | Verification                                                                                                               |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Unauthenticated access                    | Session authentication on `/api/v1/**` except explicit bootstrap, local-profile, and webhook routes.                                                                  | Integration tests exercise authenticated routes and profile sign-in.                                                       |
| Cross-merchant IDOR                       | Merchant scope is applied to reads and mutations; foreign intent returns 404.                                                                                         | Integration test checks list, detail, and capture with two merchant sessions.                                              |
| Role escalation                           | Local profile sign-in accepts a fixed account ID only; role comes from the server catalog. Mutations check role server-side.                                          | Integration tests reject unknown account IDs, role injection, and auditor mutations.                                       |
| Client mode/role spoofing                 | Build-time `VITE_IS_MOCK` selects only the API origin; both modes fetch the API-owned profile catalog, and the API accepts catalog IDs and assigns roles server-side. | Frontend tests API profile discovery and both API-origin modes; backend tests session identity and rejects role injection. |
| CSRF/session abuse                        | Spring Security CSRF token, HttpOnly/SameSite session cookie, session ID rotation at profile sign-in, and logout invalidation.                                        | Integration tests exercise CSRF, session rotation, identity, and logout.                                                   |
| Duplicate or conflicting payment commands | Idempotency keys and request hashes; state transitions are checked before writes.                                                                                     | Integration suite covers duplicate create and invalid transitions.                                                         |
| Webhook forgery/replay                    | HMAC-SHA256 over exact raw body, constant-time signature comparison, event-ID deduplication.                                                                          | Integration test rejects unsigned/invalid signatures and verifies duplicate delivery behavior.                             |
| Unsafe input or payload expansion         | Bean Validation, strict unknown-field handling, bounded request models and request body constraints.                                                                  | Integration tests cover malformed role input and client telemetry allowlist.                                               |
| Client error event leaks                  | `/api/v1/client-errors` accepts only `eventName=ui.error`; it does not accept a stack, URL, or user payload.                                                          | Integration test rejects unknown `stack` field.                                                                            |
| Log leakage                               | Structured logs avoid request/response bodies and sensitive headers; client event logs contain only event name/outcome.                                               | Code inspection; automated tests do not prove complete log redaction.                                                      |

## Residual risks

- Local profile sign-in is not production identity and is disabled by default in the PostgreSQL profile; the default H2 profile is local-only.
- The in-memory webhook secret in the H2 profile changes on restart. A persistent environment must supply a managed secret of at least 32 bytes and protect/rotate it.
- H2 was used for local execution because this machine has no Docker or PostgreSQL server. PostgreSQL behavior still needs environment-specific integration testing.
- Rate limiting is process-local; production abuse controls, distributed rate limits, alerting, session lifecycle, key rotation, backups, retention, and incident response remain deployment work.
- No independent penetration test, OWASP ASVS assessment, PCI assessment, privacy/legal review, or production quality gate has been performed.

Before any real deployment or collection of real user/payment data, perform an independent threat review, legal/privacy/tax review for each jurisdiction, payment-provider/PCI scope review, PostgreSQL migration and backup/restore test, secret-management review, and production security assessment.
