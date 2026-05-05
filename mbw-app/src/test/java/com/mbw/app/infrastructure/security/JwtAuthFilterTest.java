package com.mbw.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.service.TokenIssuer;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private FilterChain chain;

    @Test
    void should_pass_through_without_setting_attribute_when_no_header() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(tokenIssuer);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(JwtAuthFilter.ACCOUNT_ID_ATTRIBUTE)).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void should_pass_through_without_setting_attribute_when_header_is_not_bearer() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(tokenIssuer);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(JwtAuthFilter.ACCOUNT_ID_ATTRIBUTE)).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void should_pass_through_without_setting_attribute_when_token_verify_fails() throws Exception {
        when(tokenIssuer.verifyAccess(any())).thenReturn(Optional.empty());
        JwtAuthFilter filter = new JwtAuthFilter(tokenIssuer);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid.jwt.value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(JwtAuthFilter.ACCOUNT_ID_ATTRIBUTE)).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void should_set_accountId_attribute_when_token_verify_succeeds() throws Exception {
        AccountId accountId = new AccountId(42L);
        when(tokenIssuer.verifyAccess("good.jwt.value")).thenReturn(Optional.of(accountId));
        JwtAuthFilter filter = new JwtAuthFilter(tokenIssuer);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good.jwt.value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(JwtAuthFilter.ACCOUNT_ID_ATTRIBUTE)).isEqualTo(accountId);
        verify(chain, times(1)).doFilter(request, response);
    }
}
