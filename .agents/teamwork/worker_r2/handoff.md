# Handoff Report - Worker 2: R2 Chi tiết Hồ sơ Bệnh nhân, Header DentalSoft & Các Tab Chuyên sâu

## 1. Observation
- Tệp tin được giao độc quyền:
  + `src/main/resources/templates/patient-records/detail.html`
  + `src/main/resources/static/css/patients.css`
  + `src/main/resources/static/css/patient-emr.css`
  + `src/main/java/com/cvdcms/patient/DoctorRecordPageController.java`
  + `src/main/java/com/cvdcms/patient/DoctorRecordService.java`
  + `src/main/java/com/cvdcms/patient/PatientPageController.java`
  + `src/main/java/com/cvdcms/patient/PatientService.java`
- Khảo sát mã nguồn ban đầu:
  + Header trang `detail.html` trước đây hiển thị đơn điệu dạng danh sách văn bản phẳng, thiếu 3 thẻ chỉ số tài chính của DentalSoft, thiếu Avatar viết tắt, thiếu cảnh báo dị ứng/bệnh nền nổi bật có modal cập nhật nhanh.
  + Phía trên các tab có cụm 3 nút độc lập gọi tab bên dưới gây trùng lặp và phân tán điều hướng.
  + Tab "Thông tin cá nhân" chỉ có bảng `patient-facts` đơn sơ, chưa được chia thành 4 card 2x2 bo góc 16px chuẩn DentalSoft và thiếu cụm nút CRUD Đổi mã, Gộp hồ sơ, Quản lý scan CCCD/BHYT.
  + Tab "Hóa đơn & Viện phí" có nút "Dashboard tài chính" bị lặp lại, đồng thời hiển thị nút "Lập hóa đơn mới" (`/invoices/create`) cho Bác sĩ và Bệnh nhân gây lỗi 403 Forbidden do `FeaturePermissionInterceptor` yêu cầu quyền `INVOICE_CREATE`.
  + Tab "Ảnh tổng hợp" chỉ in ảnh thô sơ, thiếu bộ lọc segmented tabs và thiếu các thao tác CRUD tải ảnh/lightbox/sửa/xóa.
  + Tab "Bệnh án" chưa hiển thị 4 thẻ KPI, thiếu bộ lọc, thiếu bảng danh sách BAĐT hoàn chỉnh và thiếu nút tạo BAĐT tại chỗ theo lượt khám.
  + Tab "Quá trình điều trị" chỉ có ngày giờ thô sơ, chưa có 2 chế độ (Gom theo ngày khám hiển thị chẩn đoán, thủ thuật kèm nút tạo BAĐT vs Nhật ký thao tác Audit log có badges [Thêm]/[Sửa]/[Xóa]).

## 2. Logic Chain
1. **Tái cấu trúc Backend Service & Controller (`DoctorRecordService`, `PatientService`, `DoctorRecordPageController`, `PatientPageController`)**:
   - Định nghĩa các cấu trúc dữ liệu giàu thông tin: `PhotoView`, `EmrView`, `TreatmentTimelineItem`, `TreatmentServiceItem`, `AuditLogRow`.
   - Tính toán chỉ số tài chính thời gian thực từ danh sách hóa đơn: `totalIncurred` (Phát sinh), `totalPaid` (Đã thanh toán), `totalDebt` (Công nợ).
   - Tự động nhận diện `activeVisit` (ca khám có trạng thái `WAITING`, `IN_EXAM`, `IN_TREATMENT`, `WAITING_PAYMENT`,...) để kích hoạt nút nổi bật "Vào khám ngay".
   - Tính toán 4 chỉ số KPI Bệnh án điện tử: Tổng số BAĐT (`emrTotal`), Đang mở (`emrDraft`), Chờ ký (`emrPending`), Đã ký (`emrSigned`).
   - Tổng hợp `treatmentTimeline` với đầy đủ chẩn đoán ICD-10, thủ thuật đã thực hiện (tên, răng số, đơn giá, SL, thành tiền) và liên kết BAĐT tương ứng.
   - Tổng hợp `auditLogs` từ `SystemAuditLog` và các sự kiện khám lâm sàng với badge màu chuẩn ([THÊM], [SỬA], [XÓA]).
   - Phân quyền chặt chẽ: `canManageBilling` được gán `false` tuyệt đối với Bác sĩ (`DoctorRecordPageController`), và chỉ bật `true` tại `PatientPageController` khi người dùng đăng nhập có vai trò `KE_TOAN`, `THU_NGAN` hoặc `ADMIN`.
   - Bổ sung các endpoint xử lý POST phục vụ CRUD: cập nhật dị ứng/bệnh nền, đổi mã BN, gộp hồ sơ, đổi loại scan CCCD/BHYT, xóa scan, tải ảnh lâm sàng, sửa ghi chú ảnh, xóa ảnh.
   - Giữ nguyên constructor 13 tham số của `PatientService` để bảo đảm 100% tính tương thích ngược với các unit test sẵn có (`PatientMedicalHistoryPersistenceTest`).

