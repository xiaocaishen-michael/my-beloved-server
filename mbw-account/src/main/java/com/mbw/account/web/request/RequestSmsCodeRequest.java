package com.mbw.account.web.request;

import com.mbw.account.application.command.SmsCodePurpose;
import jakarta.validation.constraints.NotBlank;

/**
 * HTTP body for {@code POST /api/v1/sms-codes}. Phone-format
 * validation runs in domain ({@code PhonePolicy}); the web layer's
 * {@code @NotBlank} only catches the absent / empty cases — making
 * Spring throw a clean {@code MethodArgumentNotValidException} before
 * the use case runs.
 *
 * <p>{@code purpose} is optional on the wire (FR-009 of
 * login-by-phone-sms): pre-Phase-1.1 callers (e.g. front-end built
 * before T10) omit the field and the controller defaults to
 * {@link SmsCodePurpose#REGISTER} for backwards compatibility.
 */
public record RequestSmsCodeRequest(@NotBlank String phone, SmsCodePurpose purpose) {

    /**
     * Resolve {@code purpose} with a {@code REGISTER} default when the
     * client omitted the field.
     */
    public SmsCodePurpose purposeOrDefault() {
        return purpose == null ? SmsCodePurpose.REGISTER : purpose;
    }
}
