# APP-05 PayBridge - Agent Instructions

This repository owns the PayBridge backend. Before changes, read `~/Downloads/PORTFOLIO_COMPLETE_AGENT_PLAYBOOK.md` and `~/Downloads/APP-05_PAYBRIDGE.md`; those are the full architecture, UX, security, and delivery requirements.

## Mandatory code quality
- Use supported Java LTS/Spring Boot conventions, constructor injection, DTO validation, and transactional domain boundaries. Keep domain logic independent of web frameworks where practical.
- Keep TS/JS/Java lines at or below 120 characters, except documented URLs, schemas, and generated code. Enforce with ESLint `max-len`, Prettier `printWidth`, or Java Checkstyle as applicable.
- Cognitive complexity is at most 15 per function/method. Control-flow nesting is at most 3 where feasible.
- Target at most 50 logical lines per method, 400 lines per source file except justified compositions/generated code, and 4 parameters. Prefer named parameter objects.
- No unused or duplicate code, unreachable branches, hard-coded credentials, production `System.out` logging, magic business constants, or circular dependencies.
- New-code gate: zero new blocker/critical issues, at most 3% duplication, all security hotspots reviewed, and no high-confidence leaked secrets. Verify actual Sonar rule/edition support; do not assume defaults match.

## Testing and CI
- Enforce at least 90% for each of lines, statements, functions, and branches; at least 95%
  for critical payment/auth/state-machine logic and 90% changed-code coverage. Below-gate
  exceptions must be owner-approved, timeboxed, and reported as unmet; do not
  blanket-exclude business logic.
- Keep explicit 95% JaCoCo class gates for payment transitions, provider simulation, webhook
  handling/signatures, profile auth, idempotency hashing, repository persistence, and security.
  Run `sh scripts/check-source-lines.sh` locally and in CI.
- Test state transitions, valid/invalid input, transactions/rollback, authorization and cross-merchant ownership, idempotency/replay, concurrency, webhook signatures/retries, dependency failure, and safe logging.
- CI must block on formatting, lint, typecheck, unit/integration/E2E, coverage, security/secret/dependency scans, migration validation, build, and configured quality-gate failures. Report skipped checks distinctly.

## Security, logging, and documentation
- Enforce server-side role and per-resource authorization; validate all untrusted inputs; use prepared persistence and database constraints. Simulated payments only; never move real funds or store PAN/CVV.
- Never log credentials, tokens, raw payloads, or identifying financial data. Use structured success/failure logs with request and trace IDs; verify redaction.
- Maintain canonical `docs/architecture.md` with C4, trust-boundary data flow, ERD/data
  ownership, implemented patterns, use cases, failures/recovery, API/events, test commands,
  and deployment boundaries. Architecture diagrams, design patterns, and engineering
  roadmaps belong in Markdown, never product UI. README covers purpose, roles, local setup, limits, and versioned integrations.
- Never claim security or compliance based only on checklists or automated scans.
