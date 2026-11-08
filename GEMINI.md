# QUY TẮC LÀM VIỆC TỐI ƯU TỐC ĐỘ (FAST MODE)

Mọi phản hồi và hành động của AI trong dự án này bắt buộc phải tuân thủ nghiêm ngặt các quy tắc sau:

## 1. Sửa thẳng code - Không chạy lệnh thừa
- Nhận yêu cầu / link / ảnh → Xác định ngay file đích → Dùng tool sửa trực tiếp đúng vị trí cần sửa.
- Không quét tìm kiếm (`grep`) vòng quanh các file không liên quan.
- Không gọi nhiều lệnh kiểm tra trạng thái trước và sau khi sửa.
- Phản hồi ngắn gọn, chỉ rõ vị trí và nội dung vừa sửa.

## 2. Tuyệt đối KHÔNG chạy Maven build / test
- KHÔNG chạy `./mvnw`, `mvn compile`, `mvn test`, `mvn test-compile` hay bất kỳ lệnh build nào trừ khi người dùng yêu cầu đích danh.
- Người dùng tự kiểm thử và chạy trên IDE (Eclipse / VS Code / IntelliJ) qua DevTools hot-reload.

## 3. Tuyệt đối KHÔNG tự ý Git add / Git commit
- Mọi thay đổi code giữ nguyên trên working directory để người dùng tự xem `diff`, tự test và tự commit khi đạt yêu cầu.

## 4. Xử lý siêu tốc theo URL & Ảnh chụp
- Khi người dùng gửi URL (ví dụ: `/doctor/workflows`, `/admin/services`) hoặc ảnh chụp giao diện: Map thẳng tới Controller và template Thymeleaf (`.html`) tương ứng để sửa ngay lập tức.
- Khi người dùng gửi log lỗi: Đọc ngay tên file và số dòng báo lỗi ở dòng đầu tiên của log để sửa ngay.

## 5. Bảo toàn cấu hình Database
- Giữ nguyên vẹn cấu hình kết nối database trong `application.properties` (`spring.datasource.password=class9stop`), không tự ý mở hay thay đổi file này.

