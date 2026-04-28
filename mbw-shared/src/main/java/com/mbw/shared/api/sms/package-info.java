/**
 * Cross-module SMS gateway contracts.
 *
 * <p>Business modules depend on these interfaces to send SMS and verify
 * codes without coupling to a specific provider. Concrete implementations
 * (Aliyun client, Redis-backed code store) live in
 * {@code mbw-app/infrastructure/sms}, keeping {@code mbw-shared} free of
 * infrastructure layers — see
 * {@code spec/account/register-by-phone/plan.md} § "SmsCodeService 跨模块归属".
 */
package com.mbw.shared.api.sms;
