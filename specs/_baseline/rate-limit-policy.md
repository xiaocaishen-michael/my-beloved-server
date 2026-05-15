# Rate-limit policy (baseline)

> 跨业务模块共享的限流框架使用规范。具体规则（短信 60s/次 / 登录 5 次/min 等）在
> 各业务模块自己的 spec 内定义；本文是"如何用"的统一约定。

## 框架（ADR-0011 amended 2026-04-28）

- 选型：Bucket4j 8.x（`com.bucket4j:bucket4j-core` + `com.bucket4j:bucket4j-redis`）
- **Backend：M1.1 起即 Redis**（`LettuceBasedProxyManager`）；in-memory 阶段已跳过（amendment 详见 ADR-0011）
- 入口：`com.mbw.shared.web.RateLimitService#consumeOrThrow(key, bandwidth)`
- 失败：抛 `RateLimitedException`，`GlobalExceptionHandler` 自动映射 HTTP 429 + `Retry-After` 头 + ProblemDetail
- **Fail-closed**：Redis 不可用时 `consumeOrThrow` 抛 `RateLimitedException(key, Duration.ZERO)` → 拒服务（避免 fail-open 让攻击者无限制）。运维监控 Redis 可用性

## Key 命名约定

`<scenario>:<subject>` 的格式，**冒号分隔**：

| Scenario | 示例 key | 备注 |
|---------|---------|------|
| `sms` | `sms:+8613800138000` | 按手机号限流 |
| `login` | `login:192.168.1.1` 或 `login:+8613800138000` | 按 IP 或 phone，按业务决定 |
| `register` | `register:+8613800138000` | 按手机号 |
| `passwordReset` | `passwordReset:account-12345` | 按账号 ID |

**禁止** key 中含敏感数据明文（如密码、token）— 只用标识性数据。

## 业务模块用法示例

```java
@Service
public class SmsCodeService {

    private static final Bandwidth SMS_LIMIT = Bandwidth.builder()
            .capacity(1)
            .refillIntervally(1, Duration.ofMinutes(1))
            .build();

    private final RateLimitService rateLimitService;
    private final SmsClient smsClient;

    public SmsCodeService(RateLimitService rateLimitService, SmsClient smsClient) {
        this.rateLimitService = rateLimitService;
        this.smsClient = smsClient;
    }

    public void sendCode(String phone) {
        rateLimitService.consumeOrThrow("sms:" + phone, SMS_LIMIT);
        // 限流通过，发送验证码
        String code = randomCode();
        smsClient.send(phone, code);
    }
}
```

控制器无需 try/catch — 异常自动经 `GlobalExceptionHandler` 映射成：

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 47
Content-Type: application/problem+json

{
  "type": "about:blank",
  "title": "Too many requests",
  "status": 429,
  "detail": "Rate limit exceeded; retry after 47s.",
  "limitKey": "sms:+8613800138000",
  "retryAfterSeconds": 47
}
```

## 多维度限流

业务规则通常需要多个 bandwidth（如"每分钟 5 次 + 每天 100 次"）。Bucket4j 原生支持：

```java
Bucket bucket = Bucket.builder()
    .addLimit(perMinute)
    .addLimit(perDay)
    .build();
```

但当前 `RateLimitService.consumeOrThrow` 只接受单一 bandwidth。M2 升级 API 时再加多维度重载，**M1.1 业务模块需要多维度时直接绕过 RateLimitService 自己用 Bucket4j**（暂时容忍少量重复代码，避免 API 早熟设计）。

## 测试

业务模块测试限流时：

1. 真实 `RateLimitService` 实例 + 调用 `reset(key)` 清桶
2. 或注入 mock `RateLimitService`，验证调用参数

不要在测试内 sleep 等待 refill — 慢且不稳定。

## 监控（M2 后期）

接入 Micrometer 后，`RateLimitService` 应发 `rate_limit.consume` counter（`outcome=passed/blocked` 标签）+ `rate_limit.bucket_size` gauge。M1.1 暂不实施，PR-3 业务规则落地后再加。

## 不在本框架范围

- 网关层限流（Nginx `limit_req`）— 防 DDoS / 暴力 HTTP flooding，与业务限流互补但独立
- 全局每秒 QPS 限流 — 用 Tomcat / Nginx 内建 thread pool 兜底
- 按用户配额（"Pro 用户每月 N 次"）— 属 \`mbw-billing\` 的 entitlement 范畴，不是限流
