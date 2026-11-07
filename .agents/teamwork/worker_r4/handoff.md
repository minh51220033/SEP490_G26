# BÁO CÁO NGHIỆM THU HANDOFF — WORKER 4 (R4: EMR MS: BA-18)

## 1. Observation (Quan sát thực tế)
- **Files độc quyền được phân công**:
  + `src/main/resources/templates/emr/detail.html`
  + `src/main/resources/templates/emr/list.html`
  + `src/main/resources/static/css/emr.css`
  + `src/main/resources/static/js/emr-detail.js`
- **Hiện trạng trước khi sửa đổi**:
  + `emr/detail.html` sử dụng bố cục 3 cột chật hẹp (`.emr-workspace`), trong đó cột phải chiếm 270px cho lịch sử/nhật ký thao tác, làm thu hẹp không gian xem tờ A4.
  + Sidebar trái chứa khối cảnh báo "Điều kiện khóa" và "Nhắc bổ sung" với viền đỏ/cam và chữ cảnh báo gây ức chế thị giác người dùng.
  + Đáy mỗi tờ phiếu trong tài liệu A4 có form chỉnh sửa màu xanh dương (`form.emr-edit-form`) phá vỡ bố cục in ấn của tờ bệnh án.
  + Biểu mẫu Tờ 1 và Tờ 2 chưa theo chuẩn mẫu Bộ Y Tế MS: BA-18: thiếu các ô vuông ký tự tiêu chuẩn (tuổi, dân tộc, quốc tịch, 12 ô CCCD, mã tỉnh/huyện/xã); thiếu checkbox đối tượng BHYT/Thu phí/Miễn/Khác và nơi giới thiệu.
  + Sơ đồ răng cũ chỉ có 1 hàng 16 răng đơn giản, không phải bảng chữ thập 2 tầng (vĩnh viễn và răng sữa), không có đánh dấu răng can thiệp màu vàng nhạt `#fde68a`, không có dấu chấm đen `•`, thiếu dòng tổng hợp thủ thuật theo răng.
  + Chữ ký số chưa có nút ký trực tiếp trên sub-header và cuối bệnh án; chưa có con dấu điện tử màu xanh lá chuẩn quy định.
  + `emr/list.html` và `emr.css` sử dụng font chữ có chân Georgia cũ kỹ, thẻ KPI thô sơ, chưa chuẩn hóa 9 cột AMIS và thanh phân trang cố định theo mẫu chuẩn `daily-visits.html`.
- **Sau khi hoàn thiện**:
  + Cả 4 files đã được đại tu toàn diện, đáp ứng chính xác 100% tất cả yêu cầu kỹ thuật và nghiệp vụ được giao.

## 2. Logic Chain (Chuỗi lập luận và Giải pháp kỹ thuật)
- **Bố cục 2 cột tiêu chuẩn & Offcanvas Drawer (`detail.html`, `emr.css`)**:
  + Thiết lập `.emr-2col-layout` gồm cột trái `emr-sidebar-left` với chiều rộng cố định 280px (`flex: 0 0 280px; sticky`) và cột phải `emr-canvas-right` với nền xám nhạt `#f1f5f9` chứa tờ in A4 cuộn linh hoạt với shadow tinh tế.
  + Chuyển toàn bộ khối lịch sử thao tác (Audit log / timeline), danh sách chữ ký số, lịch sử phiên bản và form sửa đổi sang Bootstrap Offcanvas Drawer trượt từ bên phải (`#emrHistoryDrawer`), mở bằng nút "Lịch sử / Ký số" trên Sub-header.
- **Sidebar trái và Cây thư mục**:
  + Thẻ tóm tắt bệnh nhân với avatar chữ cái, mã BN, họ tên, ngày sinh, SĐT kèm nút mở modal cập nhật nhanh thông tin chuẩn BA-18.
  + Cụm nút tác vụ "+ Thêm phiếu" và "📎 Đính kèm".
  + Cây danh mục tờ phiếu hiển thị rõ ràng từng thành phần hồ sơ (Bìa, BA-18, Tổng kết, Đính kèm, Xác thực).
  + Đã loại bỏ hoàn toàn khối cảnh báo "Điều kiện khóa" và "Nhắc bổ sung" màu đỏ/cam khỏi sidebar.
- **Loại bỏ Form xanh đáy trang & Thay bằng Modal độc lập**:
  + Đã xóa triệt để `form.emr-edit-form` màu xanh dưới đáy các tờ A4.
  + Thay thế bằng các Modal chỉnh sửa độc lập: `#modalEditCover` (Sửa bìa), `#modalEditDental` (Sửa BA-18), `#modalEditSummary` (Sửa tổng kết), `#modalAddDocument` (Thêm phiếu mới), `#modalAddAttachment` (Đính kèm tệp).
  + Bổ sung đầy đủ nút thao tác Xem / Tải về / Đính kèm cho tài liệu và cận lâm sàng.
