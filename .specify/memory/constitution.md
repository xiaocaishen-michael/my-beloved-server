# my-beloved-server Constitution

> AI agents (Claude Code, Cursor, Copilot, etc.) MUST reference this when generating
> `spec.md` / `plan.md` / `tasks.md` via spec-kit four-step workflow.
> Constitution **overrides** any contradictory defaults from spec-kit templates.

## Core Principles

### I. Modular Monolith (NON-NEGOTIABLE)

- Maven multi-module with physical isolation: `mbw-shared` + `mbw-<module>` + `mbw-app`
- `mbw-app` is the ONLY `@SpringBootApplication`
- Spring Modulith verifies module boundaries at runtime
- ArchUnit enforces boundaries in CI
- Cross-module communication: synchronous via `api.service` Beans, asynchronous via Spring Modulith events + outbox; never direct package imports
- Business modules depend ONLY on `mbw-shared`

### II. DDD 5-Layer Package Structure (NON-NEGOTIABLE)

Each business module follows `com.mbw.<module>.{api,domain,application,infrastructure,web}`:

| Layer | Responsibility | Constraints |
|-------|---------------|-------------|
| `api/` | Outward contract (the only entry for cross-module calls) | Pure interfaces + immutable DTOs |
| `domain/` | Business rules + invariants + state machines | **Zero framework dependencies** (no `@Entity`, no `@Component`, no `@JsonIgnore`) |
| `application/` | Use case orchestration + transaction boundaries | Depends on domain + repo interfaces + cross-module api |
| `infrastructure/` | IO implementations | JPA / external API / messaging / config |
| `web/` | HTTP adapters | Controller + Request/Response only; no business logic |

Repository pattern: pure interface in `domain.repository` + JPA implementation in `infrastructure.persistence` + MapStruct mapping. Domain Model and JPA Entity are separate types.

### III. TDD Strict (NON-NEGOTIABLE)

Red → Green → Refactor for each task. Exceptions only for:
- `@Configuration` classes / `application.yml`
- Lombok / MapStruct generated code
- Pure DTO / record (no behavior)
- Spring Data JPA interfaces (no custom `@Query`)
- Pure forwarder controllers (`@WebMvcTest` covers naturally)

Test tasks **bind to** implementation tasks in `tasks.md`; never standalone.

### IV. SDD via Spec-Kit Four-Step

Each business use case follows `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`:

1. `spec/<module>/<usecase>/spec.md` — User Scenarios & Testing / Functional Requirements / Success Criteria (spec-kit's official 3 sections; **do not invent sub-layers**)
2. `spec/<module>/<usecase>/plan.md` — DDD aggregates / Repository / Flyway migration / cross-module deps
3. `spec/<module>/<usecase>/tasks.md` — tasks tagged `[Domain]` / `[Application]` / `[Infrastructure]` / `[Web]`; tests bind to each task
4. Implement runs through Plan Mode review; TDD red-green inside each task

Skip the four-step workflow ONLY for trivial changes (< 30 LOC / doc fixes / config tweaks / typo fixes).

### V. Database Schema Isolation

- One PostgreSQL instance, one schema per module (`account`, `pkm`, `billing`, ...)
- **NO cross-schema foreign keys** — use ID references
- Flyway migrations per module: `mbw-app/src/main/resources/db/migration/<module>/V<n>__<desc>.sql`
- Migrations are immutable once on main; corrections via new migrations

## Technology Constraints

- **JDK 21** (Temurin); no language preview features
- **Spring Boot 3.5.x** + **Spring Modulith 1.4.x** (do not upgrade to Boot 4 until Modulith ships a Boot-4-compatible release; complexity-trigger reviewed in M3)
- **PostgreSQL 16** + Flyway; Redis (M1.1+); MinIO/OSS for blobs
- **Maven 3.9+** via `./mvnw` (BOM import style, NO parent inheritance)
- **OpenAPI** = single source of truth for API contracts; Springdoc generates from annotations; spec.md never duplicates OpenAPI data contracts

## Quality Gates

- **Spotless + Palantir Java Format** — formatter rewrites whitespace / indent / line length / brace position / import order
- **Checkstyle** — lint only (naming / coding bugs / class design / Javadoc public-API / metrics-warning); does NOT manage formatter dimensions
- **Test stack**: JUnit 5 + AssertJ + Mockito 5 + Testcontainers (real PG container; **no DB mocking**) + WireMock + Awaitility
- **Test naming**: `<ClassName>Test` (unit) or `<ClassName>IT` (integration); test packages mirror `src/main`
- **Logging**: SLF4J + Logback; JSON in production; MDC TraceId required; passwords / tokens / verification codes NEVER appear in logs
- **gitleaks** pre-commit hook + CI gitleaks-action (defense in depth)

## Naming Conventions (4-place consistency)

Adding a new module = matching all four names exactly:
- Maven module: `mbw-<module>` (e.g. `mbw-account`)
- Java package: `com.mbw.<module>`
- DB schema: `<module>`
- Frontend feature: `features/<module>` (in `no-vain-years-app`)

ArchUnit + Spring Modulith Verifier catch violations at CI time.

## Anti-Patterns (must reject)

- Cross-module direct dependency (must go through `api.service` Bean or Spring Modulith event)
- Domain model annotated with `@Entity` / `@Column` / `@JsonIgnore` (use MapStruct mapper)
- Cross-schema foreign keys (use ID references)
- Skipping TDD red-green cycle
- `spec.md` duplicating OpenAPI data contracts (OpenAPI = data; spec.md = business rules)
- `tasks.md` finer than 30min-2h work units
- Spec drift > 1 week (delete spec or update; never let stale specs accumulate)
- Self-creating spec sub-layers beyond spec-kit's official 3 sections

## Module Roadmap

- **M1.1**: `mbw-account` (phone register + JWT + NIST password rules)
- **M1.2**: `mbw-account` Google OAuth + frontend mobile scaffold
- **M1.3**: `mbw-account` WeChat OAuth + Cloudflare Turnstile
- **M2**: `mbw-pkm` (LLM-heavy; first split-out candidate post-MVP)
- **M3**: `mbw-billing` (Free + quota monitoring, no payment); internal beta
- **M4**: Public launch + payment integration
- **M5**: Tauri desktop wrapping

## Governance

- Constitution **supersedes** spec-kit defaults and AI agent suggestions when in conflict
- Cross-repo decisions go to meta repo `docs/adr/`
- Use-case-internal decisions stay in that use case's `plan.md`
- Constitution amendments require: explicit user approval + ADR + version bump

## References

- Meta repo (cross-repo conventions): https://github.com/xiaocaishen-michael/no-vain-years
  - [`CLAUDE.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/CLAUDE.md) — index
  - [`docs/architecture/modular-strategy.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/modular-strategy.md) — DDD 5-layer + Repository pattern + cross-module rules
  - [`docs/architecture/tech-stack.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/tech-stack.md) — full stack
  - [`docs/conventions/code-quality.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/code-quality.md) — Spotless / Checkstyle ruleset
  - [`docs/conventions/sdd.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/sdd.md) — SDD workflow + spec-kit integration
  - [`docs/adr/`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/) — all ADRs (0001 – 0010)
- Backend coding conventions detail: sibling [`CLAUDE.md`](../../CLAUDE.md)
- Account center PRD: [account-center.v2.md](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md)

---

**Version**: 0.1.0 | **Ratified**: 2026-04-27 | **Last Amended**: 2026-04-27
