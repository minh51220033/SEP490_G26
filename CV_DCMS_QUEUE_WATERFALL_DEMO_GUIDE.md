# CV-DCMS Queue Waterfall — Demo Guide

## 1. Môi trường Demo
- URL hệ thống: `http://localhost:8089/`
- TV gọi số chi nhánh 1: `http://localhost:8089/reception/queue/tv/1`
- Check-in lễ tân: `http://localhost:8089/reception/check-in?branchId=1`
- Điều phối ghế nha: `http://localhost:8089/reception/dispatch?branchId=1`
- Tài khoản lễ tân: `letan_demo` / `Demo@123`

## 2. Trạng thái dữ liệu hiện tại trên TV (`/reception/queue/tv/1`)
- **Đang gọi (`CALLED`):**
  - `P-01` — `Tran Thi Demo 2` → `BS Pham D` (`Đã đặt bác sĩ`) — `Phong X-Quang 104` / `May / Ghe X-Quang 104`
  - `04` — `Benh nhan 6` → `BS Nguyen A` (`Đã được phân bổ`) — `Phòng khám 101` / `Ghe 101-02`
  - `06` — `Benh nhan 8` → `BS Le C` (`Đã được phân bổ`) — `Phòng khám 101` / `Ghe 101-01`
- **Đang khám (`IN_SERVICE`):**
  - `03` — `Benh nhan 5` → `BS Tran B` (`Đã được phân bổ`) — `Phòng khám 102` / `Ghe 102-01`
- **Đang chờ (`WAITING` — Waterfall chung):**
  - `05` — `Benh nhan 7` → `Chưa phân công` (Bác sĩ, Phòng, Ghế)
  - `07` — `Dinh Xuan Hieu` → `Chưa phân công` (Bác sĩ, Phòng, Ghế)

## 3. Kịch bản Demo nhanh cho giảng viên / người chấm
1. **Chứng minh không gán sớm bác sĩ (`WAITING` chung):**
   - Mở `http://localhost:8089/reception/queue/tv/1`: phiếu `05` (`Benh nhan 7`) và `07` (`Dinh Xuan Hieu`) đều hiển thị `Chưa phân công` ở cả 3 cột Bác sĩ, Phòng khám, Ghế nha.
2. **Chứng minh Waterfall Queue (không nhảy hàng đợi):**
   - Cho bất kỳ bác sĩ khám tổng quát nào (ví dụ `BS Tran B` đang khám phiếu `03`, hoặc `BS Nguyen A` / `BS Le C` sau khi vào khám và hoàn tất khám + turnover ghế) rảnh trước.
   - Hệ thống luôn xét phiếu `05` (`Benh nhan 7`) trước phiếu `07` (`Dinh Xuan Hieu`), phân bổ ghế/phòng trước rồi mới gán bác sĩ vừa rảnh và chuyển phiếu `05` sang `Đang gọi`.
3. **Chứng minh ngoại lệ đặt riêng bác sĩ (`is_doctor_requested = 1`):**
   - Phiếu `P-01` (`Tran Thi Demo 2`) đặt riêng `BS Pham D` (phụ trách phòng X-Quang 104) hiển thị nhãn `Đã đặt bác sĩ` và chỉ điều phối đúng `BS Pham D`, không tự ý chuyển sang bác sĩ khác dù bác sĩ khác rảnh trước.
