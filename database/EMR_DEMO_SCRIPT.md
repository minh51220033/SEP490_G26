# Kịch bản demo xuyên suốt: hẹn lịch đến bệnh án điện tử

## Chuẩn bị một lần

1. Khởi động ứng dụng để tạo schema nền.
2. Trong PowerShell tại thư mục dự án, chạy:

   ```powershell
   .\database\seeds\run_full_demo.ps1 -ResetEmrDemo
   ```

   Script luôn nạp bằng `utf8mb4`, vì vậy tên tiếng Việt không bị lỗi bảng mã. `-ResetEmrDemo` chỉ xóa dữ liệu có mã `BNEMR*`, `BNFLOW1001`, `VISEMR*`, `EMR-DEMO-*`; không xóa dữ liệu nghiệp vụ khác.

3. Tài khoản test (mật khẩu chung: `Hieu1811`):

| Vai trò | Tài khoản | Dùng cho |
| --- | --- | --- |
| Bệnh nhân | `emr_demo_patient` | Kiểm tra hồ sơ cá nhân và lịch hẹn `APPTEMR1001` |
| Lễ tân | `reception_ft07` | Check-in, phân công ghế/bác sĩ, cấp số |
| Bác sĩ | `emr_demo_doctor` | Khám, mở Hồ sơ bệnh nhân và thao tác EMR |

## Hành trình chính (demo live)

### 0. Khởi tạo tài khoản và năng lực phục vụ

Trong môi trường thật, quản trị viên tạo ba tài khoản, gắn đúng vai trò và liên kết hồ sơ:

1. Tạo **Bệnh nhân**, liên kết tài khoản với `patient_profiles` qua `account_patient_profiles` để bệnh nhân chỉ thấy dữ liệu của chính mình.
2. Tạo **Bác sĩ**, liên kết tài khoản với `staff_profiles`, phân công chi nhánh và lập lịch làm việc trong ngày.
3. Tạo **Lễ tân**, cấp vai trò lễ tân tại chi nhánh.
4. Khai báo phòng, ghế và đảm bảo phòng/ghế đang hoạt động trước khi phân công.

Seed đã chuẩn bị sẵn đúng các liên kết trên: bệnh nhân **Đỗ Khánh Vy - Hành trình đầy đủ** (`BNFLOW1001`), bác sĩ **BS. Nguyễn Minh Khôi** (`BSEMR01`), phòng `EMR-R01`, ghế `EMR-C01` và lịch làm việc hôm nay. Vì vậy toàn bộ luồng dưới đây có thể thao tác ngay mà không phải tạo tay dữ liệu nền.

### 1. Bệnh nhân đặt lịch

- Đăng nhập `emr_demo_patient`; tài khoản đã sở hữu hồ sơ **Đỗ Khánh Vy - Hành trình đầy đủ**.
- Lịch `APPTEMR1001` đã ở trạng thái **CONFIRMED**, ngày hiện tại, lúc 09:30. Đây là lịch "đầu vào" an toàn để chạy toàn bộ hành trình.
- Khi trình diễn đầy đủ trên giao diện, bệnh nhân tạo lịch mới từ màn đặt lịch: chọn **Cơ sở Quận 1**, ngày hôm nay, 09:30 và lý do đau răng. Lễ tân xác nhận lịch. Sau đó dùng lịch vừa tạo ở bước 2.
- Khi cần demo nhanh hoặc không muốn tạo lại lịch, dùng trực tiếp `APPTEMR1001`; không nên check-in lịch này hai lần. Chạy lại script reset để khôi phục.

### 2. Lễ tân check-in

- Đăng nhập `reception_ft07` → **Bác sĩ & Điều phối → Check-in**.
- Chọn lịch `APPTEMR1001`, giữ lại ghi nhận triệu chứng, bấm **Check-in**.
- Hệ thống tạo một lượt khám mã `V...`, trạng thái **WAITING**, và lịch chuyển **CHECKED_IN**.

### 3. Phân công bác sĩ, phòng, ghế và cấp số

