package com.mbw.account.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.application.port.InitVerificationRequest;
import com.mbw.account.application.port.InitVerificationResult;
import com.mbw.account.application.port.QueryVerificationResult;
import com.mbw.account.application.port.QueryVerificationResult.Outcome;
import org.junit.jupiter.api.Test;

class BypassRealnameClientTest {

    private static final InitVerificationRequest ANY_REQUEST =
            new InitVerificationRequest("biz-id-uuid", "张三", "11010119900101004X");

    @Test
    void initVerification_should_return_bypass_verified_url_when_fixed_result_is_verified() {
        BypassRealnameClient client = new BypassRealnameClient(new RealnameDevBypassProperties("verified"));

        InitVerificationResult result = client.initVerification(ANY_REQUEST);

        assertThat(result.livenessUrl()).isEqualTo("bypass://verified");
    }

    @Test
    void initVerification_should_return_bypass_failed_url_when_fixed_result_is_failed() {
        BypassRealnameClient client = new BypassRealnameClient(new RealnameDevBypassProperties("failed"));

        InitVerificationResult result = client.initVerification(ANY_REQUEST);

        assertThat(result.livenessUrl()).isEqualTo("bypass://failed");
    }

    @Test
    void queryVerification_should_return_PASSED_when_fixed_result_is_verified() {
        BypassRealnameClient client = new BypassRealnameClient(new RealnameDevBypassProperties("verified"));

        QueryVerificationResult result = client.queryVerification("any-biz-id");

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
    }

    @Test
    void queryVerification_should_return_NAME_ID_NOT_MATCH_when_fixed_result_is_failed() {
        BypassRealnameClient client = new BypassRealnameClient(new RealnameDevBypassProperties("failed"));

        QueryVerificationResult result = client.queryVerification("any-biz-id");

        assertThat(result.outcome()).isEqualTo(Outcome.NAME_ID_NOT_MATCH);
    }
}
