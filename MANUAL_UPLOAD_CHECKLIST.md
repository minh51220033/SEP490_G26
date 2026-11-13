# MANUAL UPLOAD CHECKLIST — TÀI NGUYÊN HÌNH ẢNH & FILE DEMO CV-DCMS

Tài liệu này tổng hợp kết quả rà soát toàn bộ source code (Entity, Controller, Security, Thymeleaf Templates), thư mục `uploads/`, thư mục `src/main/resources/static/` và Database `cv_dcms_db` của dự án **CV-DCMS (SEP490_G26)**.

---

## I. PHÂN LOẠI 3 NHÓM TÀI NGUYÊN

### NHÓM 1 — Không cần upload lại (Đã có sẵn hoặc hệ thống dùng SVG / Avatar chữ cái)

| Thành phần | Hiện trạng kiểm chứng trong code & ổ đĩa | Ghi chú |
|---|---|---|
| Logo thương hiệu **CV-DCMS / Cường Việt Dental** | Render bằng **inline SVG** trong `templates/fragments/logo.html` | Không dùng file ảnh rời |
| Ảnh đại diện Bác sĩ (`doctors/list.html`, `doctors/detail.html`, `portal/home.html`) | Bảng `staff_profiles` **không có cột `avatar_url`**; giao diện hiển thị avatar chữ cái viết tắt (`doc.initials`) | Không cần chuẩn bị ảnh bác sĩ trừ khi nâng cấp thêm cột + template |
| Ảnh không gian phòng khám & Hero trang chủ (`/uploads/images/...`) | Đã có đủ **7 file** trên ổ đĩa: `clinc_treatment.png`, `clinic_treatment.png`, `waiting_area.png`, `clinic_room.png`, `whitening.png`, `storefont.png`, `storefront.png` | Lưu ý tên file viết thiếu chữ trong template là `clinc_treatment.png` và fallback `storefont.png` — cả 2 file này **đều đã tồn tại** |
| Sơ đồ răng giải phẫu FDI (`/images/fdi-chart-pure.png`) | Đã có sẵn tại `src/main/resources/static/images/fdi-chart-pure.png` (215.8 KB) | Dùng trong `icd10-diagnosis.html` và fallback `labo-tab.html` |
| Ảnh lâm sàng mẫu (`uploads/clinical_photos/`) | Đã có sẵn **4 file**: `demo_intraoral_before.jpg`, `demo_intraoral_after.jpg`, `demo_smile_before.jpg`, `demo_smile_after.jpg` | Có thể gắn thẳng đường dẫn `/uploads/clinical_photos/...` vào bảng `clinical_photos` |
| Avatar mẫu (`uploads/avatars/`) | Đã có sẵn **4 file JPG** (`*.jpg`) | Dùng được ngay hoặc upload thêm nếu muốn nhiều mẫu khác nhau |
| Chữ ký kho (`uploads/signatures/`) & Hóa đơn kho (`uploads/invoices/`) | Đã có sẵn **12 file PNG** chữ ký và **5 file JPG** hóa đơn nhập kho | Dùng lại khi seed phiếu nhập/xuất kho |

### NHÓM 2 — Nên upload để giao diện Demo đầy đủ, thẩm mỹ và chuyên nghiệp

1. **Ảnh đại diện 2 cơ sở (`branches.image_url`)**: `branch_cn01.jpg`, `branch_cn02.jpg` (hiện DB `cv_dcms_db` chưa có dữ liệu; nếu để `NULL`, UI tự fallback sang `/uploads/images/storefont.png`).
2. **Ảnh bìa Dịch vụ nha khoa (`services.image_url`)**: khoảng 6–8 dịch vụ tiêu biểu (Cấy ghép Implant, Niềng răng mắc cài, Bọc răng sứ, Tẩy trắng răng, Nhổ răng khôn, Điều trị tủy, Lấy cao răng, Khám tổng quát). Trong `uploads/services/` mới có 1 file `e4f1d111-0d3a-4642-95a9-79b669c75ff7.jpg`.
3. **Ảnh bìa Tin tức nha khoa (`news_articles.thumbnail`)**: 3–5 bài viết kiến thức nha khoa. File duy nhất hiện có trong `uploads/news/` (`..._WIN_20241002_11_46_43_Pro.jpg`) là ảnh chụp webcam không phù hợp để trình chiếu bảo vệ đồ án — nên thay bằng ảnh nha khoa chuẩn.
4. **Ảnh phim X-quang mẫu (`uploads/nanopix/`)**: Hiện thư mục đang **trống (0 file)**. Nếu demo phân hệ X-quang NanoPix (`/xray`), nên có 2–3 phim Panoramic / Periapical thực tế (ẩn thông tin bệnh nhân).
5. **Ảnh phiếu mẫu đặt xưởng Labo (`uploads/labo/`)**: Hiện thư mục đang **trống (0 file)**. Nếu demo luồng Labo có ảnh đơn/kết quả, nên chuẩn bị 1–2 ảnh mẫu.