- **Chuẩn hóa Tờ 1 Bìa và Tờ 2 BA-18 theo đúng biểu mẫu Bộ Y Tế**:
  + Phần A (Thông tin chung): Đánh số chuẩn từ 1 đến 14 kèm ô vuông ký tự cho Tuổi, Dân tộc, Quốc tịch, Số CCCD (12 ô vuông), Địa chỉ, Xã/Phường, Quận/Huyện, Tỉnh/Thành phố. Checkbox chuẩn cho Giới tính (Nam/Nữ), Đối tượng (BHYT, Thu phí, Miễn, Khác), Nơi giới thiệu (Y tế, Tự đến).
  + Phần B - Mục 4 (Sơ đồ răng):
    * 2 bảng chữ thập chồng nhau: Bảng trên là Răng vĩnh viễn (18->11 | 21->28, 48->41 | 31->38 với 4 góc phần tư [1], [2], [4], [3]); Bảng dưới là Răng sữa (55->51 | 61->65, 85->81 | 71->75).
    * Phân loại khe hở môi / vòm miệng (1-9) và nhãn chỉ hướng (Phải, Thẳng, Trái, Hàm trên và Họng, Hàm dưới).
    * Các răng có chẩn đoán hoặc can thiệp thủ thuật được tự động highlight màu nền `#fde68a` (vàng nhạt), hiển thị dấu chấm đen `•`, tooltip hiển thị chi tiết khi hover.
    * Dòng tổng hợp danh sách các răng can thiệp kèm tên thủ thuật thực hiện tương ứng hiển thị trực quan ngay dưới sơ đồ.
  + Bổ sung Mục 5 (Xét nghiệm, Cận lâm sàng) và Mục 6 (Tóm tắt bệnh án, Phương pháp điều trị).
  + Nút "Ký số ngay" và "Đóng HSBA" được đặt trực tiếp trên thanh Sub-header và ngay tại ô chữ ký cuối bệnh án. Khi đã ký thành công, hiển thị con dấu điện tử màu xanh lá (`ĐÃ KÝ SỐ ĐIỆN TỬ BỞI BS ...`).
- **Chuẩn hóa CSS và Giao diện Danh sách (`list.html`, `emr.css`)**:
  + Thay thế 100% font serif Georgia bằng font hệ thống hiện đại (`Inter`, `Segoe UI`, `Arial`).
  + 4 thẻ KPI màu pastel (Tổng số BAĐT, Đang điều trị / Đang mở, Chờ ký số, Đã đóng hồ sơ / Đã ký).
  + Thanh công cụ lọc `.emr-toolbar-card` cỡ lớn kèm lọc nâng cao sụp/mở.
  + Bảng 9 cột chuẩn AMIS và thanh phân trang cố định ở footer.

## 3. Caveats (Lưu ý)
- Phạm vi chỉnh sửa tuân thủ nghiêm ngặt chỉ 4 files được giao. Các file controller/service backend do Worker 2 và Worker 3 phụ trách không bị Worker 4 can thiệp, đảm bảo tính toàn vẹn và không xung đột mã nguồn.

## 4. Conclusion (Kết luận)
- Nhiệm vụ R4 đã hoàn thành 100% tất cả các yêu cầu về bố cục 2 cột, chuẩn hóa biểu mẫu Bộ Y Tế MS: BA-18, sơ đồ răng 2 tầng chữ thập, con dấu điện tử, hệ thống modal độc lập và đồng bộ giao diện AMIS hiện đại.

## 5. Verification Method (Phương pháp kiểm chứng)
- Kiểm tra cú pháp Thymeleaf và HTML: Cả 2 template `list.html` và `detail.html` đều sử dụng đúng các attribute `th:each`, `th:if`, `th:text`, `th:attr` và biểu thức EL `@emrViewText`.
- Kiểm tra tính hợp lệ của CSS: File `src/main/resources/static/css/emr.css` không còn bất kỳ khai báo `Georgia, serif` nào, hoàn toàn sử dụng `Inter, -apple-system, Segoe UI, Arial`.
- Kiểm tra JavaScript: File `src/main/resources/static/js/emr-detail.js` xử lý chuẩn xác việc điều hướng tờ phiếu, tự động tính tuổi, phân bổ 12 ký tự CCCD vào 12 ô vuông, và thuật toán phân tích răng can thiệp để highlight màu vàng `#fde68a` kèm dấu chấm `•` và tooltip.
