# CV-DCMS TV Service Phase Countdown — Test Report

## 1. Automated Tests (H2 In-Memory Isolated)
- **`TvServicePhaseCountdownIntegrationTest`**: **8 / 8 PASS** (`BUILD SUCCESS`)
  - `testB1_threePhaseProgression`: PASS — Dịch vụ `5 + 30 + 5` chuyển đúng `PREP` -> `EXAM` -> `CLEANING`.
  - `testB2_zeroPrepSkipped`: PASS — `prep = 0` bắt đầu ngay `EXAM`, `hasPrep = false`.
  - `testB3_zeroCleaningSkipped`: PASS — `cleaning = 0` sau khám chuyển thẳng `OVERTIME`, `hasCleaning = false`.
  - `testB4_B5_B10_reloadAndMultiTvConsistent`: PASS — Nhiều TV và reload dùng chung `actualStartTimeEpochMs` từ backend.
  - `testB6_lateDoctorStartUsesActualStartTime`: PASS — Check-in trước 45 phút nhưng `enterExaminationForDoctor` lúc hiện tại -> countdown tính từ lúc bắt đầu khám thực tế.
  - `testB7_overtimeDoesNotAutoCompleteOrReleaseChair`: PASS — Vượt dự kiến vẫn giữ `IN_SERVICE`, `IN_EXAM`, `IN_USE`, không tự đóng hồ sơ hay giải phóng ghế.
  - `testB8_examDoneVsChairTurnover`: PASS — `DONE` đưa ticket rời khỏi `Đang khám`, ghế vào `TURNOVER` (`cleaningChairs` trên TV), `completeChairTurnover` đưa ghế về `AVAILABLE`.
  - `testB9_twoPatientsTwoChairsIndependent`: PASS — 2 bệnh nhân trên 2 ghế có tiến trình và tổng thời lượng độc lập.
- **Regression Suites:**
  - `QueueResourceMatrixAndTvIntegrationTest`: **5 / 5 PASS**
  - `WaterfallQueueStrictOrderingIntegrationTest`: **8 / 8 PASS** (chạy ngay trước đó trên cùng code điều phối hàng đợi)

## 2. Live Browser Verification (`http://localhost:8089`, DB `cv_dcms_v118_verify`)
- Mở `http://localhost:8089/reception/queue/tv/1` (HTTP 200, `GET /reception/queue/tv/1/data` HTTP 200, 0 lỗi console/JS):
  - 3 phiếu `Đang khám` (`03`, `04`, `06`) hiển thị `.tv-phase-box` với nhãn `ĐANG KHÁM`, pill `Đang khám`, thanh tiến trình, và đồng hồ `MM:SS`.
  - Chờ ~3.2 giây: đồng hồ đếm ngược trực tiếp từ `23:08` (`Đã thực hiện: 06:52`) xuống `23:04` (`Đã thực hiện: 06:56`).
  - Reload trang (`page.reload()`): tiếp tục chính xác tại `23:04` (`Đã thực hiện: 06:56 / Tổng dự kiến: 30 phút`), không reset về `00:00` hay `30:00`.

## 3. Giới hạn còn tồn tại
- Lượt khám vãng lai chưa gắn `Appointment` mặc định dùng thời lượng khám `30` phút (`0 / 30 / 0`) nếu chưa chọn dịch vụ trước khi bắt đầu khám.
