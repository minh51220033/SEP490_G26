Cập nhật và hoàn thiện giao diện danh sách hồ sơ bệnh nhân và trang chi tiết hồ sơ bệnh án điện tử (EMR) theo các yêu cầu kỹ thuật và giao diện mẫu.

Integrity mode: [OPEN: Integrity mode restrictions]

## Requirements

### R1. Chuẩn hóa thanh tab phân loại và cột thao tác tại danh sách hồ sơ bệnh nhân
- Tại `src/main/resources/templates/patient-records/list.html`, trên thanh `.dv-quick-segment`, loại bỏ các nút rời: `Facebook`, `Giới thiệu`, `Vãng lai`. Chỉ giữ lại 3 tab phân loại cơ bản: `Tất cả`, `Đang hoạt động`, `Lưu trữ / Tạm ngừng`. Bộ lọc nguồn khách hàng sử dụng dropdown `Nguồn tiếp nhận` ở thanh `Bộ lọc` nâng cao.
- Tại cột Thao tác, thay thế các nút pastel hiện tại bằng cụm thao tác đồng bộ với `daily-visits.html` [OPEN: Lựa chọn giữa nút bấm 'Xem hồ sơ' hay cụm icon 'Xem / Sửa / Xóa' có hover].

### R2. Chuẩn hóa giao diện trang chi tiết EMR và cây danh mục hồ sơ
- Tại `src/main/resources/templates/emr/detail.html` và file CSS liên quan, cấu trúc sidebar trái với menu "Hồ sơ bệnh án" màu xanh lá (#10b981), logo "EMR DentalSoft - BỆNH ÁN ĐIỆN TỬ".
- Hiển thị thanh breadcrumb đỉnh trang: `⊞ > Hồ sơ bệnh án > [TÊN BỆNH NHÂN] > [MÃ HỒ SƠ]`.
- Khối thông tin bệnh nhân góc trên trái gồm: `Bệnh nhân: [Họ tên]`, `Ngày sinh: [Năm sinh]`, hai nút `[+ Thêm phiếu]`, `[+ Thêm tài liệu]`.
- Accordion cây danh mục: Header `Hồ sơ chính` nền xanh (#10b981) có icon chevron, danh sách tờ phiếu thụt lề:
  1. `Bìa hồ sơ bệnh án` (ngày tạo)
  2. `Bệnh án ngoại trú Răng Hàm Mặt (Sơ đồ răng)` (ngày tạo) - active có viền bao quanh
  3. `Tổng kết bệnh án ngoại trú Răng hàm mặt` (ngày tạo)
- Hiển thị nút in nổi ở góc dưới bên phải màn hình: `[🖨️ In phiếu]`.

### R3. Hỗ trợ chế độ chỉnh sửa trực tiếp (Inline Editing) và loại bỏ các nội dung thừa
- Cung cấp thanh điều khiển chế độ xem phía trên tờ bệnh án [OPEN: Lựa chọn 2 chế độ 'Chỉnh sửa / Bản in' hay 3 chế độ 'Xem / Chỉnh sửa / Bản in'].
- Ở chế độ `Chỉnh sửa`, cho phép nhập/chỉnh sửa trực tiếp trên form văn bản thay vì popup modal "Sửa tờ bìa", có nút lưu thay đổi và gửi dữ liệu cập nhật về backend (`EmrPageController`).
- Loại bỏ các dòng chữ thừa:
  - Bỏ `HỒ SƠ BỆNH ÁN ĐIỆN TỬ`.
  - Bỏ `(Ban hành kèm theo Quyết định của Bộ Y Tế - MS: BA-18)`.
  - Định dạng chuẩn tiêu đề `BỆNH ÁN NGOẠI TRÚ RĂNG HÀM MẶT` trên cả tờ bìa và tờ bệnh án.

### R4. Chuẩn hóa sơ đồ răng chữ thập hai bảng đối xứng
- Bảng răng vĩnh viễn (hàng trên, 16 cột: `8 7 6 5 4 3 2 1 | 1 2 3 4 5 6 7 8`) với số góc `[1]` bên trái và `[2]` bên phải.
- Bảng răng sữa (hàng dưới, 10 cột: `5 4 3 2 1 | 1 2 3 4 5`) với số góc `[4]` bên trái và `[3]` bên phải.
- Mỗi ô răng ở cả 2 bảng có chiều rộng cố định (ví dụ 32px), căn giữa đối xứng qua vạch chữ thập đen thẳng đứng ở giữa.
- Răng có can thiệp/chẩn đoán được highlight màu vàng `#fde68a`, hiển thị số răng và dấu chấm đen `•` bên dưới số.
- Phía dưới sơ đồ: hiển thị text `Răng [XX]: [Tên thủ thuật / chẩn đoán]` và mục `5. Các xét nghiệm, cận lâm sàng cần làm: [ ] Không [ ] Có, ghi rõ:`.

## Acceptance Criteria

### Giao diện và Kiểm thử
- [ ] [OPEN: Acceptance criteria và phương thức kiểm thử/xác minh độc lập]
