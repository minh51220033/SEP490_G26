## 2026-11-07T07:04:13Z
Bạn là Worker 1 (teamwork_worker). Nhiệm vụ của bạn là hoàn thành toàn diện R1: Quản lý Danh sách Bệnh nhân, Khám bệnh & Điều hướng.

Working directory của bạn: c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\worker_r1
Đọc các tài liệu tham chiếu:
- `c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\ORIGINAL_REQUEST.md`
- `c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\PROJECT.md`
- Giao diện mẫu chuẩn: `src/main/resources/templates/doctor/daily-visits.html`

PHẠM VI FILE BẠN SỞ HỮU ĐỘC QUYỀN (CHỈ SỬA CÁC FILE NÀY, TUYỆT ĐỐI KHÔNG SỬA FILE KHÁC):
- `src/main/resources/templates/patient-records/list.html`
- `src/main/resources/templates/doctor/diagnoses.html`
- `src/main/resources/templates/fragments/sidebar.html`
- `src/main/java/com/cvdcms/patient/PatientSearchRepository.java`
- `src/main/java/com/cvdcms/patient/PatientRestController.java`

YÊU CẦU CHI TIẾT (R1):
1. Xử lý hash tab tại `src/main/resources/templates/doctor/diagnoses.html`:
   - Kiểm tra và sửa đoạn mã JavaScript xóa hash khi tải trang (giữ nguyên location.hash hoặc restore tab từ hash).
   - Đọc và kích hoạt đúng tab được chọn (`#tab-treatment-plan`, `#tab-prescriptions`, `#tab-performed-services`, `#tab-clinical-photos`). Khi chuyển tab, cập nhật hash lên URL.
   - Khi bác sĩ hoàn tất ca khám, hiển thị thông báo thành công kèm nút bấm mở nhanh Bệnh án điện tử của ca khám (liên kết tới `/emr/records/...` hoặc endpoint tạo/mở EMR).
2. Đồng bộ giao diện `src/main/resources/templates/patient-records/list.html` theo chuẩn `daily-visits.html`:
   - Sử dụng tông màu chủ đạo `#0284c7`, phong cách hiện đại.
   - Áp dụng thanh công cụ chuẩn `.dv-toolbar-card` với ô tìm kiếm (họ tên, SĐT, mã BN), bộ lọc ngày tạo, nguồn tiếp nhận, trạng thái.
   - Thêm Quick Segment Tabs (`.dv-quick-segment`): Tất cả, Khám hôm nay, Có lịch hẹn, v.v.
   - Dùng bảng dữ liệu `.dv-amis-table` có ghim (sticky) cột Họ tên & Thao tác.
3. Sửa lỗi cú pháp Thymeleaf ở cột Thanh toán và Nguồn khách hàng tại `list.html`:
   - Hiện tại có lỗi cú pháp khiến hiển thị lặp hoặc lỗi badge. Hãy sửa để hiển thị đúng duy nhất 1 Badge Nguồn (màu nhận diện) và 1 Badge Thanh toán cho mỗi dòng bản ghi.
4. Chuẩn hóa cột Thao tác tại `list.html`:
   - Bộ nút CRUD chuẩn gồm: Xem chi tiết (`bi-eye`), Chỉnh sửa (`bi-pencil-square` mở modal cập nhật thông tin & nguồn), Xóa/Lưu trữ (`bi-trash` có modal xác nhận Soft-Delete).
   - Thêm nút & Modal "Thêm bệnh nhân mới" với các trường chuẩn (Họ tên, SĐT, Ngày sinh, Giới tính, Địa chỉ, Nguồn khách hàng: Facebook, Giới thiệu, Vãng lai, Website...).
   - Bổ sung thanh phân trang cố định ở footer (First, Prev, Page numbers, Next, Last, hiển thị tổng số bản ghi và kích thước trang).
5. Xóa mục menu "Bệnh án điện tử" (`/emr/records`) riêng lẻ trên thanh sidebar trái tại `src/main/resources/templates/fragments/sidebar.html` (vì BAĐT đã được tích hợp trực tiếp vào Hồ sơ bệnh nhân và Ca khám).
6. Kiểm tra và bổ sung các endpoint REST / Repository nếu cần trong `PatientRestController.java` và `PatientSearchRepository.java` để hỗ trợ Soft Delete, Tìm kiếm theo nguồn, Thêm nhanh bệnh nhân.