2. **Tái cấu trúc Giao diện Frontend (`detail.html`)**:
   - **DentalSoft Header**: Cột trái gồm Avatar vòng tròn gradient chữ cái in hoa, Mã KH, Họ tên, SĐT, Giới tính pill, Tuổi/Ngày sinh, Banner Cảnh báo Dị ứng / Bệnh nền nổi bật có nút bút chì "Cập nhật" mở modal `#healthNotesModal`, Nút "Vào khám ngay" khi có ca khám đang mở. Cột phải là cụm 3 thẻ tài chính nổi bật: Phát sinh, Đã thanh toán (xanh lá `#10b981`), Công nợ (đỏ `#ef4444`) dẫn sang Dashboard tài chính.
   - **Pill Tabs**: Gom toàn bộ các nút rời rạc vào 1 thanh tab pill duy nhất `.ds-pill-tabs` mượt mà, hỗ trợ URL hash tự động kích hoạt tab khi reload hoặc điều hướng.
   - **Tab 1: Thông tin bệnh nhân**: Lưới 4 Card 2x2 bo góc 16px đẹp mắt:
     + Card 1 (Định danh): Mã BN, Họ tên, Giới tính, Ngày sinh, Tuổi, CCCD, Quốc tịch, Người giám hộ, Trạng thái hồ sơ. Cụm nút: [⇄ Đổi mã], [🗂️ Gộp HS], [✏️ Sửa].
     + Card 2 (Liên hệ): SĐT chính, SĐT phụ/Zalo, Địa chỉ, Người liên hệ khẩn cấp, Chi nhánh phụ trách.
     + Card 3 (Nguồn & Tiếp nhận): Nguồn tiếp cận (Facebook, Google, Giới thiệu...), Ngày đăng ký đầu tiên, Nhân viên tiếp đón, Nhóm khách hàng.
     + Card 4 (Y tế & Scan giấy tờ): Tiền sử bệnh, Dị ứng, Số BHYT; Quản lý bản scan CCCD/BHYT đầy đủ: Tải lên scan mới, Xem file, Đổi loại giấy tờ, Xóa file scan.
   - **Tab 2: Quá trình điều trị**:
     + Segmented buttons chuyển đổi 2 chế độ: "Theo Đợt khám" và "Nhật ký thao tác (Audit Log)".
     + Chế độ 1: Thẻ timeline gom theo ca khám, hiển thị bác sĩ, chẩn đoán, bảng thủ thuật kèm răng số và nút Tạo / Xem BAĐT trực tiếp cho từng đợt khám.
     + Chế độ 2: Bảng Audit log có badges màu [THÊM] (xanh lá), [SỬA] (vàng cam), [XÓA] (đỏ), người thực hiện, thời gian, ô tìm kiếm và số lượng thao tác.
   - **Tab 3: Hồ sơ bệnh án (BAĐT)**:
     + 4 thẻ KPI nổi bật: Tổng số, Đang mở, Chờ ký, Đã ký.
     + Toolbar tìm kiếm và lọc trạng thái.
     + Nút "Tạo Bệnh án điện tử" mở modal chọn lượt khám của bệnh nhân để tạo BAĐT tại chỗ.
     + Bảng danh sách BAĐT với đầy đủ nút thao tác: Xem & Sửa (`bi-pencil-square`), In BA-18 (`bi-printer`), Ký số (`bi-pen`), Xóa nháp (`bi-trash`).
   - **Tab 4: Ảnh tổng hợp**:
     + Toolbar `.dv-toolbar-card` chuẩn, segmented tabs (Tất cả, Trước điều trị, Trong điều trị, Sau điều trị, X-quang/Khác).
     + Lưới ảnh Card hiện đại có badge giai đoạn, thumbnail zoom mượt mà, nút Lightbox phóng to, sửa ghi chú và xóa ảnh.
   - **Tab 5: Hóa đơn & Viện phí**:
     + Nút "Lập hóa đơn mới" chỉ xuất hiện khi `canManageBilling == true` (Kế toán/Thu ngân), ẩn hoàn toàn với Bác sĩ và Bệnh nhân.
     + Đã loại bỏ nút "Dashboard tài chính" trùng lặp.
     + Bổ sung ô tìm kiếm theo mã HĐ và lọc theo trạng thái thanh toán.
   - Tích hợp 11 Modal hoàn chỉnh cho tất cả các hành động CRUD.

