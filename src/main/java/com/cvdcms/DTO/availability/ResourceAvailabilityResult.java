package com.cvdcms.DTO.availability;

import java.util.List;

/**
 * Kết quả kiểm tra khả dụng đa tài nguyên (Read-only, không giữ chỗ).
 */
public record ResourceAvailabilityResult(
        boolean available,
        String reasonCode,
        String message,
        ServiceTimeWindow timeWindow,
        List<AvailableResourceOption> options,
        List<String> diagnostics
) {
    public static ResourceAvailabilityResult unavailable(
            String reasonCode,
            String message,
            ServiceTimeWindow timeWindow,
            List<String> diagnostics) {
        return new ResourceAvailabilityResult(
                false,
                reasonCode,
                message,
                timeWindow,
                List.of(),
                diagnostics == null ? List.of() : List.copyOf(diagnostics));
    }

    public static ResourceAvailabilityResult available(
            ServiceTimeWindow timeWindow,
            List<AvailableResourceOption> options,
            List<String> diagnostics) {
        return new ResourceAvailabilityResult(
                true,
                "AVAILABLE",
                "Tìm thấy " + options.size() + " tổ hợp tài nguyên khả dụng.",
                timeWindow,
                List.copyOf(options),
                diagnostics == null ? List.of() : List.copyOf(diagnostics));
    }
}
