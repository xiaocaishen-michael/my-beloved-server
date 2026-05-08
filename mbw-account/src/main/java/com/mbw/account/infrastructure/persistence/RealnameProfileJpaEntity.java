package com.mbw.account.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA persistence shape for {@code account.realname_profile} (V12). Lives in
 * the infrastructure layer so the domain {@code RealnameProfile} aggregate
 * stays framework-free; conversion happens via {@link RealnameProfileMapper}.
 *
 * <p>Field-by-field mirrors the V12 migration: BIGINT IDENTITY id, BIGINT
 * UNIQUE account_id, VARCHAR status (chk constraint enforces enum), BYTEA
 * encrypted PII, CHAR(64) hex hash, VARCHAR provider biz id, TIMESTAMPTZ
 * timestamps, INT retry counter.
 *
 * <p>{@code updated_at} is set explicitly by the use-case layer through the
 * domain aggregate ({@code RealnameProfile.with*}) and propagated via mapper
 * — no JPA {@code @PreUpdate} callback (per V12 amend, consistent with
 * {@code account.account} and the rest of the schema).
 */
@Entity
@Table(name = "realname_profile", schema = "account")
public class RealnameProfileJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, unique = true)
    private Long accountId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "real_name_enc")
    private byte[] realNameEnc;

    @Column(name = "id_card_no_enc")
    private byte[] idCardNoEnc;

    @Column(name = "id_card_hash", length = 64, columnDefinition = "char(64)")
    private String idCardHash;

    @Column(name = "provider_biz_id", length = 64)
    private String providerBizId;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "failed_reason", length = 32)
    private String failedReason;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "retry_count_24h", nullable = false)
    private int retryCount24h;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public byte[] getRealNameEnc() {
        return realNameEnc;
    }

    public void setRealNameEnc(byte[] realNameEnc) {
        this.realNameEnc = realNameEnc;
    }

    public byte[] getIdCardNoEnc() {
        return idCardNoEnc;
    }

    public void setIdCardNoEnc(byte[] idCardNoEnc) {
        this.idCardNoEnc = idCardNoEnc;
    }

    public String getIdCardHash() {
        return idCardHash;
    }

    public void setIdCardHash(String idCardHash) {
        this.idCardHash = idCardHash;
    }

    public String getProviderBizId() {
        return providerBizId;
    }

    public void setProviderBizId(String providerBizId) {
        this.providerBizId = providerBizId;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getFailedReason() {
        return failedReason;
    }

    public void setFailedReason(String failedReason) {
        this.failedReason = failedReason;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    public int getRetryCount24h() {
        return retryCount24h;
    }

    public void setRetryCount24h(int retryCount24h) {
        this.retryCount24h = retryCount24h;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
