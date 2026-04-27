# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in this project, please **do not** open a public GitHub issue.

Use **GitHub Security Advisories** (private disclosure):

- Navigate to the repository's `Security` tab → `Advisories` → `Report a vulnerability`
- Or open this URL directly: https://github.com/xiaocaishen-michael/my-beloved-server/security/advisories/new

This channel is private until the maintainer publishes the advisory.

## Response SLO

| Stage | Target |
|-------|--------|
| Initial acknowledgement | Within 3 business days |
| Severity assessment | Within 7 business days |
| Fix or mitigation plan | Within 30 business days for High/Critical; best-effort for Medium/Low |
| Public disclosure | Coordinated; default 90 days from initial report unless agreed otherwise |

## Scope

In scope:

- Authentication / authorization vulnerabilities (JWT signing flaws, OAuth misuse, session fixation, refresh-token rotation bypass)
- Injection attacks (SQL, JPQL, OS command, header injection)
- Insecure deserialization
- SSRF via outbound HTTP clients (Aliyun SMS, Google JWK fetch, WeChat OAuth callback)
- Cryptographic weaknesses in password hashing, token generation, or transport
- Information disclosure via API responses, error messages, or logs
- Privilege escalation across modules (account / pkm / billing / ...)
- Denial of service caused by unbounded request handling or resource exhaustion

Out of scope:

- Vulnerabilities in third-party dependencies — please report upstream and let Dependabot raise a PR here
- Issues in deployment environments operated by users themselves
- Theoretical attacks without proof-of-concept
- Self-XSS or social engineering against the maintainer

## Acknowledgements

Researchers who follow this policy and submit valid reports may be credited in the published advisory upon request.
