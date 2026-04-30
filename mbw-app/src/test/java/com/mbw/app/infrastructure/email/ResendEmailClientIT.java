package com.mbw.app.infrastructure.email;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.mbw.shared.api.email.EmailMessage;
import com.mbw.shared.api.email.EmailSendException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link ResendEmailClient} against a WireMock-stubbed Resend API
 * endpoint to verify retry / fail-fast semantics + on-the-wire request
 * shape.
 *
 * <p>Mirrors the {@code AliyunSmsClientTest} structure (Mockito-driven
 * SDK there; WireMock here because the Resend integration is a direct
 * HTTPS call rather than a SDK with mockable methods). Backoff is 1ms in
 * tests so the suite stays sub-second; production uses 200ms-400ms
 * exponential.
 */
@WireMockTest
class ResendEmailClientIT {

    private static final EmailMessage SAMPLE_MSG = new EmailMessage(
            "noreply@mail.xiaocaishen.me",
            "zhangleipd@aliyun.com",
            "[mbw mock SMS] code for +8613800138000",
            "Phone: +8613800138000\nTemplate: SMS_REGISTER_A\ncode=123456");

    private ResendEmailClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        ResendProperties props = new ResendProperties(
                "re_test_apikey", wmInfo.getHttpBaseUrl(), Duration.ofMillis(500), Duration.ofMillis(500));
        client = new ResendEmailClient(props, 3, Duration.ofMillis(1), 1.0);
    }

    @Test
    @DisplayName("first attempt succeeds: POST /emails called exactly once")
    void first_attempt_success_no_retry() {
        stubFor(post("/emails").willReturn(okJson("{\"id\":\"abc123\"}")));

        client.send(SAMPLE_MSG);

        verify(1, postRequestedFor(urlEqualTo("/emails")));
    }

    @Test
    @DisplayName("transient 5xx: retries until success")
    void transient_5xx_then_success() {
        stubFor(post("/emails")
                .inScenario("retry-5xx")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("first-failed"));
        stubFor(post("/emails")
                .inScenario("retry-5xx")
                .whenScenarioStateIs("first-failed")
                .willReturn(okJson("{\"id\":\"abc123\"}")));

        client.send(SAMPLE_MSG);

        verify(2, postRequestedFor(urlEqualTo("/emails")));
    }

    @Test
    @DisplayName("transient 5xx exhausts retries: surfaces EmailSendException after maxAttempts calls")
    void transient_5xx_exhausts_retries() {
        stubFor(post("/emails").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.send(SAMPLE_MSG)).isInstanceOf(EmailSendException.class);

        verify(3, postRequestedFor(urlEqualTo("/emails")));
    }

    @Test
    @DisplayName("permanent 4xx (e.g. 401 invalid api key): no retry, immediate EmailSendException")
    void permanent_4xx_fails_fast() {
        stubFor(post("/emails")
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"unauthorized\",\"message\":\"invalid api key\"}")));

        assertThatThrownBy(() -> client.send(SAMPLE_MSG))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("401");

        verify(1, postRequestedFor(urlEqualTo("/emails")));
    }

    @Test
    @DisplayName("request body + headers shape: Authorization Bearer + JSON with from/to[]/subject/text")
    void request_body_and_headers_correct() {
        stubFor(post("/emails").willReturn(okJson("{\"id\":\"abc\"}")));

        client.send(SAMPLE_MSG);

        verify(postRequestedFor(urlEqualTo("/emails"))
                .withHeader("Authorization", equalTo("Bearer re_test_apikey"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.from", equalTo("noreply@mail.xiaocaishen.me")))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo("zhangleipd@aliyun.com")))
                .withRequestBody(matchingJsonPath("$.subject", containing("mock SMS")))
                .withRequestBody(matchingJsonPath("$.text", containing("code=123456"))));
    }

    @Test
    @DisplayName("missing api key: ctor rejects with IllegalStateException")
    void missing_api_key_rejected() {
        ResendProperties bad =
                new ResendProperties("", "https://api.resend.com", Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> new ResendEmailClient(bad, 3, Duration.ofMillis(1), 1.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mbw.email.resend.api-key");
    }
}