### NHÓM 3 — Bắt buộc bổ sung nếu sử dụng các màn hình liên quan (đang thiếu file thực tế)

1. **3 ảnh placeholder tĩnh trong `src/main/resources/static/images/`** (Template HTML/JS đang trỏ cứng đường dẫn `/images/...` nhưng trên ổ đĩa **chưa tồn tại**, dẫn đến HTTP `404` nếu ảnh gốc lỗi hoặc bản ghi chưa có ảnh):
   - `/images/dental-placeholder.png` (tham chiếu 6 lần trong `templates/doctor/clinical-photos.html` lines 192, 764, 889, 890, 894, 895).
   - `/images/placeholder-tooth.png` (tham chiếu trong `templates/patient-records/visit.html` line 861).
   - `/images/placeholder-xray.png` (tham chiếu trong `templates/patient-records/visit.html` line 873).
2. **`uploads/clinical_photos/emr-demo-odontogram.png`**: Được script `database/seeds/run_full_demo.ps1` copy từ `fdi-chart-pure.png` khi nạp demo EMR (hiện chưa có trong `uploads/clinical_photos/`).

---

## II. BẢNG CHECKLIST CHI TIẾT CẦN CHUẨN BỊ / UPLOAD

> **Quy tắc chung của Backend (`AdminServiceCatalogController`, `AdminNewsController`, `BranchController`, `ProfileController`)**:
> - Định dạng cho phép: **JPG, JPEG, PNG, WEBP** (`application-prod.properties` giới hạn multipart `15MB`, các controller ảnh profile/dịch vụ/tin tức/chi nhánh kiểm tra tối đa **5MB/file**).
> - Đường dẫn `/uploads/avatars/**`, `/uploads/news/**`, `/uploads/services/**`, `/uploads/images/**` được phép truy cập công khai (`SecurityConfig.permitAll()`).
> - Đường dẫn `/uploads/nanopix/**`, `/uploads/clinical_photos/**`, `/uploads/invoices/**`, `/uploads/signatures/**`, `/uploads/labo/**` yêu cầu đăng nhập đúng quyền (`SecuredUploadResourceController`).

