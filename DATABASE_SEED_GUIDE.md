# HƯỚNG DẪN KHỞI TẠO DỮ LIỆU DATABASE (DATABASE SEED GUIDE) - CV-DCMS

Tài liệu này hướng dẫn chi tiết kiến trúc khởi tạo dữ liệu (Database Seed Data), sự phân tách giữa **Master Data (Production)** và **Demo/Transaction Data (Staging/UAT)**, thứ tự chạy các script SQL, và danh sách tài khoản kiểm thử cho hệ thống **Cường Việt Dental Clinic Management & Booking System (CV-DCMS)**.

---

## 1. Kiến trúc Khởi tạo Dữ liệu Chuẩn trong CV-DCMS

Hệ thống CV-DCMS sử dụng **Spring Boot 3 + JPA/Hibernate + Flyway Migration + MySQL 8.0** với cơ chế khởi tạo dữ liệu 2 tầng rõ ràng, đảm bảo **không xung đột**, **không nhân bản dữ liệu** khi khởi động lại ứng dụng:

| Thành phần | Vị trí File | Cơ chế chạy | Mục đích & Phạm vi dữ liệu |
| :--- | :--- | :--- | :--- |
| **Tầng 1A: Flyway Master Data** | `src/main/resources/db/migration/V111__initial_master_data.sql` | Tự động chạy **1 lần duy nhất** khi ứng dụng khởi động nhờ Flyway (`spring.flyway.enabled=true`) | Khởi tạo **Master Data bắt buộc cho Production**: 6 Roles, 61 Permissions, Role-Permissions, 2 Chi nhánh, Phòng & Ghế răng, Khung giờ ca khám, Ca trực nhân sự, Cấu hình thuế, Tài khoản ngân hàng VietQR, Hạng thẻ thành viên, 14 mã bệnh ICD-10, 9 Nhóm dịch vụ, 12 Dịch vụ nha khoa, 3 Quy trình khám chuẩn (Clinical Workflows), 7 Thuốc, 4 Nhà cung cấp, 11 Vật tư, Tồn kho đầu kỳ = 0 và Tài khoản `admin` mặc định. |
| **Tầng 1B: RBAC Auto-Sync** | `src/main/java/com/cvdcms/config/PermissionDataInitializer.java` | `ApplicationRunner` chạy khi Spring Boot khởi động | Tự động đảm bảo 6 Role hệ thống (`ADMIN`, `BAC_SI`, `LE_TAN`, `QUAN_LY_KHO`, `KE_TOAN`, `BENH_NHAN`) và 61 Permission luôn tồn tại và đồng bộ quyền mặc định nếu bảng `role_permissions` của Role đó đang trống. |
| **Tầng 2A: Bộ Seed Tổng hợp (Master + Demo)** | `src/main/resources/seed_full_data.sql` | Chạy chủ động qua MySQL CLI / MySQL Workbench khi cần nạp dữ liệu Demo/UAT | Bao gồm toàn bộ Master Data + Nhân sự 2 cơ sở (`bacsi01`..`04`, `letan01`..`02`, `quanlykho01`..`02`, `ketoan01`..`02`) + 4 Bệnh nhân Portal (`benhnhan01`..`04`) + Lô hàng, Phiếu nhập/xuất kho, Lịch hẹn, Ca khám, Bệnh án điện tử (EMR), X-Quang NanoPix, Đơn thuốc, Labo, CSKH, Hóa đơn, Phiếu thu/chi, Bảng lương và Tin tức. |
| **Tầng 2B: Bộ Seed Theo Module (8 File)** | `database/seeds/01_*.sql` → `08_*.sql` | Chạy từng phần theo nhu cầu triển khai | Cho phép DevOps chọn chỉ nạp Master Data (`01` → `05`) hoặc nạp thêm dữ liệu Demo (`06` → `08`). |

> **Lưu ý cấu hình `application.properties`:**
> - `spring.sql.init.mode=never` được giữ nguyên để tránh Spring Boot tự chạy `data.sql` chồng lên Flyway mỗi lần restart.
> - Tuy nhiên, cả `src/main/resources/data.sql` và `database_seed_full_scenarios.sql` đều đã được chuẩn hóa 100% theo Schema thực tế (`ON DUPLICATE KEY UPDATE`), nên nếu chạy thủ công hoặc bật `spring.sql.init.mode=always` đều không phát sinh lỗi.

---

## 2. Phân biệt Master Data (Production) và Demo Data (Testing/Staging)

