package com.mbw.account.application.usecase;

import com.mbw.account.application.port.CipherService;
import com.mbw.account.application.result.RealnameStatusResult;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Query realname status" read-only use case
 * ({@code GET /api/v1/realname/status}, realname-verification spec T12).
 *
 * <p>Per plan.md § core flows: returns {@link RealnameStatusResult#unverified()}
 * when no profile row exists or status is {@code UNVERIFIED}. Sensitive
 * fields (realName / idCardNo) are decrypted only on the {@code VERIFIED}
 * path, then immediately masked via the domain helpers — plaintext never
 * leaves this method.
 *
 * <p>{@code @Transactional(readOnly = true)} keeps the JPA session
 * short-lived; no state mutation.
 */
@Service
public class QueryRealnameStatusUseCase {

    private final RealnameProfileRepository realnameProfileRepository;
    private final CipherService cipherService;

    public QueryRealnameStatusUseCase(
            RealnameProfileRepository realnameProfileRepository, CipherService cipherService) {
        this.realnameProfileRepository = realnameProfileRepository;
        this.cipherService = cipherService;
    }

    @Transactional(readOnly = true)
    public RealnameStatusResult execute(long accountId) {
        Optional<RealnameProfile> opt = realnameProfileRepository.findByAccountId(accountId);
        if (opt.isEmpty()) {
            return RealnameStatusResult.unverified();
        }
        RealnameProfile profile = opt.get();
        return switch (profile.status()) {
            case UNVERIFIED -> RealnameStatusResult.unverified();
            case PENDING -> RealnameStatusResult.pending();
            case FAILED -> RealnameStatusResult.failed(profile.failedReason());
            case VERIFIED -> {
                String realName = new String(cipherService.decrypt(profile.realNameEnc()), StandardCharsets.UTF_8);
                String idCardNo = new String(cipherService.decrypt(profile.idCardNoEnc()), StandardCharsets.UTF_8);
                yield RealnameStatusResult.verified(
                        RealnameProfile.maskRealName(realName),
                        RealnameProfile.maskIdCardNo(idCardNo),
                        profile.verifiedAt());
            }
        };
    }
}
