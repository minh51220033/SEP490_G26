## 2026-11-07T07:05:05Z
Bạn là Worker 4 (teamwork_worker). Nhiệm vụ của bạn là hoàn thành toàn diện R4: Giao diện & Nghiệp vụ Bệnh án Điện tử MS: BA-18.

Working directory của bạn: c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\worker_r4
Đọc các tài liệu tham chiếu:
- `c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\ORIGINAL_REQUEST.md`
- `c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\PROJECT.md`
- Giao diện mẫu chuẩn: `src/main/resources/templates/doctor/daily-visits.html`
- Tham khảo ảnh mẫu chuẩn Bộ Y Tế MS: BA-18: `C:\Users\PC\.gemini\antigravity\brain\9173a500-cfc4-4bb6-81e4-0875a3ec46d4\docx_images\`

PHẠM VI FILE BẠN SỞ HỮU ĐỘC QUYỀN (CHỈ SỬA CÁC FILE NÀY, TUYỆT ĐỐI KHÔNG SỬA FILE KHÁC):
- `src/main/resources/templates/emr/detail.html`
- `src/main/resources/templates/emr/list.html`
- `src/main/resources/static/css/emr.css`
- `src/main/resources/static/js/emr-detail.js`

YÊU CẦU CHI TIẾT (R4):
1. Bố cục giao diện `src/main/resources/templates/emr/detail.html`:
   - Chuyển sang bố cục 2 cột tiêu chuẩn:
     + Cột trái: Sidebar cố định chiều rộng 280px.
     + Cột phải: Khung hiển thị tài liệu trang in A4 cuộn linh hoạt với nền xám nhạt, viền shadow tinh tế.
   - Đưa khối lịch sử/nhật ký thao tác (Audit log / timeline) vào Drawer trượt từ bên phải hoặc Modal riêng biệt, không chiếm diện tích màn hình chính.
2. Sidebar trái trong `emr/detail.html`:
   - Thẻ thông tin tóm tắt bệnh nhân & mã BAĐT, kèm nút mở Modal cập nhật nhanh thông tin chuẩn BA-18.
   - Cụm nút tác vụ: "Thêm tờ phiếu", "Đính kèm tài liệu".
   - Cây thư mục danh mục phiếu (Bìa hồ sơ, Tờ BA-18, Các phiếu cận lâm sàng, Đơn thuốc...).
   - LOẠI BỎ HOÀN TOÀN khối cảnh báo "Điều kiện khóa" và "Nhắc bổ sung" màu đỏ/cam gây khó chịu cho người dùng.
3. Tờ tài liệu A4 và Form chỉnh sửa:
   - LOẠI BỎ HOÀN TOÀN form chỉnh sửa màu xanh dương nằm ở đáy tờ A4 (`form.emr-edit-form`).
   - Thay bằng các Modal chỉnh sửa độc lập, tiện lợi cho từng tờ phiếu hoặc từng phần.
   - Bổ sung các nút CRUD đầy đủ cho tài liệu đính kèm và các tờ phiếu.
4. Chuẩn hóa Tờ 1 (Bìa hồ sơ bệnh án) và Tờ 2 (Bệnh án ngoại trú RHM - MS: BA-18) theo đúng mẫu Bộ Y Tế:
   - Phần A (Thông tin chung): Đánh số từ 1 đến 14 kèm các ô vuông ký tự tiêu chuẩn (Tuổi, Dân tộc, Quốc tịch, Số định danh/CCCD, Nơi làm việc, Địa chỉ liên hệ) và các ô checkbox lựa chọn Đối tượng (BHYT, Thu phí, Miễn, Khác) & Nơi giới thiệu đến.
   - Phần B - Mục 4 (Sơ đồ răng):
     + Chuẩn hóa 2 bảng chữ thập chồng nhau: Bảng trên là Răng vĩnh viễn (hàng 1: Răng 18->11 | 21->28, hàng 2: Răng 48->41 | 31->38); Bảng dưới là Răng sữa (hàng 1: 55->51 | 61->65, hàng 2: 85->81 | 71->75).
     + Kèm phân loại khe hở môi / vòm miệng nếu có.
     + Các răng có chẩn đoán hoặc thủ thuật can thiệp được tự động đánh dấu nổi bật với màu nền `#fde68a` (vàng nhạt), biểu tượng dấu chấm đen `•`, tooltip hiển thị chi tiết bệnh lý & thủ thuật khi hover.
     + Ngay dưới sơ đồ răng: Có bảng/dòng tổng hợp rõ ràng: Danh sách các răng can thiệp kèm tên thủ thuật thực hiện tương ứng.
   - Bổ sung Mục 5 (Xét nghiệm, Cận lâm sàng) và Mục 6 (Tóm tắt bệnh án, Phương pháp điều trị).
   - Nút tác vụ trực tiếp: "Ký số ngay" và "Đóng HSBA" được đặt trực tiếp trên thanh Sub-header và ngay khu vực ký số ở cuối bệnh án; khi đã ký thì hiển thị con dấu điện tử màu xanh lá (`ĐÃ KÝ SỐ BỞI BS ... NGÀY ...`).
5. Chuẩn hóa CSS và giao diện danh sách Bệnh án điện tử (`emr/list.html`, `emr.css`):
   - Thay thế toàn bộ font chữ có chân Georgia cũ kỹ bằng font chữ hệ thống hiện đại (`Inter`, `Segoe UI`, `Arial`) đồng bộ 100% với toàn hệ thống.
   - Tinh chỉnh `emr/list.html` theo chuẩn `daily-visits.html`: 4 thẻ KPI màu pastel (Tổng số BAĐT, Đang điều trị, Chờ ký số, Đã đóng hồ sơ), thanh lọc `.dv-toolbar-card`, bảng danh sách 9 cột chuẩn AMIS và thanh phân trang cố định ở footer.
