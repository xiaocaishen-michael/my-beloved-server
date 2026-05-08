package com.mbw.account.application.service;

import com.mbw.account.api.service.IdentityApi;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import org.springframework.stereotype.Service;

/**
 * Direct-forward implementation of {@link IdentityApi} (realname-verification
 * spec T15). Holds no state, performs no caching — sibling modules calling
 * {@code isVerified(...)} hit the {@code RealnameProfileRepository} every time.
 *
 * <p>Per plan D-005, no Spring Modulith {@code RealnameVerifiedEvent} is
 * published this milestone; M2 (mbw-billing) consumers poll this API instead.
 */
@Service
public class IdentityApiImpl implements IdentityApi {

    private final RealnameProfileRepository realnameProfileRepository;

    public IdentityApiImpl(RealnameProfileRepository realnameProfileRepository) {
        this.realnameProfileRepository = realnameProfileRepository;
    }

    @Override
    public boolean isVerified(long accountId) {
        return realnameProfileRepository
                .findByAccountId(accountId)
                .map(p -> p.status() == RealnameStatus.VERIFIED)
                .orElse(false);
    }
}