| STT | Tên ảnh / file | Dùng ở màn hình nào | Đường dẫn lưu | DB cột tương ứng | Định dạng | Kích thước khuyến nghị | Ghi chú |
|---:|---|---|---|---|---|---|---|
| 1 | `dental-placeholder.png` | Ảnh lâm sàng (`clinical-photos.html` fallback & before/after mặc định) | `src/main/resources/static/images/dental-placeholder.png` | Không lưu DB (Static asset) | PNG | `800x600` (4:3), <= 500 KB | **Bắt buộc** để tránh 404 tại `/doctor/clinical-photos` |
| 2 | `placeholder-tooth.png` | Chi tiết lượt khám (`patient-records/visit.html` dòng 861) | `src/main/resources/static/images/placeholder-tooth.png` | Không lưu DB (Static asset) | PNG | `600x450` (4:3), <= 300 KB | **Bắt buộc** nếu mở trang `visit.html` có ảnh rỗng |
| 3 | `placeholder-xray.png` | Chi tiết lượt khám (`patient-records/visit.html` dòng 873) | `src/main/resources/static/images/placeholder-xray.png` | Không lưu DB (Static asset) | PNG | `800x500` (16:10), <= 300 KB | **Bắt buộc** nếu mở trang `visit.html` có X-quang rỗng |
| 4 | `branch_cn01.jpg` | Trang chủ (`/home`), danh sách/chi tiết chi nhánh (`/admin/branches`, `/branches`) | `uploads/images/branch_cn01.jpg` (hoặc upload qua UI `/admin/branches`) | `branches.image_url` = `/uploads/images/branch_cn01.jpg` | JPG / PNG / WEBP | `1200x800` (3:2), <= 2 MB | Ảnh mặt tiền/nội thất Cơ sở 1 (Cầu Giấy) |
| 5 | `branch_cn02.jpg` | Trang chủ (`/home`), danh sách/chi tiết chi nhánh (`/admin/branches`, `/branches`) | `uploads/images/branch_cn02.jpg` | `branches.image_url` = `/uploads/images/branch_cn02.jpg` | JPG / PNG / WEBP | `1200x800` (3:2), <= 2 MB | Ảnh mặt tiền/nội thất Cơ sở 2 (Hà Đông) |
| 6 | `svc_implant.jpg`, `svc_orthodontics.jpg`, `svc_porcelain.jpg`, `svc_whitening.jpg`, `svc_wisdom_tooth.jpg`, `svc_root_canal.jpg`, `svc_scaling.jpg`, `svc_checkup.jpg` | Danh mục dịch vụ (`/services`, `/services/{id}`, `/admin/services`) | `uploads/services/<ten_file>.jpg` (hoặc upload qua UI `/admin/services`) | `services.image_url` = `/uploads/services/<ten_file>.jpg` | JPG / PNG / WEBP | `800x600` (4:3), <= 1.5 MB | Đã có 1 ảnh trong `uploads/services/`; nên bổ sung 6–8 ảnh đúng chuyên khoa |
| 7 | `news_implant.jpg`, `news_nieng_rang.jpg`, `news_cham_soc_rang.jpg`, `news_tay_trang.jpg` | Tin tức trang chủ (`/home`), `/news`, `/news/{slug}`, `/admin/news` | `uploads/news/<ten_file>.jpg` (hoặc upload qua UI `/admin/news`) | `news_articles.thumbnail` = `/uploads/news/<ten_file>.jpg` | JPG / PNG / WEBP | `1200x675` (16:9), <= 2 MB | Không dùng ảnh webcam cũ đang có trong `uploads/news/` |
| 8 | `xray_pano_demo_01.jpg`, `xray_pano_demo_02.jpg`, `xray_periapical_01.jpg` | Quản lý phim NanoPix (`/xray`), Tab X-quang trong hồ sơ khám | `uploads/nanopix/<ten_file>.jpg` | `nanopix_xray_sync_logs.image_url` = `/uploads/nanopix/<ten_file>.jpg` | JPG / PNG | `1600x900` hoặc `1200x800`, <= 3 MB | Thư mục đang trống; cần để demo ca khám có X-quang & đối chiếu NanoPix |
| 9 | `labo_order_01.jpg`, `labo_order_02.jpg` | Quản lý Labo (`/labo`, Tab Labo trong phiên khám) | `uploads/labo/<ten_file>.jpg` | `labo_orders.image_url` = `/uploads/labo/<ten_file>.jpg` | JPG / PNG | `1000x750` (4:3), <= 2 MB | Thư mục đang trống; dùng để demo phiếu mẫu hàm/chỉ định Labo |
| 10 | `emr-demo-odontogram.png` | Bệnh án điện tử EMR (khi chạy seed/xem ảnh đính kèm EMR) | `uploads/clinical_photos/emr-demo-odontogram.png` | `clinical_photos.image_url` = `/uploads/clinical_photos/emr-demo-odontogram.png` | PNG | Khớp `fdi-chart-pure.png` (`215 KB`) | Có thể copy trực tiếp từ `src/main/resources/static/images/fdi-chart-pure.png` |
| 11 | `patient_avatar_01..04.jpg` (tùy chọn bổ sung) | Header Portal bệnh nhân, `/profile`, `/reception/patients/{id}` | `uploads/avatars/<ten_file>.jpg` | `patient_profiles.avatar_url` = `/uploads/avatars/<ten_file>.jpg` | JPG / PNG / WEBP | `400x400` (1:1), <= 1 MB | Đã có 4 file trong `uploads/avatars/`, có thể dùng lại hoặc upload thêm |

---

## III. HƯỚNG DẪN CHI TIẾT CÁCH CHUẨN BỊ, UPLOAD & KIỂM TRA

### 1. Hướng dẫn từng file cần chuẩn bị

