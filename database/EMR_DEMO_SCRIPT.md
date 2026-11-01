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
| `VISEMR1001` | Đủ phiếu khám + chẩn đoán chính + thủ thuật + đơn thuốc; chưa có EMR sau khi reset demo | Tạo và ký một bệnh án mới |
| `VISEMR1002` | Đã có `EMR-DEMO-DRAFT` với snapshot cũ | Bấm **Làm mới dữ liệu** rồi kiểm tra/khóa |
| `VISEMR1003` | Đã có `EMR-DEMO-SIGNED` | Tạo **phiên bản sửa đổi** |
| `VISEMR1004` | Thiếu phiếu khám, bác sĩ, chẩn đoán | Minh họa chặn tạo/khóa |
| `VISEMR1005` | Có phiếu khám nhưng thiếu chẩn đoán chính | Minh họa validation ICD-10 |

### 5. Tạo, kiểm tra và ký bệnh án điện tử

- Mở **Hồ sơ & Tái khám → Bệnh án điện tử**.
- Sau khi chạy `-ResetEmrDemo`, tại `VISEMR1001` bấm **Tạo bản nháp**.
- Mở bệnh án → **Kiểm tra hồ sơ** → **Khóa phiên bản**.
- Bác sĩ khám tích ô xác nhận rồi bấm **Ký thử và hoàn tất**. Đây là `LOCAL_TEST_ONLY`, chỉ phục vụ demo state machine.
- Mở `EMR-DEMO-DRAFT` để minh họa cảnh báo snapshot cũ và thao tác **Làm mới dữ liệu**.
- Mở `EMR-DEMO-SIGNED`, nhập lý do rồi tạo **phiên bản sửa đổi**.

### 6. Tra cứu từ Hồ sơ bệnh nhân

- Vào **Hồ sơ & Tái khám → Hồ sơ bệnh nhân**.
- Mở bệnh nhân `BNEMR1001` hoặc `BNEMR1003`, tab **Bệnh án**.
- Danh sách EMR hiển thị mã, trạng thái, lượt khám, phiên bản và liên kết **Xem chi tiết bệnh án**.
- Trong chi tiết lượt khám, thẻ **Bệnh án điện tử** dẫn trực tiếp đến phiên bản tương ứng.

## Xác nhận sau khi demo

- Bệnh án mới có 5 thành phần: bìa, bệnh án ngoại trú Răng Hàm Mặt, tổng kết bệnh án ngoại trú, danh mục đính kèm và thông tin xác thực.
- Sau ký thử, trạng thái là `SIGNED`, có chữ ký thử của bác sĩ và audit log.
- Bệnh án đã ký không bị sửa trực tiếp; thao tác sửa tạo một version mới.
- Chạy lại `run_full_demo.ps1 -ResetEmrDemo` để khôi phục các kịch bản EMR về trạng thái ban đầu.
# Kiểm thử màn bệnh án ngoại trú sau khi bổ sung rule và timeline

Chạy `./database/seeds/run_full_demo.ps1` trong PowerShell sau khi ứng dụng đã tạo schema. Nếu cần đưa riêng bộ EMR demo về trạng thái ban đầu, dùng `-ResetEmrDemo` (thao tác này xóa các bản ghi demo EMR trước khi nạp lại). Đăng nhập `emr_demo_doctor / Hieu1811`.

1. Vào **Bệnh án điện tử**. Danh sách bệnh án có phân trang 10 hồ sơ/trang; lọc và chuyển trang giữ nguyên điều kiện tìm kiếm.
2. Mở **EMR-DEMO-DRAFT**. Bản nháp được seed với snapshot cũ. Bấm **Làm mới dữ liệu**, xem lại bệnh án ngoại trú và phần tổng kết. Tình trạng sau khám đã có, hướng theo dõi vẫn thiếu nên **Kiểm tra hồ sơ** phải báo chặn khóa. Điền hướng theo dõi ở chế độ **Chỉnh sửa** của **Tổng kết bệnh án ngoại trú** rồi kiểm tra lại.
3. Mở một lượt khám `VISEMR1001` chưa có EMR và bấm **Tạo bản nháp**. Kiểm tra dữ liệu khám, chẩn đoán, dịch vụ và đơn thuốc đã chuyển sang bệnh án. Nếu nguồn thiếu trường, mục **Điều kiện khóa** chỉ ra nội dung chặn; mục **Nhắc bổ sung** nêu trường nên hoàn thiện. Nút **Mở phiếu khám để bổ sung** quay về FT11. Sau khi sửa tại FT11, bấm **Làm mới dữ liệu** trong EMR.
4. Trong tờ **Tổng kết bệnh án ngoại trú**, xem các lượt khám trước nếu bệnh nhân có lịch sử, rồi điền tình trạng khi kết thúc và hướng điều trị tiếp theo. Tờ **Danh mục tài liệu đính kèm** hiển thị ảnh nguồn khi còn là bản nháp; sau khi khóa, ảnh được sao lưu riêng và có hash nội dung.
   Riêng `VISEMR1001` có tệp **sơ đồ răng minh họa** do script sao chép vào `uploads/clinical_photos`. Sau khi hồ sơ đủ dữ liệu và được khóa, mở tờ đính kèm lần nữa: ảnh được tải qua đường dẫn EMR có kiểm tra quyền, hiển thị kích thước và SHA-256 của bytes đã lưu riêng. Đây là ảnh minh họa kiểm thử, không phải ảnh bệnh nhân.
   Bảng bên dưới cũng liệt kê các phiếu labo của lượt khám. Nếu FT12 đã liên kết ảnh X-quang với lượt khám hoặc phiếu labo có ảnh nội bộ, ảnh đó sẽ được đưa vào cùng gói đính kèm khi khóa. URL ảnh ngoài hoặc tệp nguồn bị thiếu sẽ làm thao tác khóa báo lỗi; bác sĩ cần đưa ảnh vào kho nội bộ trước.
5. Mở **EMR-DEMO-SIGNED** để xem bản đã ký thử, lịch sử phiên bản và trang xác thực. Nhãn `LOCAL_TEST_ONLY` cho biết chữ ký demo chưa phải chữ ký số của nhà cung cấp.
6. Với hồ sơ `BNEMR1001`, sau khi đủ dữ liệu, khóa phiên bản rồi tích ô xác nhận để bác sĩ `emr_demo_doctor` ký thử. Không có bước người bệnh ký bệnh án. Thử gửi POST thiếu `confirmed=true`: server phải từ chối.

Không dùng ca demo đã ký để kiểm chứng quy tắc khóa mới: bản này được seed sẵn nhằm thử hiển thị lịch sử và trạng thái. Các ca tạo mới phải đi qua kiểm tra phía server trước khi khóa.