### 2.1. Master Data (Dùng khi Deploy Production thực tế)
- **Tự động nạp qua:** `src/main/resources/db/migration/V111__initial_master_data.sql` (hoặc chạy các file `01_roles_permissions.sql` → `04_services_workflows.sql`).
- **Đặc điểm an toàn Production:**
  - **Không** tạo lịch hẹn giả, ca khám giả, hóa đơn giả hay phiếu thu/chi giả.
  - **Không** ghi đè mật khẩu của tài khoản `admin` nếu quản trị viên đã đổi mật khẩu trên Production (`ON DUPLICATE KEY UPDATE is_active = VALUES(is_active)`).
  - Đầy đủ danh mục để toàn bộ Dropdown, Bộ lọc, Trang chủ, Bảng giá, Đặt lịch trực tuyến và Quy trình khám hoạt động ngay sau khi deploy.

### 2.2. Demo Data (Dùng cho môi trường Demo, UAT, Đồ án / Báo cáo)
- **Nạp qua:** `src/main/resources/seed_full_data.sql` hoặc bộ `database/seeds/01_*.sql` → `08_*.sql`.
- **Bao gồm các kịch bản nghiệp vụ khép kín:**
  1. **Ca khám hoàn tất (`VIS-SEED-001` - Bệnh nhân Phạm Quốc Bảo):** Khám lâm sàng → Chụp X-Quang Panorama (`CLS-SEED-001`) → Tiểu phẫu nhổ răng khôn R48 bằng máy Piezotome → Kê đơn thuốc (`TOA-SEED-001`) → Thanh toán đủ hóa đơn `2.300.000đ` (`HD-SEED-001` / `PT-SEED-001`) → Hoa hồng bác sĩ (`300.000đ`) → Ticket CSKH hỏi thăm hậu phẫu (`CSKH-SEED-001`).
  2. **Ca khám đang điều trị & cảnh báo dị ứng thuốc (`VIS-SEED-002` - Bệnh nhân Lê Hoàng Phương Thảo):** Tiền sử dị ứng nhẹ **Penicillin** → Điều trị tủy răng R36 lần 1 → Kê đơn kháng sinh thay thế an toàn **Dalacin C 300mg (Clindamycin)** (`TOA-SEED-002`) → Hóa đơn tạm ứng 1 phần `500.000đ / 1.500.000đ` (`HD-SEED-002` - `PARTIAL`) → Ticket CSKH chuyển Bác sĩ phản hồi chuyên môn (`CSKH-SEED-002` - `ESCALATED_TO_DOCTOR`).
  3. **Ca khám bệnh nhi đang chờ (`VIS-SEED-003` - Bé Phạm Gia Huy):** Hồ sơ người thân (`CHILD`) liên kết dưới tài khoản phụ huynh `benhnhan01`, hiển thị trực tiếp trên Hàng đợi phòng chờ (`KB-002`) và màn hình TV.
  4. **Quản lý Kho & Labo:** Tồn kho đa chi nhánh, lô hàng còn hạn, phiếu nhập kho (`PNK-SEED-001` tổng `16.750.000đ`), phiếu xuất kho nội bộ (`PXK-SEED-001`), đơn đặt Labo răng sứ Zirconia (`LABO-SEED-001`).

---

## 3. Hướng dẫn Chạy Seed Data

### Cách 1: Tự động khi khởi động Spring Boot (Khuyên dùng cho Production)
Khi khởi động ứng dụng Spring Boot kết nối tới database `cv_dcms_db`, Flyway sẽ tự động thực thi `V111__initial_master_data.sql` và `PermissionDataInitializer` sẽ đồng bộ toàn bộ Role & Permission.

### Cách 2: Nạp Full Master + Demo Data (Khuyên dùng cho Demo / Kiểm thử toàn diện)
Chạy trực tiếp file `src/main/resources/seed_full_data.sql` trên MySQL 8.0:

```bash
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < src/main/resources/seed_full_data.sql
```

### Cách 3: Chạy theo từng file thành phần trong `database/seeds/`
Thứ tự thực thi chuẩn (tuân thủ nghiêm ngặt khóa ngoại `FOREIGN KEY`):

```bash
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < database/seeds/01_roles_permissions.sql
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < database/seeds/03_branches_config.sql
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < database/seeds/02_users_staff.sql
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < database/seeds/04_services_workflows.sql
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < database/seeds/05_inventory.sql
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < database/seeds/06_patients_demo.sql
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < database/seeds/07_appointments_visits_demo.sql
mysql -u root -p --default-character-set=utf8mb4 cv_dcms_db < database/seeds/08_finance_demo.sql
```

