## 2026-11-07T07:04:49Z
Bạn là Worker 3 (teamwork_worker). Nhiệm vụ của bạn là hoàn thành toàn diện R3: Chi tiết Lượt khám & Backend Đồng bộ EMR.

Working directory của bạn: c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\worker_r3
Đọc các tài liệu tham chiếu:
- `c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\ORIGINAL_REQUEST.md`
- `c:\Users\PC\Desktop\SEP490_G26_CV_DMS\.agents\teamwork\PROJECT.md`
- Giao diện mẫu chuẩn: `src/main/resources/templates/doctor/daily-visits.html`

PHẠM VI FILE BẠN SỞ HỮU ĐỘC QUYỀN (CHỈ SỬA CÁC FILE NÀY, TUYỆT ĐỐI KHÔNG SỬA FILE KHÁC):
- `src/main/resources/templates/patient-records/visit.html`
- `src/main/java/com/cvdcms/patient/DoctorRecordRepository.java`
- `src/main/java/com/cvdcms/patient/PatientVisitReadRepository.java`
- `src/main/java/com/cvdcms/patient/VisitView.java`
- `src/main/java/com/cvdcms/emr/EmrModuleService.java`
- `src/main/java/com/cvdcms/emr/EmrSourceSnapshot.java`
- `src/main/java/com/cvdcms/emr/EmrPageController.java`
- `src/main/java/com/cvdcms/emr/EmrRestController.java`

YÊU CẦU CHI TIẾT (R3):
1. Đại tu giao diện màn Xem chi tiết lượt khám tại `src/main/resources/templates/patient-records/visit.html`:
   - Đồng bộ bảng màu chủ đạo `#0284c7`, thẻ card bo góc 16px, badge rõ nét, typography sắc nét theo chuẩn `daily-visits.html`.
   - Header lượt khám: Mã lượt khám, Ngày giờ khám, Bác sĩ phụ trách, Trạng thái lượt khám, kèm 2 nút hành động chính:
     + "Mở màn hình Khám & Điều trị" (chuyển hướng sang `/doctor/examination/{visitId}`)
     + "Mở / Tạo Bệnh án điện tử" (liên kết hoặc tạo nhanh EMR cho lượt khám này).
2. Tổng hợp và hiển thị đầy đủ, trực quan dữ liệu từ ca khám bác sĩ trên `visit.html`:
   - Thông tin hành chính lượt khám, Lý do đến khám (Chief complaint), Tiền sử bệnh lý & Dị ứng.
   - Dấu hiệu sinh tồn (Vital signs): Mạch, Huyết áp, Nhiệt độ, Nhịp thở, Cân nặng với card chỉ số sinh động.
   - Sơ đồ răng trực quan thể hiện tình trạng răng đã ghi nhận trong ca khám.
   - Danh sách Chẩn đoán xác định & chẩn đoán kèm theo (kèm mã ICD-10 và mô tả).
   - Kế hoạch điều trị tổng thể và Bảng Danh sách Dịch vụ / Thủ thuật đã thực hiện (gồm Tên thủ thuật, Răng thực hiện, Bác sĩ làm, Chi phí, Trạng thái).
   - Chỉ định Cận lâm sàng & Ảnh lâm sàng / X-quang chụp trong ca khám (hỗ trợ preview ảnh).
   - Bảng Đơn thuốc chi tiết (Tên thuốc, Hoạt chất, Hàm lượng, Cách dùng, Số lượng) & Ghi chú Lịch hẹn tái khám.
3. Backend đồng bộ dữ liệu Ca khám sang Bệnh án điện tử (`EmrModuleService.java`, `EmrPageController.java`, `EmrRestController.java`, `EmrSourceSnapshot.java`):
   - Đảm bảo nguyên tắc chặt chẽ: 1 Bệnh án điện tử tương ứng 1 Lượt khám (Visit). Nếu lượt khám đã có BAĐT thì mở hoặc đồng bộ lại, không tạo bản ghi rác.
   - Tự động ánh xạ toàn bộ dữ liệu từ ca khám (examination) sang `EmrSourceSnapshot` và các tờ phiếu EMR:
     + Thông tin hành chính bệnh nhân & lượt khám.
     + Danh sách răng điều trị và tên thủ thuật từng răng (dùng để vẽ sơ đồ răng can thiệp trên Tờ MS: BA-18).
     + Dấu hiệu sinh tồn, chẩn đoán, thuốc kê, tóm吐 điều trị.
   - Bổ sung/hoàn thiện endpoint REST/POST:
     + Endpoint đồng bộ lại dữ liệu từ ca khám vào BAĐT đang mở (cho phép bác sĩ cập nhật dữ liệu mới nhất từ ca khám sang EMR).
     + Endpoint cập nhật nhanh thông tin hành chính bệnh nhân từ modal trong BAĐT.
     + Hỗ trợ xóa mềm (Soft-Delete) cho bản nháp BAĐT theo đúng quy định y tế.
4. Cập nhật các repository / DTO (`DoctorRecordRepository.java`, `PatientVisitReadRepository.java`, `VisitView.java`) để cung cấp dữ liệu đầy đủ cho `visit.html`.

