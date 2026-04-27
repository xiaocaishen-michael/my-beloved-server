# my-beloved-server

后端服务（Java backend）of the `no-vain-years` project. Hosts business modules including account center, PKM, billing, etc.

## Stack

- **JDK**: 21 (Temurin)
- **Framework**: Spring Boot 3.5.x + Spring Modulith 1.4.x
- **Persistence**: Spring Data JPA + PostgreSQL 16 + Flyway
- **Build**: Maven 3.9+ via `./mvnw` (BOM-import style, no parent inheritance)
- **Quality**: Spotless + Palantir Java Format + Checkstyle + JaCoCo + ArchUnit
- **API docs**: Springdoc OpenAPI v3

Detailed stack rationale: see meta repo [`docs/architecture/tech-stack.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/tech-stack.md).

## Module structure

```
my-beloved-server/
├── mbw-shared/               # cross-module kernel (error codes, utilities, event contracts)
├── mbw-app/                  # deployment unit, the only @SpringBootApplication
├── spec/                     # SDD spec directory (spec-kit four-step output; populated PR-2+)
├── config/checkstyle/        # Checkstyle ruleset
├── .specify/                 # spec-kit CLI config + project constitution
└── pom.xml                   # parent POM (BOM-style import)
```

Module strategy: see meta repo [`docs/architecture/modular-strategy.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/modular-strategy.md).

## Local development

```bash
# Verify (compile + test + spotless + checkstyle + jacoco)
./mvnw verify

# Run the app
./mvnw -pl mbw-app spring-boot:run

# Test a single module
./mvnw -pl mbw-shared test
```

Environment variables: see [`.env.example`](./.env.example); use [direnv](https://direnv.net/) for local loading.

## SDD workflow

Business modules follow [GitHub Spec-Kit](https://github.com/github/spec-kit) four-step workflow: `/specify` → `/plan` → `/tasks` → `/implement`. See meta repo [`docs/conventions/sdd.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/sdd.md).

## Conventions

Coding standards, TDD discipline, naming, error handling, logging, etc. detailed in [`CLAUDE.md`](./CLAUDE.md). Cross-repo conventions in [meta repo CLAUDE.md](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/CLAUDE.md).

## Security

Vulnerability reporting: see [`SECURITY.md`](./SECURITY.md).

## License

[Apache License 2.0](./LICENSE)
