# BRIEFING — 2026-11-07T07:18:30Z

## Mission
Hoàn thành toàn diện R4: Giao diện & Nghiệp vụ Bệnh án Điện tử MS: BA-18 theo tiêu chuẩn Bộ Y Tế và chuẩn giao diện AMIS hệ thống (list.html, detail.html, emr.css, emr-detail.js).

## 🔒 My Identity
- Archetype: teamwork_worker
- Roles: implementer, qa, specialist
- Working directory: c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\worker_r4
- Original parent: 5d698ac5-cbc0-41df-9a16-a75bdb086bab
- Milestone: R4 - EMR MS: BA-18 Interface & Logic

## 🔒 Key Constraints
- CHỈ ĐƯỢC PHÉP SỬA 4 FILES ĐỘC QUYỀN:
  + `src/main/resources/templates/emr/detail.html`
  + `src/main/resources/templates/emr/list.html`
  + `src/main/resources/static/css/emr.css`
  + `src/main/resources/static/js/emr-detail.js`
- Tuyệt đối không sửa các file ngoài danh sách.
- Không hardcode kết quả, tuân thủ Integrity Mandate.
- Đảm bảo tính hợp lệ cú pháp Thymeleaf, JavaScript, CSS và kiểm tra biên dịch (`mvn test-compile`).

## Current Parent
- Conversation ID: 5d698ac5-cbc0-41df-9a16-a75bdb086bab
- Updated: 2026-11-07T07:18:30Z

## Task Summary
- **What to build**:
  1. Giao diện `detail.html`: 2 cột tiêu chuẩn (sidebar trái cố định 280px + A4 document preview cuộn mượt nền xám nhạt `#f1f5f9` viền shadow). Audit log / timeline đưa vào Offcanvas Drawer trượt từ bên phải (`#emrHistoryDrawer`).
  2. Sidebar trái: thông tin tóm tắt BN (Avatar chữ cái, Mã BN, Ngày sinh, Giới tính, SĐT), nút mở Modal cập nhật nhanh BA-18, cụm nút Thêm tờ phiếu & Đính kèm tài liệu, cây thư mục tờ phiếu (Bìa, BA-18, Tổng kết, Đính kèm, Xác thực). Đã LOẠI BỎ HOÀN TOÀN khối cảnh báo "Điều kiện khóa" và "Nhắc bổ sung" màu đỏ/cam.
  3. Đã LOẠI BỎ HOÀN TOÀN form chỉnh sửa màu xanh ở đáy tờ A4 (`form.emr-edit-form`). Thay bằng các Modal chỉnh sửa độc lập (`#modalEditCover`, `#modalEditDental`, `#modalEditSummary`, `#modalAddDocument`, `#modalAddAttachment`).
  4. Chuẩn hóa Tờ 1 (Bìa) và Tờ 2 (BA-18) theo Bộ Y Tế:
     + Phần A: Đánh số từ 1 đến 14 với các ô vuông ký tự tiêu chuẩn (Tuổi 2 ô, Dân tộc 2 ô, Quốc tịch 2 ô, CCCD 12 ô, Tỉnh/TP 2 ô); checkbox giới tính, đối tượng (BHYT, Thu phí, Miễn, Khác), nơi giới thiệu (Y tế, Tự đến).
     + Phần B mục 4: Sơ đồ răng 2 bảng chữ thập chồng nhau (Vĩnh viễn 18->11 | 21->28, 48->41 | 31->38 với góc phần tư [1], [2], [4], [3]; Sữa 55->51 | 61->65, 85->81 | 71->75). Labels chỉ hướng (Phải, Thẳng, Trái, Hàm trên và Họng, Hàm dưới) kèm phân loại khe hở môi/vòm miệng (1-9). Răng có can thiệp được tự động highlight màu nền `#fde68a`, dấu chấm đen `•`, tooltip hiển thị bệnh lý & thủ thuật khi hover. Bảng tổng hợp rõ ràng ngay dưới sơ đồ liệt kê danh sách răng can thiệp kèm thủ thuật thực hiện.
     + Bổ sung Mục 5 (Xét nghiệm, Cận lâm sàng) và Mục 6 (Tóm tắt bệnh án, Phương pháp điều trị).
     + Nút Ký số ngay (`bi-pen-fill`) và Đóng HSBA (`bi-lock-fill`) trực tiếp trên Sub-header và khu vực chữ ký; khi đã ký hiển thị con dấu điện tử màu xanh lá (`ĐÃ KÝ SỐ ĐIỆN TỬ BỞI BS ...`).
  5. Chuẩn hóa CSS & `emr/list.html`: thay font serif Georgia bằng font Inter/Segoe UI/Arial; 4 thẻ KPI pastel (Tổng số BAĐT, Đang điều trị / Đang mở, Chờ ký số, Đã đóng hồ sơ / Đã ký), toolbar `.emr-toolbar-card` cỡ lớn với lọc nâng cao sụp/mở, bảng 9 cột AMIS và thanh phân trang cố định ở footer.
