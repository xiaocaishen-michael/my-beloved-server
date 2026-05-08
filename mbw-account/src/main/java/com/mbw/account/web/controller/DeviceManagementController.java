package com.mbw.account.web.controller;

import com.mbw.account.application.command.RevokeDeviceCommand;
import com.mbw.account.application.query.ListDevicesQuery;
import com.mbw.account.application.result.DeviceListResult;
import com.mbw.account.application.usecase.ListDevicesUseCase;
import com.mbw.account.application.usecase.RevokeDeviceUseCase;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import com.mbw.account.web.exception.MissingAuthenticationException;
import com.mbw.account.web.resolver.DeviceMetadataExtractor;
import com.mbw.account.web.response.DeviceListResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the device-management use cases (per
 * {@code spec/account/device-management/spec.md}).
 *
 * <p>Both endpoints require a valid Bearer JWT carrying the
 * {@code did} claim — the upstream {@code JwtAuthFilter} stashes
 * {@code mbw.accountId} and {@code mbw.deviceId} request attributes on
 * success; either being missing folds to
 * {@link MissingAuthenticationException} → 401 (FR-006).
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/devices?page&size} — paginated list
 *       of active devices for the caller; {@code isCurrent} flag
 *       compares each row's stored {@code device_id} to the access
 *       token's {@code did} (FR-001 / FR-004).
 *   <li>{@code DELETE /api/v1/auth/devices/{recordId}} — revoke one
 *       row; rejects self-revoke 409 (FR-005); idempotent on
 *       already-revoked rows; cross-account targets surface as 404
 *       to defeat enumeration (FR-014).
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth/devices")
public class DeviceManagementController {

    private final ListDevicesUseCase listDevicesUseCase;
    private final RevokeDeviceUseCase revokeDeviceUseCase;

    public DeviceManagementController(ListDevicesUseCase listDevicesUseCase, RevokeDeviceUseCase revokeDeviceUseCase) {
        this.listDevicesUseCase = listDevicesUseCase;
        this.revokeDeviceUseCase = revokeDeviceUseCase;
    }

    @GetMapping
    public ResponseEntity<DeviceListResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        AccountId accountId = authenticatedAccountId(request);
        DeviceId currentDeviceId = authenticatedDeviceId(request);
        String clientIp = DeviceMetadataExtractor.extractIp(request);

        DeviceListResult result =
                listDevicesUseCase.execute(new ListDevicesQuery(accountId, currentDeviceId, page, size, clientIp));
        return ResponseEntity.ok(DeviceListResponse.from(result));
    }

    @DeleteMapping("/{recordId}")
    public ResponseEntity<Void> revoke(@PathVariable long recordId, HttpServletRequest request) {
        AccountId accountId = authenticatedAccountId(request);
        DeviceId currentDeviceId = authenticatedDeviceId(request);
        String clientIp = DeviceMetadataExtractor.extractIp(request);

        revokeDeviceUseCase.execute(
                new RevokeDeviceCommand(accountId, new RefreshTokenRecordId(recordId), currentDeviceId, clientIp));
        return ResponseEntity.ok().build();
    }

    private static AccountId authenticatedAccountId(HttpServletRequest request) {
        Object attr = request.getAttribute("mbw.accountId");
        if (attr instanceof AccountId accountId) {
            return accountId;
        }
        throw new MissingAuthenticationException();
    }

    private static DeviceId authenticatedDeviceId(HttpServletRequest request) {
        // FR-006: missing did claim folds to 401 — the filter stashes deviceId
        // only when verifyAccessWithDevice succeeded.
        Object attr = request.getAttribute("mbw.deviceId");
        if (attr instanceof DeviceId deviceId) {
            return deviceId;
        }
        throw new MissingAuthenticationException();
    }
}
