# Implementation Plan: Refresh Token

**Use case**: `refresh-token`
**Module**: `mbw-account`（spec 在 `specs/auth/` 因 token lifecycle 跨登录方式横切；M2+ 评估抽 mbw-auth 模块）
**Spec**: [`./spec.md`](./spec.md)
**Builds on**: [`../../account/login-by-phone-sms/plan.md`](../../account/login-by-phone-sms/plan.md) + [`../../account/login-by-password/plan.md`](../../account/login-by-password/plan.md)

> 本 use case 是 Phase 1 中**最复杂**的一个 — 引入新表 `account.refresh_token` + retrofit 三个既有 UseCase（register-by-phone / login-by-phone-sms / login-by-password）。

## Constitution Check

| 原则 | 本 use case 落实 |
|---|---|
| Modular Monolith | 工作仅在 `mbw-account`；spec 组织在 `specs/auth/` 仅作命名约定，不创建新模块 |
| DDD 5 层 | 新增 domain `RefreshTokenRecord` + repo + hasher；application 新增 `RefreshTokenUseCase`；web 扩展 `AuthController` |
| TDD 强制 | 红绿循环全覆盖；UseCase 单测 + Testcontainers IT |
| Conventional Commits | `docs(auth): ...` (本 PR) + `feat(auth): ...`（impl PR） |
| ArchUnit 边界守护 | 新代码不破坏既有边界 |
| DB schema 隔离 | 新表 `account.refresh_token` 在 account schema；账号引用走 ID（不加 FK） |
| Expand-migrate-contract | **新表纯增** → 不适用 |

## Project Structure

```text
mbw-account/
├── src/main/java/com/mbw/account/
│   ├── domain/
│   │   ├── model/RefreshTokenRecord.java          ← **新建** 聚合根
│   │   ├── model/RefreshTokenHash.java            ← **新建** 值对象（封装 SHA-256 hash 字符串）
│   │   ├── repository/RefreshTokenRepository.java ← **新建** 接口
│   │   └── service/RefreshTokenHasher.java        ← **新建** 封装 SHA-256 算法（便于 M2+ 切 keyed HMAC）
│   ├── application/
│   │   ├── usecase/RefreshTokenUseCase.java       ← **新建**
│   │   ├── command/RefreshTokenCommand.java       ← **新建** record { rawRefreshToken, clientIp }
│   │   └── result/RefreshTokenResult.java         ← **新建** record { accountId, accessToken, refreshToken }
│   ├── infrastructure/
│   │   ├── persistence/RefreshTokenJpaEntity.java ← **新建** JPA Entity
│   │   ├── persistence/RefreshTokenJpaRepository.java ← **新建** Spring Data JPA 接口
│   │   ├── persistence/RefreshTokenRepositoryImpl.java ← **新建** 适配器
│   │   └── persistence/RefreshTokenMapper.java    ← **新建** MapStruct
│   └── web/
│       ├── controller/AuthController.java         ← **改**：加 refreshToken 方法（1.1 / 1.2 已扩展该 controller）
│       ├── request/RefreshTokenRequest.java       ← **新建**
│       └── response/LoginResponse.java            ← 复用（1.1 已建）
└── src/main/resources/db/migration/account/
    └── V4__create_refresh_token_table.sql         ← **新建**
```

### Retrofit existing UseCases（per FR-009）

```text
mbw-account/src/main/java/com/mbw/account/application/usecase/
├── RegisterByPhoneUseCase.java       ← 改：签 refresh token 后写 RefreshTokenRecord
├── LoginByPhoneSmsUseCase.java       ← 同上（1.1 完成后）
└── LoginByPasswordUseCase.java       ← 同上（1.2 完成后）
```

注：本 PR 实施时若 1.1 / 1.2 impl PR 还未合并，retrofit 需依赖。建议执行顺序：1.1 impl + 1.2 impl 合并后再开 1.3 impl PR。

## Domain Design

### RefreshTokenRecord 聚合根

