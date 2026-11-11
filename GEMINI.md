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

## 6. Kiểm tra đúng tên field / getter giữa Entity, DTO/Record và Thymeleaf
- Trước khi viết biểu thức Thymeleaf (`th:text`, `th:value`, `th:href`, JS inline `/*[[...]]*/`) hoặc gọi method Java mới: Luôn đọc qua class/record/entity nguồn để dùng đúng tên thuộc tính & getter thực tế (đặc biệt trên template dùng chung như `patient-records/detail.html`, nơi truyền `PatientView`, `ReceptionVisitView` dùng `id`, `code`, `phoneNumber` khác với Entity dùng `visitId`, `visitCode`, `phone`; hoặc `PatientProfile` dùng `preferredBranch` chứ không phải `branch`).
- Tránh suy đoán tên getter/field gây lỗi biên dịch (dẫn tới mất Spring bean khi khởi động) hoặc lỗi `SpelEvaluationException` khi render giao diện.

## 7. Quy chuẩn giao diện Danh sách, Bảng & Toolbar (chuẩn `/doctor/daily-visits` & Bootstrap 5)
- **Toolbar & Bộ lọc gọn**: Dùng `.dv-toolbar-card` với ô tìm kiếm `.dv-search-box` bên trái; bên phải gồm bộ đếm kết quả, nút **Lọc nâng cao** (`.dv-adv-filter-btn` + `.dv-filter-pulse-badge`) và nút tác vụ chính (`.dv-toolbar-primary-btn`). Đưa phân loại trạng thái/danh mục và khoảng thời gian vào `<select class="form-select dv-adv-control">` bên trong `.dv-adv-filter-panel`; tránh lặp KPI cards hay dải nút pill trùng chức năng.
- **Bảng dữ liệu (`.dv-grid-card` + `.dv-amis-table`)**:
  - Giữ viền/bo góc thoáng (`border-radius: 16px`, dòng `.dv-data-row` nền trắng, hover `#f0f9ff`), dùng `.dv-code-pill` và `.dv-status-pill` có `text-nowrap` để không rớt chữ/mã xuống nhiều dòng.
  - Khi bảng có nhiều cột trong `.dv-table-scroll-container`, dùng thanh thao tác nổi bám mép phải khi di chuột qua dòng & cuộn ngang (`.dv-floating-actions-track` + `.dv-floating-actions-bar` với `.dv-equal-btn-primary` / `.dv-equal-btn-secondary`).
- **Bootstrap 5 chuẩn – không dùng class thập phân kiểu Tailwind**: Tuyệt đối không viết `gap-1.5`, `gap-2.5`, `px-3.5`, `py-1.5`, `me-1.5`, `me-0.5` (bị trình duyệt bỏ qua khiến icon dính sát chữ và nút mất padding). Luôn dùng utility hợp lệ của Bootstrap 5 (`gap-2`, `px-3`, `py-2`, `me-1`, `me-2`) hoặc `gap` CSS thuần (`gap: 7px–8px`).

