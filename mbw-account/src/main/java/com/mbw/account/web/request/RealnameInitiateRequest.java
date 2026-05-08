package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/realname/verifications request body
 * (realname-verification spec FR-001 / spec.md AC).
 *
 * <p>Field-level Jakarta Validation enforces non-blank presence; deeper checks
 * (GB 11643 checksum on {@code idCardNo}) live in
 * {@code IdentityNumberValidator} and surface as 400
 * {@code REALNAME_INVALID_ID_CARD_FORMAT}.
 */
public record RealnameInitiateRequest(
        @NotBlank @Size(max = 64) String realName,
        @NotBlank @Size(max = 32) String idCardNo,
        @NotBlank @Size(max = 32) String agreementVersion) {}
