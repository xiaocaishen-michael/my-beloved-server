package com.mbw.account.web.response;

import com.mbw.account.application.result.DeviceListResult;
import java.util.List;

/**
 * HTTP wire shape for {@code GET /api/v1/auth/devices}
 * (device-management spec FR-001).
 */
public record DeviceListResponse(
        int page, int size, long totalElements, int totalPages, List<DeviceItemResponse> items) {

    public static DeviceListResponse from(DeviceListResult result) {
        List<DeviceItemResponse> items =
                result.items().stream().map(DeviceItemResponse::from).toList();
        return new DeviceListResponse(result.page(), result.size(), result.totalElements(), result.totalPages(), items);
    }
}
