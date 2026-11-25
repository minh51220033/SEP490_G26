package com.cvdcms.util;

import com.cvdcms.entity.PatientProfile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Chính sách phân loại truy vấn tìm kiếm bệnh nhân tại màn hình tiếp đón &amp; đặt lịch.
 * Phân tách rạch ròi giữa duyệt/tìm theo tên trong phạm vi chi nhánh và tra cứu định danh
 * chính xác liên cơ sở (SĐT, CCCD/CMND, Mã bệnh nhân).
 */
public final class PatientSearchPolicy {

    private static final Pattern EXACT_PHONE_PATTERN = Pattern.compile("^0\\d{9,10}$");
    private static final Pattern EXACT_ID_CARD_PATTERN = Pattern.compile("^(\\d{9}|\\d{12})$");
    private static final Pattern EXACT_PATIENT_CODE_PATTERN = Pattern.compile("^(?i)BN(?=.*\\d)[-_A-Z0-9]{2,18}$");

    public static final String VERIFICATION_CONFLICT_MESSAGE =
            "Phát hiện nhiều hồ sơ trùng định danh hoặc dữ liệu không nhất quán. Vui lòng xác minh thủ công trước khi tiếp đón.";

    public enum ExactIdentifierType {
        NONE,
        PHONE,
        ID_CARD,
        PATIENT_CODE
    }

    private PatientSearchPolicy() {
    }

    public static String normalize(String rawQuery) {
        return rawQuery == null ? "" : rawQuery.trim();
    }

    public static ExactIdentifierType classifyExactIdentifier(String rawQuery) {
        String normalized = normalize(rawQuery);
        if (normalized.isEmpty()) {
            return ExactIdentifierType.NONE;
        }
        if (EXACT_PHONE_PATTERN.matcher(normalized).matches()) {
            return ExactIdentifierType.PHONE;
        }
        if (EXACT_ID_CARD_PATTERN.matcher(normalized).matches()) {
            return ExactIdentifierType.ID_CARD;
        }
        if (EXACT_PATIENT_CODE_PATTERN.matcher(normalized).matches()) {
            return ExactIdentifierType.PATIENT_CODE;
        }
        return ExactIdentifierType.NONE;
    }

    /**
     * Xác thực kết quả tra cứu định danh chính xác:
     * không bao giờ tự chọn phần tử đầu tiên khi có từ 2 hồ sơ trở lên hoặc khi dữ liệu định danh không nhất quán.
     */
    public static Optional<PatientProfile> resolveSingleConsistentExactMatch(
            ExactIdentifierType type,
            String rawQuery,
            List<PatientProfile> candidates) {
        if (type == ExactIdentifierType.NONE || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, VERIFICATION_CONFLICT_MESSAGE);
        }
        String normalized = normalize(rawQuery);
        PatientProfile profile = candidates.get(0);
        if (!isConsistentExactProfile(profile, type, normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, VERIFICATION_CONFLICT_MESSAGE);
        }
        return Optional.of(profile);
    }

    private static boolean isConsistentExactProfile(PatientProfile p, ExactIdentifierType type, String normalizedQuery) {
        if (p == null || p.getProfileId() == null) {
            return false;
        }
        if (!"ACTIVE".equalsIgnoreCase(p.getStatus())) {
            return false;
        }
        if (isBlank(p.getPatientCode()) || isBlank(p.getFullName()) || isBlank(p.getPhoneNumber())) {
            return false;
        }
        if (p.getPreferredBranch() == null || p.getPreferredBranch().getBranchId() == null) {
            return false;
        }
        return switch (type) {
            case PHONE -> normalizedQuery.equals(p.getPhoneNumber().trim());
            case ID_CARD -> p.getIdCardNumber() != null && normalizedQuery.equals(p.getIdCardNumber().trim());
            case PATIENT_CODE -> normalizedQuery.equalsIgnoreCase(p.getPatientCode().trim());
            case NONE -> false;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