1. **Ảnh placeholder tĩnh (`dental-placeholder.png`, `placeholder-tooth.png`, `placeholder-xray.png`)**
   - **Nội dung**: Biểu tượng răng sạch sẽ nền sáng nhạt (cho `dental-placeholder.png`, `placeholder-tooth.png`) và khung phim X-quang tối giản nền xám/xanh đậm (cho `placeholder-xray.png`).
   - **Nguồn lấy**: Tạo từ SVG/Canva hoặc ảnh minh họa nha khoa miễn phí bản quyền (Unsplash/Freepik/Flaticon).
   - **Tỉ lệ & nền**: Tỉ lệ `4:3` (`800x600` px) hoặc `16:10`, **không bắt buộc nền trong suốt** (nền trắng/xám nhạt #f8fafc hiển thị đẹp hơn trong thẻ `card` có `object-fit: cover`).
   - **Đặt tên & Thư mục**: Đúng tên như bảng trên, chép vào `c:\SEP490_G26\src\main\resources\static\images\`.
   - **Cập nhật DB**: Không cần cập nhật DB.
   - **Kiểm tra hiển thị**: Mở `http://localhost:8080/images/dental-placeholder.png`, `/images/placeholder-tooth.png`, `/images/placeholder-xray.png` phải trả HTTP `200 OK` (nếu chạy bằng IDE/Maven dev-server sau khi thêm file tĩnh mới vào `src/main/resources/static/images/`, cần reload resource hoặc restart nhẹ để `target/classes` nhận file).

2. **Ảnh 2 cơ sở (`branch_cn01.jpg`, `branch_cn02.jpg`)**
   - **Nội dung**: Ảnh quầy lễ tân hoặc mặt tiền phòng khám nha khoa hiện đại, sáng sủa.
   - **Tỉ lệ & nền**: `3:2` hoặc `16:10` (`1200x800` px), ảnh chụp đầy khung (không cần trong suốt).
   - **Cách nạp khuyến nghị**: Upload trực tiếp qua trang **Quản lý cơ sở** (`/admin/branches`) khi sửa từng cơ sở để hệ thống tự lưu và gán `branches.image_url`, **HOẶC** đặt file vào `c:\SEP490_G26\uploads\images\branch_cn01.jpg` (và `branch_cn02.jpg`) để Giai đoạn B gán thẳng vào `branches.image_url`.
   - **Kiểm tra hiển thị**: Mở `/home`, `/branches`, `/admin/branches` -> F12 Network kiểm tra request `/uploads/images/...` trả HTTP `200`, ảnh không bị cắt lệch (`object-fit: cover`).

3. **Ảnh 6–8 Dịch vụ nha khoa (`uploads/services/...`)**
   - **Nội dung**: Ảnh minh họa đúng dịch vụ (Implant, Niềng răng trong suốt/mắc cài, Bọc răng sứ thẩm mỹ, Tẩy trắng răng Laser, Nhổ răng khôn, Điều trị tủy, Lấy cao răng siêu âm, Khám tổng quát).
   - **Tỉ lệ & nền**: `4:3` (`800x600` px), không cần trong suốt.
   - **Cách nạp khuyến nghị**: Upload qua UI `/admin/services` (controller `AdminServiceCatalogController` kiểm tra cả magic bytes JPG/PNG/WebP và tự sinh UUID lưu vào `uploads/services/`) **HOẶC** đặt sẵn vào `uploads/services/` theo đúng tên trong bảng để script Giai đoạn B mapping vào `services.image_url`.
   - **Kiểm tra hiển thị**: Mở `/services`, `/services/{id}`, `/admin/services` -> ảnh hiển thị rõ, không hiện ô icon thay thế.

4. **Ảnh Tin tức nha khoa (`uploads/news/...`)**
   - **Nội dung**: Ảnh bài viết kiến thức chăm sóc răng miệng, công nghệ cấy ghép Implant, niềng răng, chăm sóc răng trẻ em.
   - **Tỉ lệ & nền**: `16:9` (`1200x675` px), ảnh ngang chuẩn editorial.
   - **Đặt tên & Thư mục**: Đặt vào `uploads/news/` (hoặc upload qua `/admin/news` mục Ảnh đại diện bài viết -> DB cập nhật `news_articles.thumbnail`).
   - **Kiểm tra hiển thị**: Mở `/home` (Section Tin tức nổi bật), `/news`, `/news/{slug}` -> F12 Network đạt `200 OK`.

5. **Ảnh X-quang NanoPix (`uploads/nanopix/...`) & Ảnh Labo (`uploads/labo/...`)**
   - **Nội dung**: Phim X-quang nha khoa toàn hàm (Panoramic) / cận chóp đã che tên bệnh nhân; ảnh mẫu hàm răng sứ / phiếu chỉ định Labo.
   - **Tỉ lệ & nền**: Phim X-quang ưu tiên tỉ lệ ngang `16:9` hoặc `3:2` nền tối; ảnh Labo tỉ lệ `4:3`.
   - **Đặt tên & Thư mục**: Chép vào `uploads/nanopix/` và `uploads/labo/`. (Lưu ý: 2 thư mục này chỉ xem được khi **đã đăng nhập tài khoản đúng quyền** như `ADMIN` hoặc `BAC_SI` thông qua `SecuredUploadResourceController`).
   - **Cập nhật DB**: Script Giai đoạn B sẽ gắn đường dẫn `/uploads/nanopix/<file>` vào `nanopix_xray_sync_logs.image_url` và `/uploads/labo/<file>` vào `labo_orders.image_url`.
   - **Kiểm tra hiển thị**: Đăng nhập bằng `admin` hoặc `bs01`, mở `/xray` và `/labo` -> kiểm tra ảnh hiển thị bình thường trong Modal Viewer, không lỗi `403` / `404`.
