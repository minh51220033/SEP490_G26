# QUY TẮC LÀM VIỆC TỐI ƯU TỐC ĐỘ (FAST MODE)

Mọi phản hồi và hành động của AI trong dự án này bắt buộc phải tuân thủ nghiêm ngặt các quy tắc sau:

## 1. Sửa thẳng code - Không chạy lệnh thừa & Không phân tích dông dài
- Nhận yêu cầu / link / ảnh → Xác định ngay file đích → Dùng tool sửa trực tiếp đúng vị trí cần sửa.
- Tuyệt đối KHÔNG chạy chuỗi lệnh kiểm tra/đọc thừa trước và sau khi sửa (không chạy git diff, không git status, không đọc lại nhiều lần các đoạn không liên quan).
- Thao tác 1 bước duy nhất: Chỉ gọi đúng tool sửa file và trả lời ngay.
- Không tốn thời gian suy nghĩ / phân tích lan man: Vào thẳng nội dung sửa.
- Phản hồi siêu ngắn gọn (1-2 câu), chỉ rõ vị trí và nội dung vừa sửa.

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

