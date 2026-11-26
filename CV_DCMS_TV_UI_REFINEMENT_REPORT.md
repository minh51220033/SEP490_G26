# CV-DCMS TV Display — UI Refinement Report

## 1. File đã sửa
- `src/main/resources/templates/reception/queue-tv.html`
- `src/main/resources/static/js/reception-queue-tv.js`

## 2. Logic trước / sau
- **Trước:** Cột `Họ và tên` của dòng `Đang khám` chèn thêm `.tv-phase-box` chứa tiêu đề trạng thái, bộ pill giai đoạn, thanh tiến trình và dòng `"Đã thực hiện / Tổng dự kiến"`, gây lặp với badge `Đang khám` ở cột `Trạng thái` và làm thay đổi cấu trúc dòng.
- **Sau:**
  - Giữ nguyên 100% layout bảng 6 cột cũ (`Số thứ tự / Phiếu`, `Họ và tên`, `Bác sĩ phụ trách`, `Trạng thái`, `Phòng khám`, `Ghế nha`), header, đồng hồ, nút chức năng, màu nền dòng.
  - Cột `Họ và tên` chỉ hiển thị tên bệnh nhân.
  - Cột `Trạng thái` của dòng `IN_SERVICE` hiển thị **1 trạng thái duy nhất** (`ĐANG CHUẨN BỊ` / `ĐANG KHÁM` / `ĐANG VỆ SINH` / `ĐÃ VƯỢT THỜI GIAN DỰ KIẾN`) và **đồng hồ đếm ngược ngay bên dưới** (`MM:SS` hoặc `+MM:SS`) kèm thanh tiến trình mảnh.
  - Bỏ qua giai đoạn 0 phút; đếm ngược độc lập theo `actualStartTimeEpochMs` từng bệnh nhân; reload không reset; hết giờ không tự hoàn tất hồ sơ hoặc giải phóng ghế.

## 3. Test & Browser Verification
- `TvServicePhaseCountdownIntegrationTest`: **8 / 8 PASS**
- `QueueResourceMatrixAndTvIntegrationTest`: **5 / 5 PASS**
- Browser (`http://localhost:8089/reception/queue/tv/1`, HTTP 200, 0 console errors):
  - Cột `Họ và tên` chỉ chứa tên bệnh nhân (`.tv-phase-box` count = 0).
  - Cột `Trạng thái` dòng `03`, `04`, `06` hiển thị `ĐANG KHÁM` + đồng hồ `03:46` → chờ 3s giảm còn `03:43` → sau `page.reload()` tiếp tục tại `03:42` (không reset).
  - Ảnh chụp trước tinh giản: `step_38.png` (`6e3afa98-ccc6-49be-8457-e24608395aab`); ảnh chụp sau tinh giản: `step_4_shot_1.png` (`e37b9d29-9c04-426e-aeb3-cda3f9d65570`).

## 4. Điểm chưa kiểm chứng trực tiếp trên trình duyệt
- Trạng thái `ĐANG CHUẨN BỊ` và `ĐANG VỆ SINH` trên giao diện TV live chưa có dữ liệu mẫu `prep > 0` / `cleaning > 0` trên DB demo tại thời điểm chụp, nhưng đã được kiểm chứng đầy đủ qua integration test (`testB1`, `testB2`, `testB3`, `testB8`).
