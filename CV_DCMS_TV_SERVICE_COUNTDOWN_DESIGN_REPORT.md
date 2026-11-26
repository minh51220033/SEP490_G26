# CV-DCMS TV Service Phase Countdown — Design Report

## 1. Các file đã kiểm tra
- `src/main/java/com/cvdcms/entity/Service.java`
- `src/main/java/com/cvdcms/entity/Visit.java`
- `src/main/java/com/cvdcms/entity/DentalChair.java`
- `src/main/java/com/cvdcms/entity/QueueTicket.java`
- `src/main/java/com/cvdcms/service/QueueWorkflowService.java`
- `src/main/java/com/cvdcms/service/ClinicQueueCoordinatorService.java`
- `src/main/resources/templates/reception/queue-tv.html`
- `src/main/resources/static/js/reception-queue-tv.js`

## 2. Thiết kế kiến trúc & Dữ liệu
1. **Tương thích thời lượng 3 giai đoạn trên `Service`:**
   - `Service` đã có sẵn `preparationDurationMinutes` (mặc định `0`), `procedureDurationMinutes` (mặc định `30`, tương ứng thời gian khám), `turnoverDurationMinutes` (mặc định `0`, tương ứng thời gian vệ sinh), và `estimatedDurationMinutes = prep + procedure + turnover` (đồng bộ qua `ServiceCatalogServiceImpl.applyThreePhaseDurations`).
   - `Appointment` cũng lưu snapshot `preparationDurationMinutes`, `procedureDurationMinutes`, `turnoverDurationMinutes`.
2. **Mốc thời gian bắt đầu thực tế:**
   - Dùng `Visit.actualStartTime` (`visits.actual_start_time`) được ghi khi bệnh nhân thực sự bước vào `IN_EXAM` / `IN_SERVICE` (`ClinicQueueCoordinatorService.enterExaminationForDoctor` hoặc `QueueWorkflowService.transition(Action.START)`). Tuyệt đối không tính từ lúc đặt lịch hay check-in.
3. **Phân biệt kết thúc khám và vệ sinh ghế (`DentalChair`):**
   - Bổ sung `turnover_started_at DATETIME NULL` và `turnover_duration_minutes INT NULL` trên `dental_chairs` (`V126`).
   - Khi hoàn tất khám (`Action.DONE`) trên dịch vụ có `turnoverDurationMinutes > 0` (hoặc `resourceEngineEnabled = true`), ticket bệnh nhân chuyển `DONE` (không còn hiển thị `Đang khám`), trong khi ghế chuyển sang `ChairStatus.TURNOVER` và hiển thị ở dải **Ghế đang vệ sinh sau khám** riêng trên TV cho đến khi gọi `completeChairTurnover`.
4. **Đồng bộ đồng hồ trên nhiều TV / Reload / Reconnect:**
   - Backend trả `serverNowEpochMs` và `ServicePhaseProgress` (`actualStartTimeEpochMs`, `prepMinutes`, `examMinutes`, `cleaningMinutes`, `currentPhase`, `phaseRemainingSeconds`, `elapsedSeconds`, `overtimeSeconds`, ...).
   - Frontend giữ `serverOffsetMs = serverNowEpochMs - Date.now()` và tính lại từng giây từ `actualStartTimeEpochMs`, nên reload hay mở nhiều màn hình TV luôn khớp đến từng giây.
