package com.mbw.account.web.request;

import com.mbw.account.application.command.RefreshTokenCommand;
import jakarta.validation.constraints.NotBlank;

/**
 * HTTP body for {@code POST /api/v1/auth/refresh-token}.
 *
 * <p>Format check is the application layer's job (RefreshTokenHasher
 * accepts any string and produces a fixed-size hash); the web layer
 * just rejects empty / null inputs so we don't waste a hash + DB
 * lookup on them.
 */
public record RefreshTokenRequest(@NotBlank String refreshToken) {

    public RefreshTokenCommand toCommand(String clientIp) {
        return new RefreshTokenCommand(refreshToken, clientIp);
    }
}
