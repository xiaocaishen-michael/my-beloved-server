package com.mbw.account.infrastructure.client;

import com.aliyun.cloudauth20190307.Client;
import com.aliyun.cloudauth20190307.models.DescribeVerifyResultRequest;
import com.aliyun.cloudauth20190307.models.DescribeVerifyResultResponse;
import com.aliyun.cloudauth20190307.models.DescribeVerifyResultResponseBody;
import com.aliyun.cloudauth20190307.models.InitFaceVerifyRequest;
import com.aliyun.cloudauth20190307.models.InitFaceVerifyResponse;
import com.aliyun.cloudauth20190307.models.InitFaceVerifyResponseBody;
import com.aliyun.tea.TeaException;
import com.mbw.account.application.port.InitVerificationRequest;
import com.mbw.account.application.port.InitVerificationResult;
import com.mbw.account.application.port.QueryVerificationResult;
import com.mbw.account.application.port.QueryVerificationResult.Outcome;
import com.mbw.account.application.port.RealnameVerificationProvider;
import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Aliyun cloud-auth (RPBasic) implementation of
 * {@link RealnameVerificationProvider} (realname-verification spec T10).
 *
 * <p>Activated when {@code mbw.realname.dev-bypass} is unset or false —
 * the default for any profile that has not opted into bypass mode.
 *
 * <p>SDK API mapping:
 *
 * <ul>
 *   <li>{@link #initVerification} → {@code Client#initFaceVerify}; lifts
 *       the upstream {@code resultObject.certifyUrl} into our
 *       {@link InitVerificationResult#livenessUrl()}.
 *   <li>{@link #queryVerification} → {@code Client#describeVerifyResult};
 *       maps {@code verifyStatus} (Integer) to our
 *       {@link Outcome} enum.
 * </ul>
 *
 * <p><b>Outcome mapping limitation</b> (spec drift fix #5 of PR-2): the
 * cloud-auth describeVerifyResult API only returns {@code verifyStatus}
 * (1=passed / 0=not passed / -1=pending / other=error) plus comparison
 * scores; it does <b>not</b> distinguish "name+id mismatch" from
 * "liveness failed" or "user canceled". Per the spec amend approved
 * with PR-2, all {@code verifyStatus=0} are mapped to
 * {@link Outcome#NAME_ID_NOT_MATCH} (the most common cause) with the
 * upstream raw message preserved in {@code failureMessage} for trace.
 * This means FR-009 / SC-005 ("USER_CANCELED does not increment the
 * 24h retry counter") is enforced precisely only via
 * {@code BypassRealnameClient}; in production the counter increments
 * for every failed outcome.
 *
 * <p>Failure mapping:
 *
 * <ul>
 *   <li>SDK {@link TeaException} with {@code statusCode >= 500} →
 *       {@link ProviderTimeoutException} (use case may retry / leave
 *       status PENDING for next poll).
 *   <li>SDK {@link TeaException} with other status, or non-success
 *       business code, or {@code verifyStatus} not in {0, 1} →
 *       {@link ProviderErrorException}.
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "mbw.realname.dev-bypass", havingValue = "false", matchIfMissing = true)
public class AliyunRealnameClient implements RealnameVerificationProvider {

    private static final String CERT_TYPE_IDENTITY_CARD = "IDENTITY_CARD";
    private static final String MODE_RPBASIC = "RPBasic";
    private static final String SUCCESS_CODE = "Success";
    private static final int VERIFY_STATUS_PASSED = 1;
    private static final int VERIFY_STATUS_NOT_PASSED = 0;

    private final Client sdk;
    private final String sceneId;

    public AliyunRealnameClient(Client sdk, AliyunRealnameProperties properties) {
        this.sdk = sdk;
        this.sceneId = properties.sceneId();
    }

    @Override
    public InitVerificationResult initVerification(InitVerificationRequest request) {
        InitFaceVerifyRequest sdkRequest = new InitFaceVerifyRequest()
                .setOuterOrderNo(request.providerBizId())
                .setSceneId(toLong(sceneId))
                .setMode(MODE_RPBASIC)
                .setCertName(request.realName())
                .setCertNo(request.idCardNo())
                .setCertType(CERT_TYPE_IDENTITY_CARD);

        InitFaceVerifyResponse response;
        try {
            response = sdk.initFaceVerify(sdkRequest);
        } catch (TeaException te) {
            throw mapTeaException("initFaceVerify", te);
        } catch (Throwable t) {
            throw new ProviderErrorException("Aliyun initFaceVerify failed: " + t.getMessage(), t);
        }

        InitFaceVerifyResponseBody body = response != null ? response.getBody() : null;
        if (body == null) {
            throw new ProviderErrorException("Aliyun initFaceVerify returned empty body");
        }
        if (!isBusinessSuccess(body.getCode())) {
            throw new ProviderErrorException(
                    "Aliyun initFaceVerify business error code=" + body.getCode() + " message=" + body.getMessage());
        }
        InitFaceVerifyResponseBody.InitFaceVerifyResponseBodyResultObject result = body.getResultObject();
        if (result == null
                || result.getCertifyUrl() == null
                || result.getCertifyUrl().isBlank()) {
            throw new ProviderErrorException("Aliyun initFaceVerify returned no certifyUrl");
        }
        return new InitVerificationResult(result.getCertifyUrl());
    }

    @Override
    public QueryVerificationResult queryVerification(String providerBizId) {
        DescribeVerifyResultRequest sdkRequest =
                new DescribeVerifyResultRequest().setBizId(providerBizId).setBizType(MODE_RPBASIC);

        DescribeVerifyResultResponse response;
        try {
            response = sdk.describeVerifyResult(sdkRequest);
        } catch (TeaException te) {
            throw mapTeaException("describeVerifyResult", te);
        } catch (Throwable t) {
            throw new ProviderErrorException("Aliyun describeVerifyResult failed: " + t.getMessage(), t);
        }

        DescribeVerifyResultResponseBody body = response != null ? response.getBody() : null;
        if (body == null || body.getVerifyStatus() == null) {
            throw new ProviderErrorException("Aliyun describeVerifyResult returned no verifyStatus");
        }
        int status = body.getVerifyStatus();
        if (status == VERIFY_STATUS_PASSED) {
            return new QueryVerificationResult(Outcome.PASSED, null);
        }
        if (status == VERIFY_STATUS_NOT_PASSED) {
            String upstreamMessage = body.getRequestId() != null ? "requestId=" + body.getRequestId() : null;
            return new QueryVerificationResult(Outcome.NAME_ID_NOT_MATCH, upstreamMessage);
        }
        throw new ProviderErrorException("Aliyun describeVerifyResult returned unexpected verifyStatus=" + status);
    }

    private static boolean isBusinessSuccess(String code) {
        return SUCCESS_CODE.equalsIgnoreCase(code) || "200".equals(code) || "0".equals(code);
    }

    private static RuntimeException mapTeaException(String op, TeaException te) {
        Integer statusCode = te.getStatusCode();
        if (statusCode != null && statusCode >= 500) {
            return new ProviderTimeoutException("Aliyun " + op + " upstream " + statusCode, te);
        }
        return new ProviderErrorException(
                "Aliyun " + op + " error code=" + te.getCode() + " message=" + te.getMessage(), te);
    }

    private static Long toLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "mbw.realname.aliyun.scene-id must be numeric (Aliyun cloud-auth console scene id), got " + s, e);
        }
    }
}
