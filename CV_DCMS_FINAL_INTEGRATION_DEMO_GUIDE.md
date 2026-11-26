# CV-DCMS Final Integration Demo Guide

## 1. Môi trường Demo
- Server: **http://localhost:8089/** (DB `cv_dcms_v118_verify`, `resource_engine_enabled = 0`)
- Tài khoản (mật khẩu chung `Demo@123`):
  - Bệnh nhân: `bn_demo1`, `bn_demo2`
  - Lễ tân: `letan_demo`
  - Bác sĩ: `bs01_m4` (BS Nguyen A), `bs02_m4` (BS Tran B), `bs03_m4` (BS Le C), `bs04_m4` (BS Pham D)
  - Admin: `admin_m4`

## 2. Kịch bản Demo Nhóm 1 — Smart Appointment Overlap Warning
1. Đăng nhập `bn_demo1` / `Demo@123` tại `http://localhost:8089/login`.
2. Mở `http://localhost:8089/portal/appointments/new`.
3. Chọn chi nhánh `Chi nhanh M4-1`, ngày `2026-11-27`, ca `Ca Sáng`, bác sĩ `BS Tran B`, giờ `09:00 (Có thể đông/trùng)`.
4. Quan sát 2 thẻ cảnh báo tách biệt (**Clinic Overlap** & **Doctor Overlap**), nhóm **Gợi ý tối ưu**, và 2 lựa chọn:
   - **A. Giữ giờ mong muốn** (tick checkbox và bấm *Xác nhận đặt lịch khám* -> lưu thành công đúng `09:00` + `BS Tran B`).
   - **B. Chọn giờ gợi ý** (bấm vào một chip gợi ý -> cập nhật giờ, tự kiểm tra lại `/overlap-check` và xóa cảnh báo).

## 3. Kịch bản Demo Nhóm 2 — Service Phase Countdown trên TV Display
1. Đăng xuất tài khoản bệnh nhân (hoặc mở trình duyệt ẩn danh) và truy cập `http://localhost:8089/reception/queue/tv/1`.
2. Quan sát các phiếu đang `Đang khám` (`03`, `04`, `06`):
   - Hiển thị rõ giai đoạn hiện tại (`ĐANG KHÁM`), đồng hồ đếm ngược `MM:SS`, thanh tiến trình và `Đã thực hiện / Tổng dự kiến`.
3. Thử **F5 / Reload** trình duyệt hoặc mở thêm tab TV thứ 2: cả 2 màn hình hiển thị cùng một tiến trình và đồng hồ không bị reset.
