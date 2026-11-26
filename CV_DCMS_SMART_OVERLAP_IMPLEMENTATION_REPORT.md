# CV-DCMS Smart Appointment Overlap — Implementation Report

## 1. Các file đã thay đổi / thêm mới
- `src/main/resources/db/migration/V126__smart_overlap_and_chair_turnover.sql` (mới)
- `src/main/java/com/cvdcms/entity/Appointment.java`
- `src/main/java/com/cvdcms/service/AppointmentOverlapService.java` (mới)
- `src/main/java/com/cvdcms/service/AppointmentPolicyService.java` & `service/impl/AppointmentPolicyServiceImpl.java`
- `src/main/java/com/cvdcms/service/AppointmentSlotService.java`
- `src/main/java/com/cvdcms/service/PortalAppointmentService.java`
- `src/main/java/com/cvdcms/controller/PortalAppointmentRestController.java`
- `src/main/java/com/cvdcms/controller/PublicBookingRestController.java`
- `src/main/resources/templates/portal/appointments/form.html`
- `src/main/resources/static/js/portal-appointments.js`
- `src/main/resources/templates/portal/home.html`

## 2. Logic mới & API/DTO thay đổi
- **Database (`V126`):** Thêm `overlap_warning_acknowledged TINYINT(1) NOT NULL DEFAULT 0` và `overlap_warning_note VARCHAR(500) NULL` vào bảng `appointments` (đã áp dụng trên `cv_dcms_v118_verify`).
- **`AppointmentOverlapService.checkOverlap(...)`:**
  - Luôn giữ nguyên kiểm tra cứng các điều kiện vận hành bắt buộc (chi nhánh hoạt động, ngày hợp lệ, giờ nằm trong ca mở cửa, không ở quá khứ, bác sĩ có ca `SCHEDULED` và không có `LeaveRequest` `APPROVED`).
  - Đánh giá độc lập **Clinic Overlap** (`NO_OVERLAP` / `WITHIN_CAPACITY` / `OVERLAP_WARNING`) và **Doctor Overlap** (`NO_OVERLAP` / `HAS_OVERLAP` / `NOT_APPLICABLE` khi chưa chọn bác sĩ).
  - Sinh gợi ý giờ thay thế theo bước 15 phút (`15/30/60/90` phút đủ toàn bộ thời lượng dịch vụ), sắp xếp theo độ gần với giờ mong muốn, tách thành `clinicSuggestions`, `doctorSuggestions`, `combinedSuggestions`.
- **Endpoints mới / mở rộng:**
  - `GET /portal/appointments/api/overlap-check` và `GET /api/public/booking/overlap-check`
  - `GET /portal/appointments/api/slots?includeOverlap=true` và `GET /api/public/booking/slots?includeOverlap=true`
  - `BookingRequest` / `RescheduleRequest` / `QuickBookingForm`: bổ sung `confirmOverlap` (giữ nguyên constructor cũ cho tương thích ngược). Khi `confirmOverlap = true`, backend lưu đúng giờ và bác sĩ bệnh nhân đã chọn, không tự đổi giờ/bác sĩ/chi nhánh, đồng thời lưu `overlapWarningAcknowledged = true`.
- **Frontend (`form.html`, `portal-appointments.js`, `portal/home.html`):**
  - Hiển thị 2 thẻ cảnh báo tách biệt (Clinic Overlap màu hổ phách, Doctor Overlap màu xanh indigo), danh sách nút gợi ý giờ (bấm vào tự cập nhật `#slotTime` và gọi lại `/overlap-check`), và lựa chọn A (**Giữ giờ mong muốn**) hoặc B (**Chọn giờ gợi ý**).