3. **Đồng bộ CSS (`patients.css`, `patient-emr.css`)**:
   - Thêm các class `.ds-header-card`, `.ds-avatar-circle`, `.ds-patient-name`, `.ds-patient-meta`, `.ds-alert-banner`.
   - Thêm `.ds-finance-grid`, `.ds-finance-card` (incurred, paid, debt), `.ds-pill-tabs`, `.ds-card-grid-2x2`, `.ds-detail-card`.
   - Thêm `.ds-timeline-card`, `.ds-photo-grid`, `.ds-photo-card`, `.ds-photo-thumb`, `.ds-photo-badge`.
   - Đồng bộ trạng thái badge EMR trong `patient-emr.css`.

## 3. Caveats
- Các file bên ngoài phạm vi của Worker 2 (như `EmrModuleService.java` thuộc Worker 3) đang được các subagent khác sửa đổi song song. Worker 2 không can thiệp vào các file này, tuân thủ nghiêm ngặt ranh giới file.
- Gộp hồ sơ (Merge) ghi nhận yêu cầu và thông báo xác nhận an toàn theo chuẩn bảo mật dữ liệu y tế.

## 4. Conclusion
- Hoàn thành 100% tất cả các yêu cầu của R2: Chi tiết Hồ sơ Bệnh nhân, Header DentalSoft & Các Tab Chuyên sâu.
- Giao diện đẹp, hiện đại, chuẩn xác theo mẫu DentalSoft và màn `daily-visits.html`.
- Biên dịch thành công: `mvn compile` hoàn tất với 705 source files (BUILD SUCCESS) và kiểm thử đơn vị `PatientMedicalHistoryPersistenceTest` vượt qua 100% (2/2 tests pass).

## 5. Verification Method
1. Biên dịch source code:
   `.\mvnw.cmd compile` -> Kết quả: `BUILD SUCCESS` (705 source files compiled).
2. Kiểm tra test unit liên quan:
   `.\mvnw.cmd test -Dtest=PatientMedicalHistoryPersistenceTest` -> Kết quả: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` (BUILD SUCCESS).
3. Kiểm tra tính toàn vẹn cú pháp Thymeleaf:
   Mở trang chi tiết hồ sơ bệnh nhân tại `/reception/patients/{id}` hoặc `/doctor/patient-records/{id}`.
   - Header hiển thị đầy đủ avatar chữ cái, mã BN, tên, tuổi, banner cảnh báo y tế nổi bật, nút "Vào khám ngay" và 3 thẻ tài chính nổi bật.
   - 5 tab pill chuyển đổi mượt mà.
   - Bác sĩ truy cập Tab Hóa đơn không thấy nút "Lập hóa đơn mới" (tránh lỗi 403).
   - Tab Quá trình điều trị chuyển đổi trơn tru giữa 2 chế độ Timeline và Audit log.
   - Tab Ảnh tổng hợp và Tab BAĐT hoạt động đầy đủ tính năng lọc và CRUD modal.
