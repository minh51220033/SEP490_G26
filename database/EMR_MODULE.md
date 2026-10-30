# Module bệnh án điện tử (độc lập)

Module này chỉ đọc dữ liệu nguồn của `visits`, `clinical_examinations`,
`examination_diagnoses`, `performed_services`, `prescriptions` và
`clinical_photos`. Module không cập nhật nghiệp vụ FT03, FT04 hoặc FT11.

## Cài đặt database

Thứ tự dành cho môi trường test hiện tại:

1. Tạo/nạp schema gốc của dự án.
2. Chạy `test_data.sql` để có các lượt khám chuẩn `VIS10001` và `VIS10002`.
3. Chạy `database/migrations/V6__emr_module.sql`.
4. Chạy `database/seeds/seed_emr_module.sql`.
5. Kiểm tra câu `SELECT` cuối file seed trả về:
   - `EMR-DEMO-DRAFT`: 3 documents, 0 signatures.
   - `EMR-DEMO-SIGNED`: 3 documents, 2 signatures.

Các file migration và seed đều không sửa cột/bảng của feature khác. Seed có thể
chạy lặp lại vì sử dụng mã duy nhất và `NOT EXISTS`.

## Màn hình

- Sidebar: **Hồ sơ & Tái khám → Bệnh án điện tử**.
- Danh sách và tạo hồ sơ: `GET /emr/records`.
- Chi tiết snapshot/version/ký/audit: `GET /emr/records/{recordId}`.

Chỉ tài khoản `ADMIN` hoặc `BAC_SI` đang hoạt động được truy cập. Bác sĩ chỉ
được xem hồ sơ thuộc chi nhánh được phân công; admin được xem toàn hệ thống.

## Kịch bản test chính

### A. Tạo bệnh án mới từ dữ liệu khám

1. Mở `/emr/records`.
2. Chọn một lượt khám chưa có bệnh án và bấm **Tạo bản nháp**.
3. Kiểm tra snapshot hiển thị bệnh nhân, lượt khám, chẩn đoán, thủ thuật và đơn thuốc.
4. Nếu FT11 thay đổi dữ liệu nguồn, màn chi tiết phải báo **Snapshot đã cũ**.
5. Bấm **Làm mới dữ liệu**, sau đó **Kiểm tra hồ sơ**.

### B. Khóa và ký thử nghiệm

1. Hồ sơ chỉ chuyển `READY_TO_LOCK` khi có phiếu khám, bác sĩ và chẩn đoán chính có ICD-10.
2. Bấm **Khóa phiên bản**; hồ sơ chuyển `PATIENT_SIGNATURE_PENDING`.
3. Bấm **Người bệnh ký thử**; hồ sơ chuyển `DOCTOR_SIGNATURE_PENDING`.
4. Bấm **Bác sĩ ký thử**; hồ sơ chuyển `SIGNED`.
5. Kiểm tra có hai chữ ký, content hash, và audit cho mỗi thao tác.

### C. Sửa bệnh án đã ký

1. Mở `EMR-DEMO-SIGNED`.
2. Nhập lý do và bấm **Tạo phiên bản sửa đổi**.
3. Bản v1 vẫn là bản đã ký hợp lệ; v2 ở trạng thái `AMENDMENT_DRAFT`.
4. Thực hiện lại kiểm tra, khóa và ký cho v2; chỉ khi v2 ký xong thì v1 mới chuyển `SUPERSEDED`.

## API

- `GET /api/emr/records`
- `GET /api/emr/records/{id}`
- `POST /api/emr/records`
- `POST /api/emr/records/{id}/refresh-snapshot`
- `POST /api/emr/records/{id}/validate`
- `POST /api/emr/records/{id}/lock`
- `POST /api/emr/records/{id}/test-signatures/patient`
- `POST /api/emr/records/{id}/test-signatures/doctor`
- `POST /api/emr/records/{id}/amendments`

## Giới hạn có chủ đích

Provider `LOCAL_TEST_ONLY` chỉ dùng để test state machine, hash, version và UI.
Đây không phải chữ ký điện tử/chữ ký số có giá trị pháp lý. Khi tích hợp VNPT
SmartCA hoặc Viettel MySign, giữ nguyên state machine và thay endpoint ký thử
bằng adapter gửi yêu cầu, callback có idempotency key và kiểm tra chứng thư.