- Mở **Bác sĩ & Điều phối → Điều phối ghế nha**.
- Chọn lượt vừa tạo; phân công **BS. Nguyễn Minh Khôi**, **Phòng khám demo EMR (EMR-R01)** và **Ghế demo EMR số 1 (EMR-C01)**.
- Bấm **Cấp số thường**. Kiểm tra số xuất hiện trong bảng hàng chờ.
- Có thể dùng thêm dữ liệu `VISFT0801`–`VISFT0804` để minh họa chưa phân công, số thường, cấp cứu và ưu tiên.

### 4. Bác sĩ khám và chẩn đoán

- Đăng nhập `emr_demo_doctor`.
- Mở màn khám/chuẩn đoán của lượt vừa được điều phối; ghi phiếu khám, chẩn đoán chính có mã ICD-10, thủ thuật và đơn thuốc.
- Điều kiện dữ liệu để module EMR cho phép tạo là: lượt khám có **bác sĩ**, **phiếu khám lâm sàng**, **chẩn đoán chính gắn ICD-10**. Thủ thuật, đơn thuốc, sơ đồ răng và kế hoạch điều trị được kéo vào snapshot khi có dữ liệu, nhưng không bị module EMR sửa ngược lại.
- Nếu chỉ cần trình diễn EMR ngay, dùng các lượt đã chuẩn bị:

| Lượt khám | Tình trạng | Mục đích |
| --- | --- | --- |
| `VISEMR1001` | Đủ phiếu khám + chẩn đoán chính + thủ thuật + đơn thuốc, chưa có EMR | Tạo và ký một bệnh án mới |
| `VISEMR1002` | Đã có `EMR-DEMO-DRAFT` với snapshot cũ | Bấm **Làm mới dữ liệu** rồi kiểm tra/khóa |
| `VISEMR1003` | Đã có `EMR-DEMO-SIGNED` | Tạo **phiên bản sửa đổi** |
| `VISEMR1004` | Thiếu phiếu khám, bác sĩ, chẩn đoán | Minh họa chặn tạo/khóa |
| `VISEMR1005` | Có phiếu khám nhưng thiếu chẩn đoán chính | Minh họa validation ICD-10 |

### 5. Tạo, kiểm tra và ký bệnh án điện tử

- Mở **Hồ sơ & Tái khám → Bệnh án điện tử**.
- Tại `VISEMR1001`, bấm **Tạo bản nháp**.
- Mở bệnh án → **Kiểm tra hồ sơ** → **Khóa phiên bản**.
- Bấm **Người bệnh ký thử**, sau đó **Bác sĩ ký thử**. Cả hai là `LOCAL_TEST_ONLY`, chỉ phục vụ demo state-machine; không phải chữ ký số pháp lý.
- Mở `EMR-DEMO-DRAFT` để minh họa cảnh báo snapshot cũ và thao tác **Làm mới dữ liệu**.
- Mở `EMR-DEMO-SIGNED`, nhập lý do rồi tạo **phiên bản sửa đổi**.

### 6. Tra cứu từ Hồ sơ bệnh nhân

- Vào **Hồ sơ & Tái khám → Hồ sơ bệnh nhân**.
- Mở bệnh nhân `BNEMR1001` hoặc `BNEMR1003`, tab **Bệnh án**.
- Danh sách EMR hiển thị mã, trạng thái, lượt khám, phiên bản và liên kết **Xem chi tiết bệnh án**.
- Trong chi tiết lượt khám, thẻ **Bệnh án điện tử** dẫn trực tiếp đến phiên bản tương ứng.

## Xác nhận sau khi demo

- Bệnh án mới có 3 tài liệu: bìa, bệnh án ngoại trú Răng Hàm Mặt, tổng kết điều trị.
- Sau ký thử, trạng thái là `SIGNED`, có 2 chữ ký và audit log.
- Bệnh án đã ký không bị sửa trực tiếp; thao tác sửa tạo một version mới.
- Chạy lại `run_full_demo.ps1 -ResetEmrDemo` để khôi phục các kịch bản EMR về trạng thái ban đầu.
