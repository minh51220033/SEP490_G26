# Original User Request

## Parameters
[skip-interview]
How this run was launched; not part of the task.

## Initial Request — 2026-11-07T09:33:00Z

sửa mấy cái trong cái doc này 

6.Cái này thì bỏ bớt mấy cái nhưng “facebook “ “giới thiệu” Vãng Lai “ hoặc gộp thành 1 chứ sao cứ để bừa ra vậy ?
7.mấy cái thao tác này cũng chưa được như http://localhost:8080/doctor/daily-visits đã bảo bắt chước y hệt hoặc lấy lại code cũ cho nhanh. 
8.http://localhost:8080/emr/records/3 Làm cho nó giống hệt cái ảnh ở dưới từng tý 1 phải giống 
9.Vẫn ở http://localhost:8080/emr/records/3QUAN TRỌNG : Là cái của người khác có 3 chế độ thì có chế độ Chỉnh sửa là nhập chỉnh sửa luôn vào được hiểu LÀM 3 cái chế độ này đi .Sao lại có cái nút “Sửa tờ bìa “ và có độc ít thông tin này ? và tôi muốn là kiểu nó sửa luôn vào đây ý , kiểu vẫn lấy thông tin nhưng cũng nhập được . Và nó bỏ cái” HỒ SƠ BỆNH ÁN ĐIỆN TỬ “ bỏ cái “(Ban hành kèm theo Quyết định của Bộ Y Tế - MS: BA-18) “ bỏ cái “Nội trú✓Ngoại trú …”Cái phần “BỆNH ÁN NGOẠI TRÚ RĂNG HÀM MẶT“ cũng làm tương tự tất cả đúng từng phần 
10.Nhưng quan trọng là cái phần . Sơ đồ răng thì làm kiểu này Chứ hiện tại FE đang bị như thế này sai lè .

---
### CHI TIẾT KỸ THUẬT VÀ TỆP CẦN SỬA:

#### 1. Mục 6 & 7: `src/main/resources/templates/patient-records/list.html`
- **Mục 6 (Bỏ các nút nguồn Facebook / Giới thiệu / Vãng lai trên thanh tab)**:
  - Trên thanh `.dv-quick-segment` (dòng ~695-734), xóa bỏ các nút rời: `Facebook`, `Giới thiệu`, `Vãng lai`.
  - Chỉ giữ lại đúng 3 tab phân loại cơ bản: `Tất cả`, `Đang hoạt động`, `Lưu trữ / Tạm ngừng`. Bộ lọc nguồn khách hàng đã có sẵn trong dropdown `Nguồn tiếp nhận` ở thanh `Bộ lọc` nâng cao.
- **Mục 7 (Cột Thao tác THAO TÁC)**:
  - Bỏ 3 nút ô vuông pastel `[eye] [pencil] [trash]` hiện tại.
  - Thay bằng cụm nút thao tác đồng bộ với `daily-visits.html` / `d8fb426`: dùng nút bấm `Xem hồ sơ` (class `.dv-equal-btn-primary` hoặc `.btn-action-icon` icon sạch sẽ `bi-eye`, `bi-pencil`, `bi-trash` có hover chuẩn DentalFlow/MISA AMIS).

