package com.mbw.account.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import com.mbw.account.application.query.ListDevicesQuery;
import com.mbw.account.application.result.DeviceItem;
import com.mbw.account.application.result.DeviceListResult;
import com.mbw.account.application.usecase.ListDevicesUseCase;
import com.mbw.account.application.usecase.RevokeDeviceUseCase;
import com.mbw.account.domain.exception.CannotRemoveCurrentDeviceException;
import com.mbw.account.domain.exception.DeviceNotFoundException;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.web.exception.AccountWebExceptionAdvice;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc unit-test for {@link DeviceManagementController} +
 * {@link AccountWebExceptionAdvice} (device-management spec T14).
 */
@ExtendWith(MockitoExtension.class)
class DeviceManagementControllerTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final DeviceId CURRENT_DEVICE = new DeviceId("11111111-1111-4111-8111-111111111111");

    @Mock
    private ListDevicesUseCase listDevicesUseCase;

    @Mock
    private RevokeDeviceUseCase revokeDeviceUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DeviceManagementController(listDevicesUseCase, revokeDeviceUseCase))
                .setControllerAdvice(new AccountWebExceptionAdvice())
                .build();
    }

    // ----- GET /api/v1/auth/devices -----

    @Test
    void GET_should_return_200_with_paginated_response_when_authenticated() throws Exception {
        DeviceItem item = new DeviceItem(
                7L,
                CURRENT_DEVICE.value(),
                "MK-iPhone",
                DeviceType.PHONE,
                "上海",
                LoginMethod.PHONE_SMS,
                Instant.parse("2026-05-08T10:00:00Z"),
                true);
        when(listDevicesUseCase.execute(any(ListDevicesQuery.class)))
                .thenReturn(new DeviceListResult(0, 10, 1L, 1, List.of(item)));

        mockMvc.perform(get("/api/v1/auth/devices?page=0&size=10")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].id").value(7))
                .andExpect(jsonPath("$.items[0].location").value("上海"))
                .andExpect(jsonPath("$.items[0].isCurrent").value(true));
    }

    @Test
    void GET_should_omit_ipAddress_per_CL_002() throws Exception {
        DeviceItem item = new DeviceItem(
                7L,
                CURRENT_DEVICE.value(),
                "MK-iPhone",
                DeviceType.PHONE,
                "上海",
                LoginMethod.PHONE_SMS,
                Instant.parse("2026-05-08T10:00:00Z"),
                true);
        when(listDevicesUseCase.execute(any())).thenReturn(new DeviceListResult(0, 10, 1L, 1, List.of(item)));

        mockMvc.perform(get("/api/v1/auth/devices")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isOk())
                .andExpect(
                        content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ipAddress"))));
    }

    @Test
    void GET_should_apply_default_size_10_when_unspecified() throws Exception {
        when(listDevicesUseCase.execute(any())).thenReturn(new DeviceListResult(0, 10, 0L, 0, List.of()));

        mockMvc.perform(get("/api/v1/auth/devices")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isOk());

        ArgumentCaptor<ListDevicesQuery> captor = ArgumentCaptor.forClass(ListDevicesQuery.class);
        verify(listDevicesUseCase).execute(captor.capture());
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(10);
    }

    @Test
    void GET_should_return_401_AUTH_FAILED_when_no_accountId_attribute() throws Exception {
        mockMvc.perform(get("/api/v1/auth/devices").requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void GET_should_return_401_AUTH_FAILED_when_did_claim_missing_per_FR_006() throws Exception {
        // Filter would not stash mbw.deviceId for a token without did claim.
        mockMvc.perform(get("/api/v1/auth/devices").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void GET_should_return_429_with_RetryAfter_when_use_case_throws_RateLimited() throws Exception {
        when(listDevicesUseCase.execute(any()))
                .thenThrow(new RateLimitedException("device-list:account:42", Duration.ofSeconds(60)));

        mockMvc.perform(get("/api/v1/auth/devices")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // ----- DELETE /api/v1/auth/devices/{recordId} -----

    @Test
    void DELETE_should_return_200_with_no_body_when_revoke_succeeds() throws Exception {
        doNothing().when(revokeDeviceUseCase).execute(any());

        mockMvc.perform(delete("/api/v1/auth/devices/7")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void DELETE_should_return_404_DEVICE_NOT_FOUND_when_recordId_missing() throws Exception {
        doThrow(new DeviceNotFoundException()).when(revokeDeviceUseCase).execute(any());

        mockMvc.perform(delete("/api/v1/auth/devices/7")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void DELETE_should_return_409_when_target_is_current_device() throws Exception {
        doThrow(new CannotRemoveCurrentDeviceException())
                .when(revokeDeviceUseCase)
                .execute(any());

        mockMvc.perform(delete("/api/v1/auth/devices/7")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_CURRENT_DEVICE"));
    }

    @Test
    void DELETE_should_return_429_when_use_case_throws_RateLimited() throws Exception {
        doThrow(new RateLimitedException("device-revoke:account:42", Duration.ofSeconds(60)))
                .when(revokeDeviceUseCase)
                .execute(any());

        mockMvc.perform(delete("/api/v1/auth/devices/7")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .requestAttr("mbw.deviceId", CURRENT_DEVICE))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void DELETE_should_return_401_AUTH_FAILED_when_did_claim_missing_per_FR_006() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/devices/7").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }
}