> **Tính chất Idempotent (Chạy nhiều lần không lỗi):** Tất cả các câu lệnh `INSERT` trong các file trên đều sử dụng `ON DUPLICATE KEY UPDATE` hoặc `INSERT IGNORE` dựa trên `PRIMARY KEY` và `UNIQUE KEY`, đảm bảo chạy lại nhiều lần không bao giờ gây lỗi `Duplicate entry`.

---

## 4. Danh sách Tài khoản Kiểm thử (Mật khẩu chung: `admin123`)

Toàn bộ tài khoản trong hệ thống sử dụng mật khẩu mặc định: **`admin123`**
*(Mã hóa BCrypt cost-10 chuẩn `Spring Security BCryptPasswordEncoder`: `$2a$10$5s12DPJUuk1DmfTPTZeAUOTBXAxRXBHOzcYWLapGXz3mqXFImJlIe`)*

| STT | Username | Mật khẩu | Vai trò (`Role`) | Họ và tên | Cơ sở làm việc / Hồ sơ |
| :-: | :--- | :--- | :--- | :--- | :--- |
| 1 | **`admin`** | `admin123` | `ADMIN` | Nguyễn Việt Cường | Quản trị toàn hệ thống (Cơ sở 1 & 2) |
| 2 | **`bacsi01`** | `admin123` | `BAC_SI` | ThS.BS Lê Hoàng Nam | Cơ sở 1 - Cầu Giấy (Trưởng khoa Cấy ghép & Tiểu phẫu) |
| 3 | **`bacsi02`** | `admin123` | `BAC_SI` | BS.CKI Trần Mai Hoa | Cơ sở 1 - Cầu Giấy (Chỉnh nha & Nha khoa Thẩm mỹ) |
| 4 | **`letan01`** | `admin123` | `LE_TAN` | Hoàng Thu Trang | Cơ sở 1 - Cầu Giấy (Trưởng nhóm Lễ tân & CSKH) |
| 5 | **`quanlykho01`** | `admin123` | `QUAN_LY_KHO` | Đỗ Minh Quân | Cơ sở 1 - Cầu Giấy (Thủ kho Vật tư & Dược) |
| 6 | **`ketoan01`** | `admin123` | `KE_TOAN` | Vũ Kim Ngân | Cơ sở 1 - Cầu Giấy (Kế toán trưởng chi nhánh) |
| 7 | **`bacsi03`** | `admin123` | `BAC_SI` | BSCKII Nguyễn Quốc Tuấn | Cơ sở 2 - Đống Đa (Phục hình sứ & Nội nha) |
| 8 | **`bacsi04`** | `admin123` | `BAC_SI` | ThS.BS Phạm Thúy Nga | Cơ sở 2 - Đống Đa (Nha khoa Tổng quát & Trẻ em) |
| 9 | **`letan02`** | `admin123` | `LE_TAN` | Lê Phương Linh | Cơ sở 2 - Đống Đa (Chuyên viên Lễ tân & CSKH) |
| 10 | **`quanlykho02`** | `admin123` | `QUAN_LY_KHO` | Bùi Trọng Đạt | Cơ sở 2 - Đống Đa (Chuyên viên Quản lý kho) |
| 11 | **`ketoan02`** | `admin123` | `KE_TOAN` | Trần Thu Hà | Cơ sở 2 - Đống Đa (Kế toán Thu ngân CN2) |
| 12 | **`benhnhan01`** | `admin123` | `BENH_NHAN` | Phạm Quốc Bảo | Bệnh nhân Portal (Có hồ sơ chính `BN-SEED-001` & hồ sơ con `BN-SEED-005`) |
| 13 | **`benhnhan02`** | `admin123` | `BENH_NHAN` | Lê Hoàng Phương Thảo | Bệnh nhân Portal (Hồ sơ `BN-SEED-002` - Cơ địa dị ứng Penicillin) |
| 14 | **`benhnhan03`** | `admin123` | `BENH_NHAN` | Trần Đình Trọng | Bệnh nhân Portal (Hồ sơ `BN-SEED-003` - Hạng thẻ Bạc, đang lắp răng sứ) |
| 15 | **`benhnhan04`** | `admin123` | `BENH_NHAN` | Nguyễn Ngọc Minh Anh | Bệnh nhân Portal (Hồ sơ `BN-SEED-004` - Có lịch hẹn khám định kỳ) |