```java
public final class RefreshTokenRecord {
  private final RefreshTokenRecordId id;       // null on creation, assigned on save
  private final RefreshTokenHash tokenHash;    // SHA-256 of raw token
  private final AccountId accountId;
  private final Instant expiresAt;
  private final Instant revokedAt;             // nullable; null = active
  private final Instant createdAt;

  // Factory method
  public static RefreshTokenRecord createActive(
      RefreshTokenHash hash, AccountId accountId, Instant expiresAt, Instant now) {
    return new RefreshTokenRecord(null, hash, accountId, expiresAt, /* revokedAt= */ null, now);
  }

  // Domain method
  public RefreshTokenRecord revoke(Instant now) {
    if (revokedAt != null) {
      throw new IllegalStateException("Already revoked at " + revokedAt);
    }
    return new RefreshTokenRecord(id, tokenHash, accountId, expiresAt, now, createdAt);
  }

  public boolean isActive(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }
}
```

### RefreshTokenHasher（domain service）

```java
public final class RefreshTokenHasher {
  public RefreshTokenHash hash(String rawToken) {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] hashBytes = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
    return new RefreshTokenHash(HexFormat.of().formatHex(hashBytes));
  }
}
```

封装算法便于 M2+ 切 keyed HMAC（per spec.md CL-001 落点）。

### RefreshTokenRepository

```java
public interface RefreshTokenRepository {
  RefreshTokenRecord save(RefreshTokenRecord record);
  Optional<RefreshTokenRecord> findByTokenHash(RefreshTokenHash hash);
  void revoke(RefreshTokenRecordId id, Instant revokedAt);
  // Phase 1.4 logout-all 用：
  int revokeAllForAccount(AccountId accountId, Instant revokedAt);
}
```

实现见 `RefreshTokenRepositoryImpl`：

- `save` → JPA `save`
- `findByTokenHash` → `SELECT * FROM refresh_token WHERE token_hash = ? LIMIT 1`
- `revoke` → `UPDATE refresh_token SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`（双重保险防覆盖 revoke）
- `revokeAllForAccount` → `UPDATE refresh_token SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL`（partial index 加速）

## UseCase: `RefreshTokenUseCase`

```java
@Service
public class RefreshTokenUseCase {
  private final RateLimitService rateLimitService;
  private final RefreshTokenHasher hasher;
  private final RefreshTokenRepository refreshTokenRepository;
  private final AccountRepository accountRepository;
  private final TokenIssuer tokenIssuer;
  private final AccountStateMachine stateMachine;

  @Transactional(rollbackFor = Throwable.class)
  public RefreshTokenResult execute(RefreshTokenCommand command) {
    // 1. 限流（FR-005）
    rateLimitService.consumeOrThrow("refresh:" + command.clientIp(), Duration.ofSeconds(60));
    var hash = hasher.hash(command.rawRefreshToken());
    rateLimitService.consumeOrThrow("refresh:" + hash.value(), Duration.ofSeconds(60));

    // 2. 查记录 + 校验
    Instant now = Instant.now();
    Optional<RefreshTokenRecord> recordOpt = refreshTokenRepository.findByTokenHash(hash);
    if (recordOpt.isEmpty() || !recordOpt.get().isActive(now)) {
      throw new InvalidCredentialsException();  // 不存在 / 过期 / 已 revoke 统一返
    }
    RefreshTokenRecord record = recordOpt.get();

    // 3. 校验关联账号
    Optional<Account> accountOpt = accountRepository.findById(record.accountId());
    if (accountOpt.isEmpty() || !stateMachine.canLogin(accountOpt.get())) {
      throw new InvalidCredentialsException();  // 账号不存在 / FROZEN
    }

    // 4. rotate: 签新 token + 写新记录 + revoke 旧记录（事务内）
    String newAccessToken = tokenIssuer.signAccess(record.accountId());
    String newRefreshToken = tokenIssuer.signRefresh();
    var newRecord = RefreshTokenRecord.createActive(
        hasher.hash(newRefreshToken),
        record.accountId(),
        now.plus(Duration.ofDays(30)),
        now
    );
    refreshTokenRepository.save(newRecord);
    refreshTokenRepository.revoke(record.id(), now);

    return new RefreshTokenResult(record.accountId().value(), newAccessToken, newRefreshToken);
  }
}
```

## Web Layer

### AuthController 扩展（1.1 / 1.2 已建）

```java
@PostMapping("/refresh-token")
public ResponseEntity<LoginResponse> refreshToken(
    @Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
  var result = refreshTokenUseCase.execute(request.toCommand(httpRequest.getRemoteAddr()));
  return ResponseEntity.ok(LoginResponse.from(result));
}
```

**注**：response 复用 1.1/1.2 的 LoginResponse（字段一致 `{accountId, accessToken, refreshToken}`）。

## Retrofit Existing UseCases（per FR-009）