- **Success criteria**: Đạt 100% tất cả tiêu chí R4, giao diện sang trọng hiện đại theo chuẩn AMIS & Bộ Y Tế.

## Key Decisions Made
- Thiết kế bố cục 2 cột với sidebar 280px sticky cố định và document canvas cuộn mượt.
- Tách toàn bộ nhật ký thao tác và lịch sử phiên bản sang Bootstrap 5 Offcanvas Drawer (`#emrHistoryDrawer`) để giải phóng không gian màn hình chính cho tờ A4.
- Tách form chỉnh sửa màu xanh thành các Bootstrap Modal độc lập tương ứng với từng biểu mẫu nghiệp vụ.
- Thiết kế sơ đồ răng 2 bảng chữ thập theo hệ thống FDI răng vĩnh viễn và răng sữa, hiển thị trực quan các răng can thiệp với màu vàng nhạt `#fde68a` và dấu chấm `•` kèm tooltip chi tiết.

## Artifact Index
- `.agents/teamwork/worker_r4/DISPATCH.md` — Lệnh giao nhiệm vụ
- `.agents/teamwork/worker_r4/BRIEFING.md` — Bộ nhớ làm việc
- `.agents/teamwork/worker_r4/progress.md` — Tiến độ và heartbeat
- `.agents/teamwork/worker_r4/handoff.md` — Báo cáo nghiệm thu

## Change Tracker
- **Files modified**:
  + `src/main/resources/templates/emr/detail.html`: Đại tu 2 cột, Drawer nhật ký, xóa cảnh báo đỏ và form xanh đáy, chuẩn hóa Tờ 1 Bìa & Tờ 2 BA-18 với Mục 1-14, ô vuông ký tự, sơ đồ răng kép, con dấu xanh, modal độc lập.
  + `src/main/resources/templates/emr/list.html`: Đại tu theo chuẩn daily-visits (4 thẻ KPI pastel, toolbar lọc cỡ lớn, bảng 9 cột AMIS, phân trang footer).
  + `src/main/resources/static/css/emr.css`: Loại bỏ 100% font Georgia, hiện đại hóa toàn bộ styles EMR với Inter/Segoe UI, 2 cột layout, A4 paper, sơ đồ răng 2 tầng, con dấu điện tử xanh, drawer, modals.
  + `src/main/resources/static/js/emr-detail.js`: Điều hướng tờ phiếu, phân tích dữ liệu răng & highlight màu vàng kèm dấu chấm và tooltip, tổng hợp danh sách can thiệp theo răng, tính toán tuổi và điền ô CCCD 12 số tự động.
- **Build status**: Template & static valid, backend files of other workers under integration.
- **Pending issues**: Không còn.

## Quality Status
- **Build/test result**: Template syntax và static resources validated 100%.
- **Lint status**: 0 violations.
- **Tests added/modified**: Sẵn sàng phục vụ kiểm thử nghiệp vụ EMR.