#### 2. Mục 8, 9, 10: `src/main/resources/templates/emr/detail.html`, `src/main/resources/static/css/emr.css`, `src/main/resources/static/js/emr-detail.js`, `src/main/java/com/cvdcms/emr/EmrPageController.java`
- **Mục 8 (Giao diện chuẩn EMR DentalSoft giống ảnh mẫu)**:
  - Sidebar trái trang EMR: Menu "Hồ sơ bệnh án" màu xanh lá (#10b981), logo "EMR DentalSoft - BỆNH ÁN ĐIỆN TỬ".
  - Thanh breadcrumb đỉnh trang: `⊞ > Hồ sơ bệnh án > [TÊN BỆNH NHÂN] > [MÃ HỒ SƠ]`.
  - Khối thông tin bệnh nhân góc trên trái: `Bệnh nhân: [Họ tên]`, `Ngày sinh: [Năm sinh]`, hai nút `[+ Thêm phiếu]`, `[+ Thêm tài liệu]`.
  - Accordion cây danh mục: Header `Hồ sơ chính` nền xanh lá (#10b981) có icon chevron, danh sách tờ phiếu thụt lề:
    1. `Bìa hồ sơ bệnh án` (ngày tạo)
    2. `Bệnh án ngoại trú Răng Hàm Mặt (Sơ đồ răng)` (ngày tạo) - active có viền bao quanh
    3. `Tổng kết bệnh án ngoại trú Răng hàm mặt` (ngày tạo)
  - Phía trên tờ bệnh án: Thanh điều khiển chế độ xem: `Chế độ xem: ○ Chỉnh sửa ⦿ Bản in`.
  - Nút in nổi ở góc dưới bên phải màn hình: `[🖨️ In phiếu]`.

- **Mục 9 (Chế độ Chỉnh sửa trực tiếp trên văn bản - Inline Editing, bỏ Modal popup cụt lủn "Sửa tờ bìa")**:
  - Có 2 chế độ xem: `Chỉnh sửa` và `Bản in` (khi chọn radio `Chỉnh sửa`, các ô/dòng trên tờ Bìa và Bệnh án chuyển sang dạng input/textarea có thể gõ trực tiếp nội dung vào form văn bản thay vì phải bật modal popup).
  - Có nút `Lưu thay đổi` (hoặc submit POST form về `/emr/records/{id}/documents/{documentCode}`) để lưu trực tiếp các trường nhập liệu từ trang văn bản.
  - Xóa bỏ các dòng thừa theo yêu cầu:
    - Bỏ dòng chữ `HỒ SƠ BỆNH ÁN ĐIỆN TỬ`.
    - Bỏ dòng chữ `(Ban hành kèm theo Quyết định của Bộ Y Tế - MS: BA-18)`.
    - Trên tờ bìa / tờ bệnh án: phần tiêu đề chuẩn `BỆNH ÁN NGOẠI TRÚ RĂNG HÀM MẶT`, định dạng sạch sẽ như ảnh mẫu.

- **Mục 10 (Sơ đồ răng chuẩn chữ thập 2 bảng đồng đều kích thước)**:
  - Sửa lại bảng sơ đồ răng trong `emr/detail.html` và `emr.css`:
    - Bảng trên: Răng vĩnh viễn (16 cột: `8 7 6 5 4 3 2 1 | 1 2 3 4 5 6 7 8`), có số góc `[1]` bên trái và `[2]` bên phải.
    - Bảng dưới: Răng sữa (10 cột: `5 4 3 2 1 | 1 2 3 4 5`), có số góc `[4]` bên trái và `[3]` bên phải.
    - **Quan trọng**: Mỗi ô răng ở cả 2 bảng phải có **cùng chiều rộng cố định** (ví dụ `width: 32px; min-width: 32px; text-align: center`), căn giữa đối xứng qua vạch chữ thập đen thẳng đứng ở giữa. Răng sữa KHÔNG bị bóp méo hay lệch độ rộng ô so với răng vĩnh viễn!
    - Ô răng có can thiệp / chẩn đoán được tô màu vàng `#fde68a` với số răng và dấu chấm đen `•` ngay bên dưới số.
    - Phía dưới sơ đồ răng: hiển thị dòng text `Răng [XX]: [Tên thủ thuật / chẩn đoán]` (ví dụ `Răng 16: Răng sứ Titan`), tiếp theo là mục `5. Các xét nghiệm, cận lâm sàng cần làm: [ ] Không [ ] Có, ghi rõ:`.

Hãy đọc kỹ tài liệu, tiến hành chỉnh sửa mã nguồn và báo cáo kết quả chi tiết.

## Follow-up — 2026-11-07T09:34:00Z

[skip-interview] Yêu cầu đã đầy đủ và chi tiết từ Mục 6 đến Mục 10. Tiến hành phân công và thực thi code ngay lập tức không cần phỏng vấn.
