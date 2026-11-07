# Handoff Report - Worker 1 (R1: Quản lý Danh sách Bệnh nhân, Khám bệnh & Điều hướng)

## 1. Observation
- **`src/main/resources/templates/doctor/diagnoses.html`**:
  * Đoạn mã tại dòng 1478-1480 trước đây:
    ```javascript
    if (window.location.hash) {
        history.replaceState(null, null, window.location.pathname + window.location.search);
    }
    ```
    chủ động xóa sạch URL hash ngay khi trang bắt đầu tải, dẫn đến việc mất tab đang chọn khi reload hoặc chuyển trang.
  * Thiếu modal thành công có nút bấm chuyển tiếp trực tiếp sang Bệnh án điện tử (EMR) sau khi bác sĩ ấn hoàn tất ca khám lâm sàng.
- **`src/main/resources/templates/fragments/sidebar.html`**:
  * Mục menu riêng lẻ "Bệnh án điện tử" (`/emr/records`) tồn tại độc lập tại dòng 362-367, gây trùng lặp điều hướng vì BAĐT đã được tích hợp trực tiếp vào Hồ sơ bệnh nhân và Ca khám bác sĩ.
- **`src/main/resources/templates/patient-records/list.html`**:
  * Cột Thanh toán và Nguồn khách hàng trước đây sử dụng các thẻ `th:elseif` và `th:else` không hợp lệ trong cú pháp Thymeleaf chuẩn, khiến nhiều badge hiển thị cùng lúc hoặc chồng chéo nhau.
  * Chưa đồng bộ giao diện với chuẩn hiện đại `#0284c7` của `daily-visits.html`: thiếu thanh `.dv-toolbar-card`, Quick Segment Tabs (`.dv-quick-segment`), bảng dữ liệu `.dv-amis-table` với cột Họ tên và Thao tác ghim (sticky).
  * Cột Thao tác chỉ có các icon đơn giản, thiếu Modal Thêm nhanh bệnh nhân mới, Modal Chỉnh sửa thông tin & nguồn, Modal xác nhận Soft-Delete và thanh phân trang cố định ở footer.
- **`src/main/java/com/cvdcms/patient/PatientSearchRepository.java` & `PatientRestController.java`**:
  * Chưa hỗ trợ lọc trực tiếp theo `customerSource`.
  * Chưa có endpoint cho soft delete (chuyển trạng thái `INACTIVE`), cập nhật nhanh nguồn, và tạo nhanh bệnh nhân.

## 2. Logic Chain
1. **Xử lý hash tab tại `diagnoses.html`**:
   - Loại bỏ câu lệnh xóa hash `history.replaceState(...)` khi load trang.
   - Thêm hàm `restoreTabFromHash()` hỗ trợ đầy đủ các alias và tab chính: `#tab-treatment-plan`, `#tab-prescriptions`, `#tab-performed-services`, `#tab-clinical-photos`, `#tab-examination`...
   - Cập nhật hàm `switchMainTab` để tự động đồng bộ hash lên URL (`#` + tabId) khi người dùng nhấp chọn tab.
   - Bổ sung modal `#examCompletedSuccessModal` và cập nhật logic `confirmDirectHandover` khi API `/api/doctor/visits/{id}/complete-clinical` trả về `success = true`, hiển thị thông báo thành công và nút bấm nổi bật "Mở Bệnh án điện tử (EMR) ngay" để gọi POST `/api/emr/records` và chuyển hướng đến `/emr/records/{id}`.
2. **Loại bỏ menu EMR riêng lẻ tại `sidebar.html`**:
   - Đã xóa khối link `<a th:href="@{/emr/records}">` và dọn dẹp logic accordion tại `sidebar.html`.
3. **Đồng bộ UI `list.html` theo chuẩn `daily-visits.html` & Sửa lỗi cú pháp Thymeleaf**:
   - Áp dụng hệ thống biến màu sắc `:root` với `--dv-primary: #0284c7`.
   - Cung cấp thanh công cụ `.dv-toolbar-card` với ô tìm kiếm lớn `.dv-search-box` có nút xóa nhanh, bộ lọc ngày sinh, dropdown cơ sở, nút bộ lọc nâng cao (`.dv-adv-filter-btn`) và panel lọc theo nguồn / trạng thái (`.dv-adv-filter-panel`).
   - Tích hợp thanh Quick Segment Tabs (`.dv-quick-segment`): Tất cả, Đang hoạt động, Facebook, Giới thiệu, Vãng lai, Lưu trữ / Tạm ngừng.
   - Thiết lập bảng dữ liệu `.dv-amis-table` bên trong `.dv-table-scroll-container` có ghim (sticky) cố định cột Họ tên (`.dv-col-pinned-left`) và cột Thao tác (`.dv-col-pinned-right`).
   - Sửa triệt để lỗi cú pháp Thymeleaf bằng cấu trúc `th:switch="${patient.paymentStatus}"` và `th:switch="${patient.customerSourceDisplay}"`, đảm bảo mỗi hàng chỉ render DUY NHẤT 1 badge Thanh toán và 1 badge Nguồn với màu nhận diện thương hiệu chuẩn.
   - Thêm bộ nút CRUD chuẩn: Xem chi tiết (`bi-eye`), Chỉnh sửa (`bi-pencil-square`), Lưu trữ / Xóa mềm (`bi-trash`).
   - Tích hợp 3 modal tương tác hiện đại: `#addPatientModal` (Thêm nhanh có nguồn tiếp nhận), `#editPatientModal` (Chỉnh sửa thông tin & nguồn), `#deletePatientModal` (Xác nhận lưu trữ soft-delete).
   - Bổ sung thanh phân trang cố định ở footer (`.dv-pagination-container`) với số bản ghi hiển thị và các nút First, Prev, các số trang thông minh, Next, Last.
4. **Bổ sung backend REST & Repository**:
   - `PatientSearchRepository`: Thêm query `searchWithSource`, `findById`, và `@Modifying updateStatus`.
   - `PatientRestController`: Hỗ trợ param `customerSource`, thêm endpoint `POST /patients/quick-create`, `PUT /patients/{id}/quick-update`, `DELETE /patients/{id}`, `POST /patients/{id}/soft-delete`, và `POST /patients/{id}/restore`.

## 3. Caveats
- Các thay đổi tuân thủ nghiêm ngặt phạm vi 5 file được giao độc quyền của Worker 1, không can thiệp vào các file của Worker 2, 3, 4.

## 4. Conclusion
Tất cả 6 yêu cầu chi tiết thuộc phân hệ R1 (Quản lý Danh sách Bệnh nhân, Khám bệnh & Điều hướng) đã được triển khai hoàn chỉnh, đúng nghiệp vụ, không hardcode hay facade dummy, giao diện đồng bộ 100% với chuẩn `daily-visits.html` và biên dịch Java không có lỗi ở các file thuộc phạm vi quản lý.

## 5. Verification Method
- **Kiểm tra biên dịch Java**:
  Chạy lệnh `.\mvnw.cmd test-compile`.
- **Kiểm tra cú pháp Thymeleaf**:
  Xác nhận không còn bất kỳ thẻ `th:else` hoặc `th:elseif` nào trong `src/main/resources/templates/patient-records/list.html` thông qua `Select-String -Path "src/main/resources/templates/patient-records/list.html" -Pattern "th:else|th:elseif"`.
- **Kiểm tra hash tab**:
  Truy cập URL `/doctor/examination/{visitId}/diagnoses#tab-treatment-plan`, kiểm tra tab Kế hoạch điều trị được kích hoạt tự động mà không bị xóa URL hash.
