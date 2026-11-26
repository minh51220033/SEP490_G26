# CV-DCMS Booking Suggestions — UI Refinement Report

## 1. File đã sửa
- `src/main/java/com/cvdcms/service/AppointmentOverlapService.java`
- `src/main/resources/static/js/portal-appointments.js`
- `src/main/resources/templates/portal/home.html`
- `src/test/java/com/cvdcms/service/SmartAppointmentOverlapIntegrationTest.java`

## 2. Logic trước / sau
- **Trước:**
  - Chip gợi ý hiển thị dạng khoảng `08:30 - 09:00`.
  - Hiển thị tối đa 6 chip ngay lập tức và có thể lặp cùng một giờ ở cả 3 nhóm (`combinedSuggestions`, `clinicSuggestions`, `doctorSuggestions`).
- **Sau:**
  - Giao diện chỉ hiển thị **giờ bắt đầu** (`08:30`, `09:30`, `08:15`).
  - Mặc định hiển thị tối đa **3 gợi ý** gần nhất; nếu còn thêm giờ hợp lệ, hiển thị nút **`Xem thêm giờ khác (N)`** để người dùng mở rộng.
  - Backend ưu tiên nhóm không overlap cả Clinic và Doctor (`combinedSuggestions`), khử trùng lặp `startTime` giữa các nhóm (`usedStartTimes`), đồng thời **vẫn tính đầy đủ `[startTime, startTime + durationMinutes)`** để kiểm tra Clinic Overlap, Doctor Overlap, ca làm việc và ngày nghỉ trước khi lưu.

## 3. Test & Browser Verification
- `SmartAppointmentOverlapIntegrationTest`: **8 / 8 PASS** (đã bổ sung assertion không trùng `startTime` giữa `combinedSuggestions`, `clinicSuggestions`, `doctorSuggestions`).
- Browser (`http://localhost:8089/portal/appointments/new`, HTTP 200, 0 console errors):
  - Chọn `2026-11-27`, `MORNING`, `BS Tran B`, giờ `09:00`: hiển thị riêng 2 thẻ **Clinic Overlap** (`2/2`) và **Doctor Overlap** (`1`).
  - Nhóm gợi ý mặc định hiển thị đúng **3 giờ bắt đầu** (`08:30`, `09:30`, `08:15`) và nút **`Xem thêm giờ khác (9)`**.
  - Bấm `Xem thêm giờ khác (9)` mở rộng thành **12 giờ bắt đầu duy nhất**, 0 trùng lặp.
  - Ảnh chụp trước tinh giản: `step_20_shot_1.png` (`6e3afa98-ccc6-49be-8457-e24608395aab`); ảnh chụp sau tinh giản: `step_14_shot_1.png` (`e37b9d29-9c04-426e-aeb3-cda3f9d65570`).

## 4. Điểm chưa kiểm chứng trực tiếp trên trình duyệt
- Thao tác đặt lịch nhiều dịch vụ cùng lúc (`/portal/appointments/api/multi-service`) hiện không nằm trong `form.html` đơn dịch vụ; tổng thời lượng dịch vụ đơn (`15/30/60/90` phút) đã được kiểm chứng qua `testA7`.
