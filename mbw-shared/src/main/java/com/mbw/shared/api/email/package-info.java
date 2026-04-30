/**
 * Cross-module email gateway contracts.
 *
 * <p>Business modules and infrastructure adapters (e.g.
 * {@code MockSmsCodeSender} routing SMS verification codes via email per
 * ADR-0013) depend on these interfaces to send transactional email
 * without coupling to a specific provider. Concrete implementations
 * (Resend HTTPS API client, logging fallback) live in
 * {@code mbw-app/infrastructure/email}, keeping {@code mbw-shared} free
 * of infrastructure layers.
 *
 * <p>Mirrors the {@link com.mbw.shared.api.sms} layout — both are
 * "external messaging" abstractions whose implementation details
 * (provider SDK / HTTP client / retry policy) belong to the deployment
 * unit, not the cross-module contract.
 */
package com.mbw.shared.api.email;
