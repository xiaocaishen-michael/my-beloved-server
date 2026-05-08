package com.mbw.account.application.port;

import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;

/**
 * Application-layer port for the upstream realname-verification provider
 * (realname-verification spec T6 / plan D-001). Two implementations:
 *
 * <ul>
 *   <li>{@code AliyunRealnameClient} — production, calls Aliyun cloud-auth
 *       OpenAPI ({@code cloudauth.aliyuncs.com}, plan choice
 *       {@code RPBasic} pending console activation).
 *   <li>{@code BypassRealnameClient} — dev / staging, returns fixed outcomes
 *       per {@code MBW_REALNAME_DEV_FIXED_RESULT} env var.
 * </ul>
 *
 * <p>Profile-routed via {@code @ConditionalOnProperty} so the use case is
 * unaware of which client is wired; production fail-fast in T11 prevents
 * dev-bypass from leaking into prod.
 */
public interface RealnameVerificationProvider {

    /**
     * Initiate a verification session with the upstream provider, passing
     * plaintext name + ID number alongside a server-generated
     * {@code providerBizId} (UUID v4 per D-003) that becomes the idempotency
     * key for later polling.
     *
     * @return liveness URL the client opens in the WebView / SDK
     * @throws ProviderTimeoutException on connect / read timeout or 5xx
     * @throws ProviderErrorException on non-recoverable upstream business error
     */
    InitVerificationResult initVerification(InitVerificationRequest request);

    /**
     * Query the upstream provider for the authoritative outcome of a
     * previously-initiated verification session. Used by
     * {@code ConfirmRealnameVerificationUseCase} when the client polls
     * after the SDK liveness flow completes.
     *
     * @throws ProviderTimeoutException on connect / read timeout or 5xx —
     *     status stays PENDING in the DB so the client may retry
     * @throws ProviderErrorException on unrecoverable business error
     */
    QueryVerificationResult queryVerification(String providerBizId);
}
