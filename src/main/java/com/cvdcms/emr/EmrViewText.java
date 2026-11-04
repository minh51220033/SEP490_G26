package com.cvdcms.emr;

import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component("emrViewText")
public class EmrViewText {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("ACTIVE", "Đang hoạt động"), Map.entry("INACTIVE", "Ngừng hoạt động"),
            Map.entry("WAITING", "Chờ khám"), Map.entry("IN_EXAM", "Đang khám"),
            Map.entry("WAITING_PARACLINICAL", "Chờ cận lâm sàng"),
            Map.entry("WAITING_RESULT", "Chờ kết quả"),
            Map.entry("IN_TREATMENT", "Đang điều trị"),
            Map.entry("WAITING_PAYMENT", "Chờ thanh toán"),
            Map.entry("PENDING", "Chưa thực hiện"),
            Map.entry("IN_PROGRESS", "Đang thực hiện"),
            Map.entry("PLANNED", "Đã lên kế hoạch"),
            Map.entry("PROPOSED", "Đã đề xuất"),
            Map.entry("ACCEPTED", "Đã chấp nhận"),
            Map.entry("SKIPPED", "Bỏ qua"),
            Map.entry("SENT", "Đã gửi labo"),
            Map.entry("PROCESSING", "Đang xử lý"),
            Map.entry("DELIVERED", "Đã bàn giao"),
            Map.entry("FITTED_SUCCESS", "Đã lắp hoàn tất"),
            Map.entry("RETURNED", "Đã trả lại"),
            Map.entry("COMPLETED", "Đã hoàn thành"), Map.entry("CANCELLED", "Đã hủy"),
            Map.entry("DRAFT", "Bản nháp"), Map.entry("READY_TO_LOCK", "Sẵn sàng khóa"),
            Map.entry("LOCKED", "Đã khóa"), Map.entry("SIGNED", "Đã ký thử"),
            Map.entry("SUPERSEDED", "Đã thay thế"), Map.entry("AMENDMENT_DRAFT", "Bản sửa đổi"),
            Map.entry("MAIN", "Chính"), Map.entry("SECONDARY", "Phụ"),
            Map.entry("DOCTOR", "Bác sĩ"), Map.entry("PATIENT", "Người bệnh"),
            Map.entry("HEALTHY", "Khỏe mạnh"), Map.entry("NORMAL", "Bình thường"),
            Map.entry("MEDIUM", "Trung bình"));

    public String date(Object value) {
        if (value == null || value.toString().isBlank()) return "—";
        String raw = value.toString();
        try { return LocalDate.parse(raw.substring(0, 10)).format(DATE); }
        catch (RuntimeException ex) { return raw; }
    }

    public String label(Object value) {
        if (value == null) return "—";
        String raw = value.toString();
        return LABELS.getOrDefault(raw, raw.replace('_', ' '));
    }

    public String effective(Map<String, String> fields, String key, Object source) {
        String manual = fields == null ? null : fields.get(key);
        return manual != null && !manual.isBlank() ? manual : source == null ? "" : source.toString();
    }
}
