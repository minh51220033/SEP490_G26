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

## 8. Quy chuẩn giao diện Vùng nội dung, Chi tiết & Biểu mẫu (Content Area UI/UX)
- **Duy nhất 1 Primary CTA & Gom nút hành động**: Mỗi màn hình chỉ có tối đa 1 nút Primary đặc màu cho hành động kết thúc chính (ví dụ: *Lưu kế hoạch*). Các nút thao tác phụ bên trong section dùng kiểu Tonal nhẹ (`.df-btn-tonal-primary`) hoặc Outline/Secondary; đặt cặp nút theo thứ tự **Secondary trái — Primary phải** (IBM Carbon). Không lặp cùng một nút tạo/thêm ở cả header khối lẫn từng card con; gom các nút đối xứng (Mở/Thu gọn) thành 1 nút toggle hoặc Overflow Menu `⋯` / Split Button.
- **Luồng nghiệp vụ & Tiết lộ tuần tự (Mental Model + Progressive Disclosure)**: Sắp xếp các mục theo đúng trình tự tư duy chuyên môn (ví dụ: *Chẩn đoán → Dịch vụ → Thông tin chung → Lộ trình → Thanh toán*), tự mở khối tùy chọn khi đã có dữ liệu sẵn và thu gọn khi trống; không đặt 2 widget cùng chức năng trên 1 vùng nhìn. Empty State chỉ nêu gọn *nguyên nhân → hành động tiếp theo* (Shopify Polaris).
- **Adaptive Layout & Micro-States cho màn hình chi tiết lượt khám / ca bệnh (IBM Carbon & Shopify Polaris)**: Không ép khóa `height: 100vh` hay `grid-template-rows: 1fr` khiến khối trống bị kéo giãn khổng lồ; để khối mở rộng tự nhiên theo dữ liệu thật (neo sticky cột sơ đồ răng khi cần). Khi một module chưa có dữ liệu (`0` chẩn đoán, kế hoạch, đơn thuốc), thu gọn thành thanh Micro-State 1 dòng (`~48px`) thay vì card trống lớn; tự ẩn badge `0`, switcher `(0)|(0)` và dải màu Sơ đồ răng FDI khi chưa có răng/dữ liệu. Màn hình tra cứu lượt khám chỉ giữ nhóm thông tin tần suất dùng cao (*FDI, Chẩn đoán/Thủ thuật, Kế hoạch, Đơn thuốc/Tái khám, Phim ảnh/BAĐT*), loại bỏ bảng sinh hiệu/khám lâm sàng chi tiết nếu không phải màn hình nhập khám.
- **Tối giản bề mặt & Phân cấp 3 tầng (Refactoring UI / Material 3)**: Tránh lạm dụng `table-bordered` và thẻ card viền dày lồng nhau; ưu tiên tách khối bằng nền nhẹ (`#f8fafc` trên `#ffffff`), khoảng trắng bội số 4px/8px và phân tách hàng ngang mảnh `#f1f5f9`. Tiêu đề section dùng Sentence case ngắn gọn (3–5 từ). Nút xóa dạng icon giữ màu xám trung tính mặc định, chỉ hiện đỏ khi hover.
- **Biểu mẫu & Validate UX (Atlassian)**: Không disable nút Submit form; giữ nút bấm được và báo lỗi inline ngay cạnh trường thiếu / banner đầu thẻ (tự ẩn ngay khi sửa đúng), kết hợp autofocus vào ô nhập chính đầu tiên khi tạo mới.


