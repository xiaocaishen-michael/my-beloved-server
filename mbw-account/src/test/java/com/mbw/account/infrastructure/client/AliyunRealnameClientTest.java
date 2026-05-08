package com.mbw.account.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Drives {@link AliyunRealnameClient} mapping logic against a Mockito-stubbed
 * cloud-auth SDK (T10). Mirrors the {@code AliyunSmsClientTest} pattern —
 * mock the upstream SDK, exercise our wrapper's request building and
 * response parsing without spinning a Spring context or HTTP server.
 */
class AliyunRealnameClientTest {

    private static final String SCENE_ID = "100200";
    private static final String BIZ_ID = "biz-001";
    private static final InitVerificationRequest ANY_INIT_REQUEST =
            new InitVerificationRequest(BIZ_ID, "张三", "11010119900101004X");

    private Client sdk;
    private AliyunRealnameClient client;

    @BeforeEach
    void setUp() {
        sdk = Mockito.mock(Client.class);
        AliyunRealnameProperties properties =
                new AliyunRealnameProperties("ak", "secret", "cloudauth.aliyuncs.com", SCENE_ID);
        client = new AliyunRealnameClient(sdk, properties);
    }

    @Test
    void initVerification_should_lift_certifyUrl_into_livenessUrl_on_success() throws Exception {
        when(sdk.initFaceVerify(any(InitFaceVerifyRequest.class)))
                .thenReturn(initResponse("Success", "https://h5.cloudauth/start?bizId=" + BIZ_ID));

        InitVerificationResult result = client.initVerification(ANY_INIT_REQUEST);

        assertThat(result.livenessUrl()).isEqualTo("https://h5.cloudauth/start?bizId=" + BIZ_ID);
    }

    @Test
    void initVerification_should_throw_ProviderTimeoutException_on_5xx() throws Exception {
        when(sdk.initFaceVerify(any(InitFaceVerifyRequest.class))).thenThrow(teaException(503, "ServiceUnavailable"));

        assertThatThrownBy(() -> client.initVerification(ANY_INIT_REQUEST))
                .isInstanceOf(ProviderTimeoutException.class);
    }

    @Test
    void initVerification_should_throw_ProviderErrorException_on_business_failure_code() throws Exception {
        when(sdk.initFaceVerify(any(InitFaceVerifyRequest.class))).thenReturn(initResponse("InvalidParameter", null));

        assertThatThrownBy(() -> client.initVerification(ANY_INIT_REQUEST))
                .isInstanceOf(ProviderErrorException.class)
                .hasMessageContaining("InvalidParameter");
    }

    @Test
    void queryVerification_should_return_PASSED_when_verifyStatus_is_1() throws Exception {
        when(sdk.describeVerifyResult(any(DescribeVerifyResultRequest.class))).thenReturn(queryResponse(1));

        QueryVerificationResult result = client.queryVerification(BIZ_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
    }

    @Test
    void queryVerification_should_default_to_NAME_ID_NOT_MATCH_when_verifyStatus_is_0() throws Exception {
        when(sdk.describeVerifyResult(any(DescribeVerifyResultRequest.class))).thenReturn(queryResponse(0));

        QueryVerificationResult result = client.queryVerification(BIZ_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.NAME_ID_NOT_MATCH);
    }

    @Test
    void queryVerification_should_throw_ProviderErrorException_on_system_error_verifyStatus_2() throws Exception {
        when(sdk.describeVerifyResult(any(DescribeVerifyResultRequest.class))).thenReturn(queryResponse(2));

        assertThatThrownBy(() -> client.queryVerification(BIZ_ID))
                .isInstanceOf(ProviderErrorException.class)
                .hasMessageContaining("verifyStatus=2");
    }

    @Test
    void queryVerification_should_throw_ProviderErrorException_on_pending_verifyStatus_minus_1() throws Exception {
        when(sdk.describeVerifyResult(any(DescribeVerifyResultRequest.class))).thenReturn(queryResponse(-1));

        assertThatThrownBy(() -> client.queryVerification(BIZ_ID))
                .isInstanceOf(ProviderErrorException.class)
                .hasMessageContaining("verifyStatus=-1");
    }

    @Test
    void queryVerification_should_throw_ProviderTimeoutException_on_5xx() throws Exception {
        when(sdk.describeVerifyResult(any(DescribeVerifyResultRequest.class)))
                .thenThrow(teaException(503, "ServiceUnavailable"));

        assertThatThrownBy(() -> client.queryVerification(BIZ_ID)).isInstanceOf(ProviderTimeoutException.class);
    }

    private static InitFaceVerifyResponse initResponse(String code, String certifyUrl) {
        InitFaceVerifyResponseBody body =
                new InitFaceVerifyResponseBody().setCode(code).setMessage("...");
        if (certifyUrl != null) {
            InitFaceVerifyResponseBody.InitFaceVerifyResponseBodyResultObject ro =
                    new InitFaceVerifyResponseBody.InitFaceVerifyResponseBodyResultObject()
                            .setCertifyId("cert-001")
                            .setCertifyUrl(certifyUrl);
            body.setResultObject(ro);
        }
        return new InitFaceVerifyResponse().setBody(body).setStatusCode(200);
    }

    private static DescribeVerifyResultResponse queryResponse(int verifyStatus) {
        DescribeVerifyResultResponseBody body = new DescribeVerifyResultResponseBody()
                .setVerifyStatus(verifyStatus)
                .setRequestId("req-001");
        return new DescribeVerifyResultResponse().setBody(body).setStatusCode(200);
    }

    private static TeaException teaException(int statusCode, String code) {
        TeaException te = new TeaException();
        te.setStatusCode(statusCode);
        te.setCode(code);
        te.setMessage("upstream " + code);
        return te;
    }
}