3 个既有 UseCase 在签 refresh token 后**新增**写 RefreshTokenRecord：

```java
// register-by-phone / login-by-phone-sms / login-by-password 内：
String refreshToken = tokenIssuer.signRefresh();
var record = RefreshTokenRecord.createActive(
    hasher.hash(refreshToken),
    account.id(),
    Instant.now().plus(Duration.ofDays(30)),
    Instant.now()
);
refreshTokenRepository.save(record);  // ← 新增此行
```

写入失败回滚整体事务（依赖既有 `@Transactional`）。

## Test Strategy

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `RefreshTokenRecordTest`（新建）| factory / revoke / isActive 各分支 |
| Domain unit | `RefreshTokenHasherTest`（新建）| SHA-256 输出确定性 + 长度 64 hex |
| App unit | `RefreshTokenUseCaseTest`（新建）| Mockito mock 6 依赖；覆盖 7 分支（rotate success / 限流 IP / 限流 token / 不存在 / 过期 / revoked / 账号 FROZEN） |
| Repo IT | `RefreshTokenRepositoryImplIT`（新建）| Testcontainers PG，断言 save / findByTokenHash / revoke / revokeAllForAccount + partial index 生效 |
| Web IT | `AuthControllerRefreshTokenIT`（新建）| `@WebMvcTest`；覆盖 200 / 400 / 401 / 429 / 500 |
| E2E | `RefreshTokenE2EIT`（新建，Testcontainers PG + Redis）| 6 个 Acceptance Scenarios（User Stories 1-3）+ SC-002 / SC-005 |
| Concurrency | `RefreshTokenConcurrencyIT`（新建）| 同 token 10 并发 rotate → 仅 1 成功（SC-003）|
| Retrofit IT | `RegisterByPhoneE2EIT`（既有，扩展 assertion）+ `LoginByPhoneSmsE2EIT` + `LoginByPasswordE2EIT` | 三 E2E 测试断言 DB `account.refresh_token` 表必有对应记录（SC-004） |
| Cross-use-case | 既有 `CrossUseCaseEnumerationDefenseIT` | 加 refresh-token 的 INVALID_CREDENTIALS 断言响应字节级一致 |
| ArchUnit | 既有 `ModuleStructureTest` | 新代码不破坏边界 |

## DB Schema

### Migration: `V4__create_refresh_token_table.sql`

```sql
CREATE TABLE account.refresh_token (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  token_hash      VARCHAR(64) NOT NULL,                                      -- SHA-256 hex (64 chars)
  account_id      BIGINT NOT NULL,                                            -- ref account.id (no FK per CLAUDE.md "禁跨 schema FK"，同 schema 也保守不加)
  expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at      TIMESTAMP WITH TIME ZONE NULL,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- 唯一索引
CREATE UNIQUE INDEX uk_refresh_token_token_hash
  ON account.refresh_token (token_hash);

-- Partial index for logout-all (Phase 1.4) 加速
CREATE INDEX idx_refresh_token_account_id_active
  ON account.refresh_token (account_id)
  WHERE revoked_at IS NULL;
```

回滚：

```sql
-- V4__rollback.sql
DROP TABLE IF EXISTS account.refresh_token;
```

## Phasing & Out of Scope

本 use case 完成：

- ✅ Domain: RefreshTokenRecord / RefreshTokenHash / RefreshTokenHasher / RefreshTokenRepository 接口
- ✅ Application: RefreshTokenUseCase + Command + Result
- ✅ Infrastructure: RefreshTokenJpaEntity / Repository / Mapper / Impl
- ✅ Web: AuthController.refreshToken 方法 + Request DTO
- ✅ Migration: V4__create_refresh_token_table.sql
- ✅ **Retrofit 3 既有 UseCases**（register-by-phone / login-by-phone-sms / login-by-password）
- ✅ Test: 单测 + IT + E2E + Concurrency + Retrofit assertion
- ❌ logout-all use case (Phase 1.4)
- ❌ keyed HMAC / SIEM revocation 告警（M3+ ）
- ❌ Session multi-device management (M3+)

## Verification

```bash
./mvnw -pl mbw-account verify
./mvnw -pl mbw-app -Dtest=ModuleStructureTest test

curl -X POST http://localhost:8080/v3/api-docs > /tmp/spec.json
# 期望：spec.json 含 POST /api/v1/auth/refresh-token 路径
```
