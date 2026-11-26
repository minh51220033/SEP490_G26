# CV-DCMS TV Service Phase Countdown — Implementation Report

## 1. Các file đã thay đổi
- `src/main/resources/db/migration/V126__smart_overlap_and_chair_turnover.sql`
- `src/main/java/com/cvdcms/entity/DentalChair.java`
- `src/main/java/com/cvdcms/service/QueueWorkflowService.java`
- `src/main/java/com/cvdcms/controller/QueueWorkflowController.java`
- `src/main/resources/templates/reception/queue-tv.html`
- `src/main/resources/static/js/reception-queue-tv.js`

## 2. Logic mới & API/DTO thay đổi
- **`DentalChair` (`V126`):** Thêm `turnoverStartedAt` (`turnover_started_at`) và `turnoverDurationMinutes` (`turnover_duration_minutes`).
- **`QueueWorkflowService`:**
  - Thêm record `ServicePhaseProgress` gắn vào `PublicQueueRow` khi ticket ở trạng thái `IN_SERVICE` (`computePhaseProgress(ticket, linkedAppt)`).
  - Bỏ qua giai đoạn có thời lượng `0` (`hasPrep = prepMinutes > 0`, `hasCleaning = cleaningMinutes > 0`).
  - Khi hết tổng thời lượng dự kiến (`elapsed >= totalDurationSeconds`), chuyển `currentPhase = "OVERTIME"`, `currentPhaseLabel = "Đã vượt thời gian dự kiến"`, `overtime = true`, không tự chuyển `Visit` sang `COMPLETED` và không tự giải phóng ghế/bác sĩ.
  - Thêm record `ChairTurnoverItem` và danh sách `cleaningChairs` trên `PublicBoard` để hiển thị trạng thái vệ sinh ghế sau khi ca khám đã `DONE`.
  - Cập nhật `transition(..., Action.DONE)` ghi `turnoverStartedAt` / `turnoverDurationMinutes` khi chuyển ghế sang `ChairStatus.TURNOVER`, và `completeChairTurnover` xóa mốc này khi trả ghế về `AVAILABLE`.
- **`QueueWorkflowController`:**
  - Bổ sung `POST /reception/queue/chairs/{chairId}/turnover-complete` để xác nhận hoàn tất vệ sinh ghế.
- **TV Display (`queue-tv.html` & `reception-queue-tv.js`):**
  - Hiển thị `.tv-phase-box` trực tiếp trong dòng `Đang khám`: nhãn giai đoạn chữ lớn, đồng hồ `MM:SS` (hoặc `+MM:SS` khi vượt dự kiến), dải pill các giai đoạn (tự ẩn giai đoạn 0 phút), thanh tiến trình tổng thể và thông tin `Đã thực hiện / Tổng dự kiến`.
  - Hiển thị `#tvCleaningSection` tách biệt bên dưới bảng khi có ghế ở trạng thái `TURNOVER`.
  - Timer JS chạy mỗi 1 giây theo `Date.now() + serverOffsetMs` so với `actualStartTimeEpochMs`.
