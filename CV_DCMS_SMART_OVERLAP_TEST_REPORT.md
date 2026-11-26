# CV-DCMS Smart Appointment Overlap — Test Report

## 1. Automated Tests (H2 In-Memory Isolated)
- **`SmartAppointmentOverlapIntegrationTest`**: **8 / 8 PASS** (`BUILD SUCCESS`)
  - `testA1_noOverlap`: PASS — Không cảnh báo khi không trùng, đặt lịch thành công.
  - `testA2_onlyClinicOverlap`: PASS — Cảnh báo Clinic khi đông chi nhánh, không tạo Doctor Overlap giả khi chưa chọn bác sĩ, trả về `clinicSuggestions` đều là `NO_OVERLAP`.
  - `testA3_onlyDoctorOverlap`: PASS — Cảnh báo Doctor khi bác sĩ bận nhưng chi nhánh còn trong năng lực (`WITHIN_CAPACITY`), `doctorSuggestions` thuộc đúng bác sĩ đó.
  - `testA4_bothClinicAndDoctorOverlap`: PASS — Trả về cả 2 cảnh báo riêng và `combinedSuggestions` không overlap cả hai.
  - `testA5_keepOverlappingTimeAndDoctor`: PASS — Thiếu `confirmOverlap` bị chặn 409; có `confirmOverlap=true` lưu đúng giờ `10:00` và đúng `docA` kèm `overlapWarningAcknowledged=true`.
  - `testA6_selectSuggestedSlotAndRecheck`: PASS — Chọn giờ gợi ý, kiểm tra lại `/overlap-check` ra không overlap và đặt lịch thành công.
  - `testA7_differentServiceDurations`: PASS — Kiểm tra đủ 4 thời lượng `15`, `30`, `60`, `90` phút; mọi slot gợi ý đủ thời lượng và không vượt giờ đóng ca.
  - `testA8_leavesAndOutsideShiftStayHardBlocked`: PASS — Ngoài ca trực và ngày nghỉ phép `APPROVED` vẫn bị chặn cứng kể cả khi `confirmOverlap=true`.
- **Regression Suites:**
  - `AppointmentSlotServiceTest`: **5 / 5 PASS**
  - `PublicBookingGuestSecurityIntegrationTest`: **14 / 14 PASS**

## 2. Live Browser Verification (`http://localhost:8089`, DB `cv_dcms_v118_verify`)
- Đăng nhập `bn_demo1` / `Demo@123` tại `/login` -> mở `/portal/appointments/new` (HTTP 200, 0 lỗi console/JS, 0 request lỗi).
- Chọn chi nhánh `Chi nhanh M4-1`, ngày `2026-11-27`, ca `MORNING`, bác sĩ `BS Tran B`:
  - `#slotTime` tải đủ 15 mốc giờ (`08:00`–`11:30`), đánh dấu rõ `08:45 (Có thể đông/trùng)`, `09:00 (Có thể đông/trùng)`, `09:15 (Có thể đông/trùng)`.
  - Chọn `09:00` tự gọi `GET /portal/appointments/api/overlap-check?...` (HTTP 200, `clinicOverlap: true` `2/2`, `doctorOverlap: true` `1`).
  - `#overlapWarningPanel` hiển thị tách biệt thẻ **Cảnh báo mật độ chi nhánh (Clinic Overlap)**, thẻ **Cảnh báo lịch bác sĩ (Doctor Overlap)**, thẻ **Gợi ý tối ưu (Không overlap cả Chi nhánh & Bác sĩ)** (6 mốc gợi ý) và checkbox **"A. Giữ giờ mong muốn (09:00 – giữ nguyên bác sĩ & thời gian đã chọn)"**.

## 3. Giới hạn còn tồn tại
- Chế độ Resource Engine (`branches.resource_engine_enabled = 1`) hiện đang tắt theo quy tắc giữ nguyên Legacy workflow, nên soft-overlap chủ yếu áp dụng cho luồng Legacy.
