# CV-DCMS – KẾ HOẠCH KIỂM TRA, HOÀN THIỆN VÀ SỬA LỖI ĐẶT LỊCH – TIẾP ĐÓN BỆNH NHÂN (GIAI ĐOẠN 1)

- **Ngày lập kế hoạch:** 20/11/2026
- **Nhánh Git:** `hieudx-test`
- **Tài liệu đối chiếu:** [CV_DCMS_APPOINTMENT_RECEPTION_AUDIT.md](file:///C:/SEP490_G26/CV_DCMS_APPOINTMENT_RECEPTION_AUDIT.md) + kiểm tra trực tiếp toàn bộ Entity, Repository, Service, Controller, SecurityConfig, Migration (`src/main/resources/db/migration` và `database/migrations`), Template, JS và Test.
- **Trạng thái:** **GIAI ĐOẠN 1 – CHƯA SỬA CODE / CHƯA SỬA TEST / CHƯA CHẠY MIGRATION.** Chờ người dùng phê duyệt các điểm nghiệp vụ & kiến trúc trước khi bắt đầu Giai đoạn 2 (P0).

---

## 1. ĐỐI CHIẾU BÁO CÁO KIỂM TOÁN VỚI SOURCE CODE & GIT HIỆN TẠI

### 1.1. Hiện trạng Git đang tồn tại trước khi sửa
Kiểm tra `git status` và `git diff` cho thấy working tree hiện có:
- **3 file đã sửa từ các phiên trước (giữ nguyên chưa đụng tới):**
  - `src/main/java/com/cvdcms/service/AppointmentSlotService.java`: thêm `@Transactional(readOnly = true)` cho `available(...)` (`+1` dòng).
  - `src/test/java/com/cvdcms/receptionappointment/ReceptionAppointmentRegressionTest.java`: thêm `lenient()` trên stub `rules.sessionEnded(...)` (`1` dòng).
  - `src/test/java/com/cvdcms/service/AppointmentFlowRemediationTest.java`: mở comment kích hoạt toàn bộ lớp test (`19` test case, `+614/-606`).
- **1 file báo cáo chưa track:** `CV_DCMS_APPOINTMENT_RECEPTION_AUDIT.md`.

### 1.2. Kết quả đối chiếu từng nhận định kiểm toán với source code thực tế

| # | Nhận định trong Audit | Kết quả đối chiếu source thực tế | Ghi chú / Điểm phát hiện bổ sung |
|---|---|---|---|
| 1 | Guest nhập SĐT tồn tại gán thẳng vào `existingProfiles.get(0)` (`PublicBookingRestController:467-497`) | **XÁC MINH ĐÚNG 100%** | Phát hiện thêm **lỗi sập HTTP 500**: `PatientProfile` có ràng buộc `uk_patient_phone UNIQUE (phone_number)` (`V18` / `PatientProfile.java:23-25`). Nếu SĐT đã thuộc một `PatientProfile` khác (VD hồ sơ phụ thuộc `0976583211` nhưng `User.phoneNumber` của tài khoản chính là số khác), `createPatientProfileForUser` (`lines 642-662`) sẽ insert trùng SĐT và dính `ConstraintViolationException`. Ngoài ra hệ thống **chưa có bất kỳ Entity `Lead`/`ConsultationRequest` nào**, và `CustomerCareTask.patientProfile` là `nullable = false` (`CustomerCareTask.java:36-38`). |
| 2 | `ReceptionAccess:50-52` gán `admin=true, canManage=true` khi `branchIds.isEmpty()` | **XÁC MINH ĐÚNG 100%** | Kiểm tra thêm: `PatientAccess:87-91` mở toàn bộ chi nhánh active cho nhân viên thiếu chi nhánh; `ChatServiceImpl:330` trả `allActive` cho phép theo dõi WebSocket mọi chi nhánh. |
| 3 | `DEFAULT_CONCURRENT_ARRIVALS_PER_DOCTOR = 3` (`AppointmentSlotService:55`) & lịch linh hoạt `expectedTime = null` không chiếm slot giờ | **XÁC MINH ĐÚNG 100%** | Kiểm tra thêm dữ liệu tài nguyên DB (xem Mục 2): DB **chưa có** cột `provider_time`/`chair_time`/`assistant_time` hay lịch phân ghế/trợ lý cho bác sĩ. |
| 4 | Bệnh nhân đến sớm `15:44` (hẹn `16:00`) bị gán `LATE_BOOKING` & tụt xuống Nhóm 4 | **XÁC MINH ĐÚNG 100%** | Kiểm tra thêm lỗi chính xác phần giây: `ReceptionService:662-669` và `ReceptionAppointmentService:665` so sánh `LocalDateTime` mang nguyên giây → **`16:15:01` bị đánh trễ sang Nhóm 4** và **`16:30:01` bị từ chối/đánh NO_SHOW**, không nhất quán theo từng phút biên. Đồng thời `V114:105` backfill mọi check-in `<= +15p` thành `is_booked_and_on_time = 1`, lệch với runtime. |
| 5 | Flyway trùng `V107` (`V107__customer_care_realtime_notifications.sql` & `V107__doctor_cover_grants.sql`) | **XÁC MINH ĐÚNG 100%** | Phát hiện thêm tồn tại 2 thư mục migration: `src/main/resources/db/migration` (Flyway active: `V100`–`V114`, trùng `V107`) và `database/migrations` (manual historical SQL: `V1`–`V18`, trùng `V7, V8, V11, V13, V14`). Trong `database/migrations/V7` đã có `uk_ft07_visit_appointment`, nên cần preflight kiểm tra trước khi thêm ở Flyway `V115+`. |
| 6 | `ReExaminationServiceImpl` bỏ qua `ensureBookable` & fallback khi `availableIntervals()` rỗng | **XÁC MINH ĐÚNG 100%** | Kiểm tra thêm: `assertAppointmentMutable` (`467-470`) không chặn `AppointmentStatus.NO_SHOW`, cho phép dời lịch `NO_SHOW` trái quy tắc; mã `TK...` dùng 4 số `Math.random()`. |
| 7 | Check-in gán thẳng bác sĩ cũ đang nghỉ/hết ca; `LeaveRequestService` auto-cancel không lock/history | **XÁC MINH ĐÚNG 100%** | Ngoài `LeaveRequestService:318-386` (không lock, không history, không hủy reminder, nguy cơ AB-BA deadlock với Booking), `confirmAndCheckIn` cho lịch `PENDING` lại bị `ensureBookable` chặn chết tại quầy khi bác sĩ đặt trước đã nghỉ. |
| 8 | Walk-in thiếu check giờ đóng cửa, nuốt exception ở `autoAssignDoctorOnCheckIn`, bỏ qua limit `8` | **XÁC MINH ĐÚNG 100%** | Walk-in dùng `ReceptionService.checkInWalkIn` không check `isActive`, `workingDays`, `openingTime`, `closingTime`; có thể rơi trạng thái `primaryDoctor == null` không cảnh báo, không tự cấp số. |
| 9 | Nút Priority chỉ thêm `[ƯU TIÊN]` vào notes; `APPOINTMENT_VIEW` báo được `EMERGENCY`; `ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING = true` | **XÁC MINH ĐÚNG (có nuance)** | Trên `check-in.html` bản hiện tại thậm chí chưa render checkbox `priority`/`emergency`, nhưng controller `ReceptionCheckInController:75,94,145` vẫn nhận tham số `priority` & `emergency`, còn `dispatch.html:677,725` gửi `priority` ẩn. `QueueWorkflowController` cho mọi quyền `APPOINTMENT_VIEW` gọi `POST /reception/queue/{id}/EMERGENCY`. |
| 10 | 4 màn hình/API hàng đợi sắp xếp lệch nhau & `WAITING_RESULT` chen vào trên Nhóm 2 | **XÁC MINH ĐÚNG 100%** | Cần hợp nhất hoàn toàn giữa `ClinicQueueCoordinatorService`, `QueueWorkflowService`, `DispatchService`, `ReceptionService` và `DoctorPatientRestController` (`/daily-visits` & `/waiting-queue`) + `VisitRepository`. |
| 11 | TV công cộng `/tv-display` & `/reception/queue/tv/data` lộ họ tên đầy đủ, `dob`, `doctorName` | **XÁC MINH ĐÚNG 100%** | Xác nhận cả `queue-tv.html:429-443`, `reception-queue-tv.js:186-192` (và TTS dòng `133-134` đọc tên bác sĩ) đều hiển thị/đọc dữ liệu cá nhân trực tiếp, dù `maskName` đã được viết sẵn nhưng bỏ không gọi. |
| 12 | `ReceptionAppointmentService.create` lỗi phân buổi `EVENING` khi `expectedTime > 12:00`; `quickStatus` không dọn visit/ticket; `ConflictDetection` báo giả `COMPLETED` | **XÁC MINH ĐÚNG 100%** | Sẽ xử lý gọn trong P1 đồng bộ với quy tắc đặt lịch/tiếp đón. |

---

## 2. ĐÁNH GIÁ DỮ LIỆU TÀI NGUYÊN & SỨC CHỨA TRONG DATABASE HIỆN TẠI (MỤC 3.1.5–3.1.7)

Sau khi kiểm tra trực tiếp toàn bộ Entity trong `com.cvdcms.entity` và migrations (`V100`–`V114`, `V1`–`V18`):

1. **Những gì hệ thống ĐÃ lưu trong DB:**
   - **Dịch vụ (`services`):** `estimated_duration_minutes INT NOT NULL DEFAULT 30` (`Service.java:76-77`), `is_bookable_online`, `status`, `requires_tooth`, `is_paraclinical`.
   - **Lịch hẹn (`appointments`):** `branch_id`, `doctor_id`, `service_id`, `appointment_date`, `preferred_session`, `expected_time`, `duration_minutes DEFAULT 30`, `expected_end_time`, `status`.
   - **Ca trực & Phân công bác sĩ (`work_shifts`, `staff_schedules`, `staff_branch_assignments`, `leave_requests`):** Lưu ca trực cụ thể theo `(staff_id, branch_id, work_date, shift_id)` và đơn nghỉ theo ngày hoặc theo `shift_id`.
   - **Phòng & Ghế (`rooms`, `dental_chairs`):** `rooms` thuộc `branch_id` (`status = ACTIVE/INACTIVE`), `dental_chairs` thuộc `(branch_id, room_id)` với `status = AVAILABLE / IN_USE / MAINTENANCE`. **Lưu ý:** `DentalChairStatusConverter` chuyển legacy `CLEANING` thành `AVAILABLE`.
   - **Sức chứa theo phiên (`appointment_session_policies`, `appointment_session_windows`):** Giới hạn `branch_capacity` (mặc định 24) và `doctor_capacity` (mặc định 10).
   - **Lượt khám (`visits`):** Gắn `primary_doctor_id`, `room_id`, `chair_id` khi tiếp đón/điều phối (lưu trạng thái thực tế tại quầy, **không dùng để đặt trước từ xa**).

2. **Những dữ liệu CÒN THIẾU (chưa tồn tại trong Database):**
   - **Chưa có phân tách Provider Time / Chair Time / Assistant Time** trên `services` hoặc bảng trung gian nào.
   - **Chưa có ánh xạ đặt trước `appointments.chair_id` hoặc `appointments.room_id`**, và **`staff_schedules` không gắn `room_id` hay `chair_id`** (ghế chỉ được gán tại bước Check-in/Điều phối trong ngày).
   - **Chưa có cấu hình trợ lý bắt buộc theo dịch vụ (`requires_assistant`, số trợ lý tối thiểu) và chưa có bảng ánh xạ `assistant` đi kèm ghế/bác sĩ khi đặt lịch** (`PerformedService.assistant` chỉ lưu trợ lý thực tế *sau khi đã vào khám*).

3. **Phương án tối thiểu tương thích kiến trúc hiện tại (không bịa mô hình giả định):**
   - Áp dụng **thời lượng dịch vụ thực tế `Service.estimatedDurationMinutes`** (mặc định an toàn `30 phút` nếu `service == null`).
   - **Loại bỏ `DEFAULT_CONCURRENT_ARRIVALS_PER_DOCTOR = 3`**: Mặc định `1` bác sĩ chỉ phục vụ **1 cuộc hẹn tại cùng khoảng thời gian nửa mở `[start, end)`** (`maxConcurrentPerDoctorSlot = 1`).
   - **Ràng buộc tài nguyên ghế thực tế ở cấp chi nhánh (Branch Active Chair Ceiling):** Dù chưa gán cứng `chair_id` trước trên từng `Appointment`, tổng số cuộc hẹn có khung giờ giao nhau `[start, end)` (và lịch linh hoạt quy đổi cùng buổi) tại một chi nhánh trong cùng một thời điểm **không được vượt quá `min(số bác sĩ trực hợp lệ, số ghế nha đang hoạt động của chi nhánh)`**! (Nếu chi nhánh chưa cấu hình ghế `chairs == 0`, fallback an toàn theo số bác sĩ trực hợp lệ để không làm gãy dữ liệu seed chưa khai báo ghế; nếu chi nhánh *đã cấu hình ghế* thì chặn tuyệt đối khi `activeOverlapCount >= activeChairsCount`).
   - **Trợ lý (`PHU_TA`):** Hiện DB chưa có trường dữ liệu xác định dịch vụ nào cần trợ lý (`requires_assistant`). Do đó ở giai đoạn này **ghi nhận thiếu dữ liệu cấu hình trợ lý ở tầng Service**, không tạo giả logic trợ lý; nếu sau này Admin cần kiểm soát trợ lý khi đặt lịch, chỉ cần bổ sung `requires_assistant TINYINT(1) DEFAULT 0` trên `services` rồi đếm `StaffSchedule` có role `PHU_TA` theo đúng ca trực.

---

## 3. KẾ HOẠCH CHI TIẾT SỬA LỖI THEO TỪNG NHÓM (GIAI ĐOẠN 2 – P0 & GIAI ĐOẠN 3 – P1)

### PHẦN A – NHÓM P0 (BẮT BUỘC SỬA TRƯỚC TIÊN)

---

#### [P0-1] Guest nhập SĐT đã tồn tại, tạo Lead riêng cho Guest mới & Chống spam/IDOR (Mục 4.1)

- **Bằng chứng hiện tại:**
  - `SecurityConfig.java:57-61` bỏ qua CSRF cho `/api/**` (gồm `/api/public/booking`); lines `117-121` cho phép `permitAll()` toàn bộ `/api/public/booking/**`.
  - `PublicBookingRestController.java:467-497`: Khi `!loggedInPatient`, tìm `userRepository.findByPhoneNumber(cleanPhone)` → lấy `existingProfiles.get(0)` gắn thẳng `Appointment` `PENDING` lên hồ sơ người khác. Nếu chưa có User, gọi `createGuestPatientUser` + `createPatientProfileForUser` (`622-662`) tạo tài khoản + hồ sơ bệnh nhân thật và khóa luôn slot bác sĩ. Nếu SĐT thuộc hồ sơ phụ thuộc (không khớp `User.phoneNumber`), insert trùng `uk_patient_phone` gây lỗi 500.
  - `PublicBookingRestController.QuickBookingForm` (`107-118`) không có `requestKey`; không có rate limit.
- **Nguyên nhân gốc:** Dùng chung một endpoint tạo `Appointment` chính thức cho cả bệnh nhân đã xác thực lẫn khách vãng lai chưa đăng nhập, dựa trên phone number thô làm khóa tra cứu danh tính.
- **Mức độ ưu tiên:** **P0 – Critical Security & Data Integrity**.
- **Giải pháp đề xuất:**
  1. **Kiểm tra trùng SĐT khi Guest gửi form (cả `users.phone_number` lẫn `patient_profiles.phone_number`):**
     - Nếu `cleanPhone` **đã tồn tại** ở `users` HOẶC `patient_profiles`:
       - **TUYỆT ĐỐI KHÔNG** tạo `Appointment`, **KHÔNG** liên kết `PatientProfile`, **KHÔNG** tạo `User`/`PatientProfile` mới.
       - Trả về HTTP `409 CONFLICT` với flag `loginRequired: true` và **thông báo trung lập (không tiết lộ tên, ngày sinh hay chi tiết tài khoản/hồ sơ)**: `"Số điện thoại này đã được đăng ký trong hệ thống. Vui lòng đăng nhập tài khoản của bạn để tiếp tục đặt lịch và chọn đúng hồ sơ người khám."` kèm URL `/login`.
  2. **Với Guest có SĐT CHƯA từng tồn tại trong hệ thống (Mục 4.1B):**
     - **KHÔNG** tạo `User`, **KHÔNG** tạo `PatientProfile`, và **KHÔNG** tạo `Appointment` chính thức chiếm slot bác sĩ/ca trực!
     - Vì dự án **chưa có Entity Lead/Yêu cầu tư vấn** (`CustomerCareTask.patientProfile` bắt buộc `NOT NULL`), đề xuất bổ sung 1 bảng + Entity nhẹ độc lập:
       - **Bảng mới (`V115` / `V116`):** `booking_consultation_requests`:
         `request_id (PK), request_code (UK), request_key (VARCHAR(36) UK), branch_id (FK -> branches), preferred_doctor_id (FK NULL -> staff_profiles), service_id (FK NULL -> services), guest_name (VARCHAR(150)), guest_phone (VARCHAR(20)), preferred_date (DATE), preferred_session (VARCHAR(20)), preferred_time (TIME NULL), notes (VARCHAR(500)), status (NEW / CONTACTED / CONVERTED / CANCELLED), converted_appointment_id (BIGINT NULL FK -> appointments), handled_by_user_id (FK NULL -> users), created_at, updated_at`.
       - Index: `(status, branch_id, created_at)`, `(guest_phone, created_at)`.
     - **Luồng vận hành:** Khi Guest chưa có tài khoản gửi form, lưu vào `booking_consultation_requests` (`status = NEW`, idempotent theo `requestKey`), hiển thị thông báo rõ: *"Đã tiếp nhận yêu cầu đặt lịch tư vấn (#Code). Lễ tân cơ sở sẽ gọi xác nhận và giữ lịch chính thức cho Quý khách"*. Không chiếm `branchCapacity`/`doctorCapacity`/`slot`.
     - Hiển thị hàng chờ "Yêu cầu đặt lịch từ khách mới" nhẹ trên màn hình `/reception/appointments` để lễ tân bấm "Xác nhận & Tạo lịch hẹn chính thức" (khi đó mới tạo hồ sơ `PatientProfile` + `Appointment` chuẩn qua `ReceptionAppointmentService.create`).
  3. **Bảo vệ Endpoint & Idempotency:**
     - Bắt buộc trường `requestKey` (UUID) ở `QuickBookingForm` (nếu client cũ/API gọi từ Drawer `portal/home.html` gửi kèm UUID lưu `sessionStorage` giống Portal; phát hiện key trùng trả lại kết quả cũ nếu cùng nội dung hoặc 409 nếu khác nội dung).
     - Rate limit in-memory token bucket / sliding window trên `/api/public/booking` (chặn IP spam > X request/phút và chặn SĐT gửi liên tiếp > Y yêu cầu `NEW` chưa xử lý trong 24h).
     - Với `loggedInPatient == true`: server **bắt buộc** dùng `PortalAccess.requireProfile(sessionAccount, form.getProfileId())` + **bỏ qua hoàn toàn** `fullName`/`phoneNumber` từ client; **khôi phục kiểm tra CSRF** hoặc dùng `CookieCsrfTokenRepository` trên `/api/public/booking` (Drawer gửi header `X-XSRF-TOKEN` đọc từ cookie/meta `_csrf` có sẵn trong `fragments/head.html:18-19`).
- **Thiết kế & Ảnh hưởng nếu thêm bảng mới:** Additive 100%, không chạm `appointments`, `visits`, `patient_profiles`, `customer_care_tasks`; Hibernate `ddl-auto=validate` hợp lệ ngay sau migration Flyway.
- **File dự kiến thay đổi:**
  - Entity/Repo mới: `BookingConsultationRequest.java`, `BookingConsultationRequestRepository.java` + Flyway migration mới.
  - Service/Controller: `PublicBookingRestController.java`, `PublicBookingLeadService` (hoặc tích hợp gọn trong `ReceptionAppointmentService` phần convert), `SecurityConfig.java` (CSRF), `portal/home.html` (`requestKey` + CSRF header + xử lý 409 `loginRequired` UX), `reception/appointments/dashboard.html` & `reception-appointments.js` (mục xem & chuyển đổi yêu cầu từ khách mới).
- **Rủi ro:** Người dùng chưa đăng nhập sẽ không thấy lịch ngay trong `appointments` cho tới khi đăng nhập hoặc lễ tân xác nhận — đúng chính xác nghiệp vụ Mục 4.1B.
- **Test cần có:** Test mục 8.1 đầy đủ (Guest SĐT mới -> tạo Lead `NEW`, không tạo `Appointment`/`Visit`/`User`; Guest SĐT có User -> 409 ẩn thông tin; Guest SĐT chỉ có `PatientProfile` -> 409 ẩn thông tin; Logged-in tự đặt bản thân/phụ thuộc; Logged-in giả `profileId`; Request lặp `requestKey`; Spam quá rate limit; Concurrent 2 request trùng SĐT/key).

---

#### [P0-2] Sửa lỗ hổng leo thang quyền `ReceptionAccess` & Branch Scope (Mục 4.2)

- **Bằng chứng hiện tại:**
  - `ReceptionAccess.java:50-52`: `if (!admin && branchIds.isEmpty()) return new Scope(user, true, true, List.of());` → tự phong `admin = true, canManage = true`!
  - `PatientAccess.java:87-91`: `if (!admin && accessibleBranchIds.isEmpty() && branchRepository != null)` → tự add **tất cả chi nhánh active** thay vì chặn!
  - `ChatServiceImpl.java:330`: `resolveStaffAllowedBranches` cuối hàm `return allActive;` khi không tìm được chi nhánh của nhân viên → mở toang `/topic/queue/branch/*` và `/topic/chat/branch/*` cho nhân sự thiếu chi nhánh.
- **Nguyên nhân gốc:** Lập trình "fallback cho tiện test" khiến fail-open (cho phép toàn quyền) thay vì fail-closed (403 Forbidden) khi dữ liệu gán chi nhánh bị thiếu.
- **Mức độ ưu tiên:** **P0 – Critical Authorization Bypass**.
- **Giải pháp đề xuất:**
  - **Admin thực sự (`hasRole("ADMIN") || users.hasActiveAdminRole(...)`):** Giữ quyền `admin = true`.
  - **Nhân viên không phải Admin nếu chưa có chi nhánh (`user.getBranch() == null` VÀ `assignments` rỗng):**
    - `ReceptionAccess.require`: **Ném `ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản chưa được phân công chi nhánh làm việc.")`**.
    - `PatientAccess.require`: **Xóa bỏ đoạn fallback lines `87-91`**, để `accessibleBranchIds.isEmpty()` rơi xuống `line 103` ném `403 FORBIDDEN`.
    - `ChatServiceImpl.resolveStaffAllowedBranches`: Khi không phải ADMIN và không xác định được branch nào (`user.getBranch()`, conversation hoặc careTask? Lưu ý chỉ nên tin `user.getBranch()` + `StaffBranchAssignment` ACTIVE, tránh suy đoán từ `careTask` cũ), trả `List.of()` (chặn subscribe `/topic/queue/branch/*`).
  - Phân biệt chặt chẽ tại `ReceptionAccess`: `admin` vs nhân viên thuộc cơ sở (`scope.permits(branchId)`) vs tài khoản khóa/inactive (chặn ở `line 32`).
- **File dự kiến thay đổi:** `ReceptionAccess.java`, `PatientAccess.java`, `ChatServiceImpl.java`, `ReceptionAccessTest.java`, `BranchAccessServiceTest.java`.
- **Rủi ro:** Bất kỳ tài khoản nhân sự demo/seed nào thiếu cả `users.branch_id` lẫn `staff_branch_assignments` sẽ bị chặn 403 đúng như thiết kế bảo mật (cần kiểm tra nhanh dữ liệu test accounts trong test integration nếu có test dùng user `branch = null`!).
- **Test cần có:** Test chứng minh 4 nhóm tài khoản (`ADMIN`, Nhân viên có branch 1 cố truy cập branch 2 -> 403, Nhân viên không có branch -> 403 cả Check-in/Queue/Dispatch/Appointments/WS branch topic, Tài khoản bị vô hiệu hóa `isActive=false` hoặc `status!=ACTIVE` -> 403).

---

#### [P0-3] Khắc phục sức chứa lịch hẹn theo tài nguyên thực tế & Lịch hẹn linh hoạt (Mục 3.1, 3.2, 4.3)

- **Bằng chứng hiện tại:**
  - `AppointmentSlotService.java:55, 115-117, 297-300, 602, 725`: `DEFAULT_CONCURRENT_ARRIVALS_PER_DOCTOR = 3` (`@Value("${appointments.doctor-concurrent-per-slot:3}")`) → 1 bác sĩ nhận 3 lịch trùng nhau ở cùng `[16:00, 16:30)`.
  - `AppointmentSlotService.java:523-569` & `745`: Khi `expectedTime == null` (Linh hoạt), `resolveEffectiveInterval` trả `null` và `resolveFlexibly` trả `null` luôn ở `line 543` **trước khi chạm vào `lockAndValidateDoctorSlot`** (`line 567`)! Cho nên đặt lịch linh hoạt cho Bác sĩ A vẫn vượt qua mọi kiểm tra lock lịch sử dụng thời gian thực của Bác sĩ A.
  - `ReExaminationServiceImpl.java:129-143, 398-411, 521-565`: Bỏ qua `ensureBookable` và fallback sang `validateDoctorAvailabilityForReExam` khi bác sĩ không có ca trực hoặc đã đầy lịch.
  - `ReceptionAppointmentService.java:390-396`: Tự suy `expectedTime > 12:00` thành `AFTERNOON` ngay cả khi đặt `18:30` (`EVENING`).
  - `ReceptionAppointmentService.java:412` vs `AppointmentSlotService:1018`: Counter ép min duration `15` trong khi SlotService chấp nhận `5` (cần thống nhất quy tắc thời lượng).
- **Nguyên nhân gốc:** Dùng hằng số concurrency = 3 thay cho kiểm tra xung đột `[start, end)` 1-1 trên mỗi bác sĩ; chưa hạch toán lịch `expectedTime = null` vào quỹ thời gian khả dụng của bác sĩ/buổi; kênh tái khám đi đường vòng riêng.
- **Mức độ ưu tiên:** **P0 – Core Scheduling Correctness**.
- **Giải pháp đề xuất:**
  1. **Loại bỏ mặc định 3 lịch/khung (`AppointmentSlotService`):**
     - Đổi `DEFAULT_CONCURRENT_ARRIVALS_PER_DOCTOR = 1` và `@Value("${appointments.doctor-concurrent-per-slot:1}")` (hoặc loại bỏ wave concurrency > 1 hoàn toàn khỏi `subtractFullConcurrencyIntervals` / `computeRemainingAtWindow` thành strictly `concurrent < 1` tức **0 overlap `[start, end)` cho cùng 1 bác sĩ**).
     - Khi kiểm tra một khung giờ `[start, end)` cho một bác sĩ hoặc toàn chi nhánh:
       - Bác sĩ A không được có bất kỳ `Appointment` (`PENDING`, `CONFIRMED`, `CHECKED_IN` trừ ca đã xong `actual_end_time` hợp lệ) nào thỏa mãn `intervalsOverlap(apptStart, apptEnd, start, end)`.
       - Giới hạn trần chi nhánh theo **số ghế hoạt động thực tế** (`DentalChairRepository`: đếm số ghế `AVAILABLE` hoặc `IN_USE` tức khác `MAINTENANCE` thuộc `Room.ACTIVE` của chi nhánh): nếu chi nhánh có cấu hình `> 0` ghế, tại mọi khung `[start, end)` tổng số lịch giao nhau ở chi nhánh không được vượt `activeChairsCount` (chặn rủi ro nhiều bác sĩ đặt chồng vượt tổng số ghế thực tế của phòng khám!).
  2. **Chuẩn hóa Lịch hẹn linh hoạt (`expectedTime == null`):**
     - **Tất cả các kênh** (`PortalAppointmentService`, `PublicBookingRestController`, `ReceptionAppointmentService`, `ReExaminationServiceImpl`) bắt buộc giữ thứ tự lock **`PatientProfile -> Branch -> StaffProfile (nếu có) -> StaffSchedule -> Appointment`** và gọi `AppointmentPolicyService.ensureBookable(...)` (không ngoại lệ!).
     - Xử lý khi `expectedTime == null` trong `AppointmentSlotService`:
       - Nếu đã chọn bác sĩ (`doctorId != null`): vẫn phải khóa `StaffProfile` + `StaffSchedule` + `lockDoctorAppointmentsForDate(...)` trong `lockAndValidateDoctorSlotForFlexible(...)` (hoặc nhánh mở rộng của `lockAndValidateDoctorSlot`), kiểm tra:
         (a) bác sĩ có lịch trực hợp lệ phủ toàn bộ/tối thiểu `durationMinutes` còn lại trong buổi chưa qua giờ hiện tại;
         (b) **tổng thời lượng đã chiếm** (`sum(timed appointments overlap session) + sum(flexible appointments của bác sĩ trong buổi) + durationMinutes <= tổng phút làm việc thực tế của bác sĩ trong buổi`), đồng thời tổng số ca trong buổi `< doctorCapacity`.
       - Nếu chưa chọn bác sĩ (`doctorId == null`): không chỉ kiểm `booked.size() < min(branchCap, doctors.size() * doctorCap)` mà còn trừ **quỹ thời lượng** (mỗi lịch linh hoạt chưa gắn bác sĩ quy đổi chiếm `durationMinutes` từ quỹ chung các bác sĩ trực trong buổi đó → đảm bảo `computeRemainingAtWindow` và `available()` **giảm dần số slot mở khi lịch linh hoạt tăng lên**, không bao giờ overbook tổng năng lực giờ công của các bác sĩ đang trực!).
       - Xác nhận rõ: Lịch linh hoạt vẫn ở `expectedTime = null, expectedEndTime = null`, không tự động gán bác sĩ không đủ điều kiện; khi check-in tại quầy, lễ tân bắt buộc phân công bác sĩ khả dụng tại thời điểm đến (hoặc chọn tự động theo tải thực tế).
     - Xử lý **`session == ANY` hoặc `null`**:
       - Trên Portal & Home Drawer: bắt buộc chọn buổi cụ thể (`MORNING / AFTERNOON / EVENING`), từ chối `ANY`/`null` (`400 Bad Request`).
       - Trên Quầy lễ tân (`ReceptionAppointmentService.create`): nếu gửi `session == null/ANY` kèm `expectedTime`, suy đúng buổi theo `sessionRange(branch, MORNING/AFTERNOON/EVENING)` (08:00–12:00 `MORNING`, 13:30–17:30 `AFTERNOON`, >= 17:30/18:00 `EVENING`), nếu không khớp hoặc không có `expectedTime` thì báo lỗi yêu cầu chọn buổi rõ ràng!
  3. **Khắc phục triệt để `ReExaminationServiceImpl`:**
     - Bỏ nhánh `else { validateDoctorAvailabilityForReExam(...) }` (`lines 134-142, 403-411`); mọi ca tái khám buộc đi qua `AppointmentPolicyService.ensureBookable(...)` + `AppointmentSlotService` (`resolveFlexibly` + `lockAndValidateDoctorSlot`), kể cả khi bác sĩ `null` (tự phân bổ hoặc kiểm quỹ linh hoạt nếu `expectedTime == null`), chặn `NO_SHOW` tại `assertAppointmentMutable` và ghi `AppointmentHistory`.
  4. **Độ toàn vẹn tầng DB (Transaction & Constraint):** Kiểm tra kỹ thứ tự lock & thêm cơ chế DB chống trùng đồng thời (chi tiết ở Phần C).
- **File dự kiến thay đổi:** `AppointmentSlotService.java`, `AppointmentPolicyServiceImpl.java`, `ReceptionAppointmentService.java`, `PortalAppointmentService.java`, `ReExaminationServiceImpl.java`, `AppointmentConflictDetectionServiceImpl.java`, `DentalChairRepository.java`, `AppointmentSlotServiceTest.java`, `AppointmentPolicyServiceTest.java`, `AppointmentCrossBranchConcurrencyIntegrationTest.java`.
- **Rủi ro:** Các test cũ trong `AppointmentSlotServiceTest` đang assert wave `3` (`sharedArrivalAllowsMultiplePatientsUpToWaveAndSessionCapacity...`, `requestedDoctorAllowsConcurrentArrivalUpToCapAndBlocksWhenWaveCapReached`, `availableReturnsRealRemainingCapacityAcrossConcurrentArrivalsAndBalancesAutoAssign` expect `5` chỗ tại `15:30`) sẽ cần được cập nhật tương ứng theo quy tắc `maxConcurrent = 1` mới sau khi bạn xác nhận!
- **Test cần có:** Đủ toàn bộ danh sách mục 8.2 (2 người cùng bác sĩ cùng giờ -> 1 thắng 1 thua 409; 2 lễ tân tạo cùng lúc; 2 dịch vụ khác thời lượng `20p` và `45p`; vượt số ghế chi nhánh; lịch linh hoạt + lịch giờ cụ thể cạnh tranh đến ngưỡng công suất buổi; bác sĩ không có ca trực/nghỉ phép/ngoài chi nhánh/vượt giờ đóng cửa).

---

#### [P0-4] Chuẩn hóa phân loại Đến sớm (`EARLY_WAITING`), Đúng giờ (`ON_TIME`), Đến muộn (`LATE_BOOKING`), Quá 30p (`NO_SHOW`) & Phần giây (Mục 3.3, 4.4)

- **Bằng chứng hiện tại:**
  - `ReceptionService.java:148, 658-670`: `evaluateGracePeriodAndRejectExpired` dùng `LocalDateTime nowDateTime = LocalDateTime.now(clock)` (mang theo giây/nanosecond) so sánh `!checkInTime.isBefore(expected - 15m) && !checkInTime.isAfter(expected + 15m)` → `15:44` trả `false`; `16:15:01` trả `false`; `16:30:01` ném 409; và mọi trường hợp `false` (`15:44` lẫn `16:16`) đều rơi chung vào `isBookedAndOnTime = false`.
  - Toàn bộ `ClinicQueueCoordinatorService` (`135-153, 350`), `DispatchService` (`628-636`), `DoctorPatientRestController` (`941-957`), `VisitRepository` (`246-253`) dùng chung logic: `APPOINTMENT + !isBookedAndOnTime` → **`LATE_BOOKING` (Tier 4, sau Walk-in!)**.
  - `ReceptionAppointmentService.java:662-665`: `isNoShowCutoffPassed` kiểm tra `now.isAfter(cutoff)` (cũng dính phần giây `16:30:01`).
  - `V114__doctor_specific_booking_and_priority_queue.sql:101-105`: backfill `is_booked_and_on_time = 1` cho `check_in_at <= TIMESTAMP(...) + INTERVAL 15 MINUTE` (không giới hạn dưới `-15p`).
- **Nguyên nhân gốc:** Dùng duy nhất 1 cột boolean `is_booked_and_on_time` cho 3 trạng thái đến khám (`EARLY_WAITING`, `ON_TIME`, `LATE_BOOKING`) và thiếu rà soát phần giây + cơ chế tự nâng hạng từ Chờ sớm sang Đúng giờ khi chạm mốc `-15 phút`.
- **Mức độ ưu tiên:** **P0 – Core Business Correctness**.
- **Giải pháp đề xuất:**
  1. **Chuẩn hóa độ chính xác thời gian đến từng phút (`truncatedTo(ChronoUnit.MINUTES)` hoặc quy tắc giây tường minh):**
     - Chuẩn hóa: cắt phần giây `LocalDateTime checkInMinute = checkInTime.truncatedTo(ChronoUnit.MINUTES)` (hoặc so sánh tương đương: `< 16:16:00` vẫn thuộc phút `16:15` -> `ON_TIME`, `< 16:31:00` vẫn thuộc phút `16:30` -> `LATE_BOOKING`, từ `16:31:00` trở đi -> Quá 30p!).
     - Áp dụng **đồng nhất hàm chuẩn hóa phút** tại cả:
       - `ReceptionService.evaluateCheckInTiming(...)`
       - `ReceptionAppointmentService.isNoShowCutoffPassed(...)`
       - `AppointmentSlotService.resolveEffectiveInterval(...)` (grace 15p giải phóng slot khi `nowMinute.isAfter(apptMinute + 15m)`, tức bắt đầu từ `16:16`!).
     - *(Ghi chú cần xác nhận tại Mục 5 bên dưới: `16:15:01` nên vẫn tính `ON_TIME` trong phút thứ 15 hay cắt cứng ngay giây `16:15:01`? Đề xuất kiến nghị theo chuẩn phút phòng khám: toàn bộ phút `16:15` (`16:15:00`–`16:15:59`) là đúng giờ, từ `16:16:00` là muộn; tương tự `16:30:00`–`16:30:59` vẫn tiếp nhận muộn, `16:31:00` trở đi mới quá hạn `NO_SHOW`! Nhưng nếu bạn muốn cắt cứng tại `:01` giống Mục 8.3 gợi ý test `16:15:01` / `16:30:01`, ta sẽ viết logic + test khớp đúng quyết định bạn chốt!)*
  2. **Thiết kế trạng thái `EARLY_WAITING` & Tự nâng hạng tại mốc `-15 phút`:**
     - Bổ sung trên `Visit` (thêm 1 cột additive qua Flyway `arrival_timing VARCHAR(20) NULL` hoặc enum `ArrivalTiming { EARLY_WAITING, ON_TIME, LATE_BOOKING, WALK_IN, EMERGENCY }`, fallback suy luận an toàn cho dữ liệu cũ từ `is_booked_and_on_time` + `visit_type` + `appointment`):
       - **Check-in `< expectedTime - 15m` (VD `15:44`):**
         - Cho check-in (`Appointment -> CHECKED_IN`, tạo `Visit` với `checkInAt = thời gian thực 15:44`, `isBookedAndOnTime = false`, `arrivalTiming = EARLY_WAITING`).
         - **KHÔNG** gắn `LATE_BOOKING`, **KHÔNG** xếp sau Walk-in. Tạo `QueueTicket` ở trạng thái chờ riêng (hoặc gắn cờ `priorityScore = 60` / nhãn `"EARLY_WAITING: Đến sớm, chờ tới 15:45 kích hoạt Đúng giờ"` hoặc giữ `QueueStatus.WAITING` + không cho `autoCallNextForDoctorIfIdleLocked` gọi trước `expectedTime - 15m`!).
         - **Cơ chế tự động nâng hạng (`promoteEligibleEarlyArrivals`):** Khi đồng hồ hệ thống chạm `>= expectedDateTime - 15m` (lúc `15:45`):
           1. Chạy trong một `@Scheduled(cron = "0 * * * * *")` (cùng nhịp phút với `ReceptionNoShowJob`, có lock) **VÀ** kiểm tra on-read/before-call trong `ClinicQueueCoordinatorService` (đảm bảo dù vừa đúng `15:45:00` giữa chu kỳ job mà bác sĩ xong ca trước, hệ thống **tự nâng ngay** từ `EARLY_WAITING` -> `ON_TIME`, `isBookedAndOnTime = true`, `priorityScore = 75`, xếp vào **Nhóm 2** dùng `checkInAt` thực tế `15:44` để tie-break và tự động gọi số nếu bàn đang rảnh!).
           2. Cập nhật số phiếu hiển thị thành chuẩn ưu tiên (`P-xx` hoặc đồng bộ lại nếu vừa nâng từ sớm lên đúng giờ — chú ý `uk_queue_no (branch_id, queue_date, queue_type, is_priority, ticket_number)`: nếu đã phát số thường trước đó thì giữ nguyên hoặc cấp số `P-xx` mới hợp lệ tránh trùng UK, tốt nhất cấp số **`P-xx` ngay lúc check-in sớm** với `priorityReason = EARLY_WAITING` hoặc phát số thường lúc sớm và khi nâng cấp cập nhật lại số `P-xx` an toàn trong lock `Branch`!). *(Đề xuất chi tiết & hỏi bạn chọn phương án cấp số `P-` vs `thường` cho khách đến sớm ở Mục 5).*
       - **Check-in `16:16` đến `16:30` (`LATE_BOOKING`):**
         - Trước khi cho check-in trễ (`ReceptionService.checkInAppointment`), **kiểm tra lại khả năng tiếp nhận hiện tại của bác sĩ & ghế**:
           - Nếu slot `[expectedTime, expectedEndTime)` đã quá 15p giải phóng **VÀ bác sĩ đã có lịch hẹn mới chồng vào khung giờ hiện tại (`now .. now + duration`) hoặc đã đạt `MAX_DOCTOR_QUEUE_LIMIT = 8` hoặc đã hết ca/nghỉ phép**: **KHÔNG mặc định nhét thẳng vào bác sĩ cũ**! Trả cảnh báo rõ ràng (hoặc cho phép người có quyền điều phối chọn bác sĩ rảnh khác thay thế ngay khi tiếp đón; nếu hết bác sĩ toàn cơ sở hoặc ngoài giờ làm thì thông báo không thể tiếp nhận).
- **File dự kiến thay đổi:** `Visit.java`, enum `ArrivalTiming.java` (mới), Flyway migration mới (thêm `visits.arrival_timing` + chỉnh dữ liệu backfill chuẩn xác), `ReceptionService.java`, `ReceptionAppointmentService.java`, `AppointmentSlotService.java`, `ClinicQueueCoordinatorService.java`, `ReceptionNoShowJob.java` (hoặc `EarlyArrivalPromotionJob`), `DispatchService.java`, `QueueWorkflowService.java`, `DoctorPatientRestController.java`, `VisitRepository.java`, `check-in.html`.
- **Test cần có:** Đủ 10 mốc (8 mốc `15:44`..`16:31` + 2 mốc giây `16:15:01`, `16:30:01`) + test auto-promote `15:44` -> chờ chưa được auto-call -> tới `15:45` tự động vào Nhóm 2 và được gọi; test `16:20` bị chặn gán bác sĩ cũ nếu bác sĩ đã nhận khách mới thay thế.

---

#### [P0-5] Phương án xử lý an toàn Flyway trùng `V107` & Ràng buộc Database (Mục 4.5, Mục 5)

- **Bằng chứng hiện tại:**
  - `src/main/resources/db/migration/` (classpath được Flyway sử dụng trong `application-prod.properties:39` & `application-release.properties:7`) có 2 file cùng version `107`:
    - `V107__customer_care_realtime_notifications.sql` (20 dòng: sửa `system_notifications` và `customer_care_tasks`).
    - `V107__doctor_cover_grants.sql` (14 dòng: tạo bảng `doctor_cover_grants`).
  - Lệnh `SELECT * FROM flyway_schema_history` chưa chạy trực tiếp được từ môi trường sandbox này (do không có CLI `mysql` trong hệ thống / không đọc `application.properties` theo `GEMINI.md` Rule 5).
  - Trong `database/migrations/` (thư mục SQL lịch sử chạy tay ngoài Flyway) cũng có trùng số `V7, V8, V11, V13, V14`, trong đó `V7__ft07_checkin_integrity.sql` đã chứa `uk_ft07_visit_appointment UNIQUE (appointment_id)` nhưng Flyway `V100`–`V114` chưa có migration đảm bảo constraint này.
- **Mức độ ưu tiên:** **P0 – Deployment & Schema Safety**.
- **Giải pháp an toàn đề xuất (CHỈ THỰC HIỆN SAU KHI BẠN KIỂM TRA & XÁC NHẬN `flyway_schema_history`):**
  1. **Bước Preflight bắt buộc (người dùng/DBA chạy SQL Read-Only trên DB thật & Staging trước):**
     ```sql
     SELECT installed_rank, version, description, type, script, checksum, success
     FROM flyway_schema_history
     WHERE version IN ('107','114') OR script LIKE '%107%'
     ORDER BY installed_rank;
     ```
     Kiểm tra xem DB thực tế đang có:
     - **Trường hợp A (DB chưa từng chạy `V107__doctor_cover_grants.sql` qua Flyway, hoặc DB dùng `baseline-version=0` chạy mới hoàn toàn):**
       Đổi tên `V107__doctor_cover_grants.sql` → `V115__doctor_cover_grants.sql` (hoặc version kế tiếp sau `V114`), giữ nguyên câu lệnh `CREATE TABLE IF NOT EXISTS doctor_cover_grants (...)` để chạy lại an toàn dù bảng đã từng được tạo tay bằng Hibernate!
     - **Trường hợp B (DB dev đã từng lỡ apply `V107__doctor_cover_grants.sql` qua Flyway hoặc `outOfOrder`):**
       Nếu đã ghi trong `flyway_schema_history` với `version='107'` đúng 1 file nào đó → **giữ nguyên file đã ghi nhận ở version 107 (cả checksum y hệt!)**, chuyển file còn lại thành `V115__...` (với SQL `IF NOT EXISTS` idempotent). Nếu cả 2 dòng đã tồn tại trong `flyway_schema_history` ở DB dev, cần lên script repair có backup trước khi đổi tên.
     - **Trường hợp C (Nếu thư mục `database/migrations/` không còn dùng qua Flyway):** Chú thích rõ là thư mục SQL lưu trữ lịch sử, không đưa vào `spring.flyway.locations`.
  2. **Kiểm tra dữ liệu trùng trước khi thêm bất kỳ UNIQUE constraint mới nào (Mục 5):**
     - Cung cấp trước bộ query preflight kiểm tra bản ghi trùng trên `visits(appointment_id)` và `appointments(profile_id, appointment_date, preferred_session)` (các bản ghi `PENDING/CONFIRMED/CHECKED_IN` chưa xong lâm sàng) trước khi viết migration thêm constraint!
- **Rủi ro:** Đổi tên file đã có trong `flyway_schema_history` sẽ làm Flyway validate fail (`checksum/script mismatch`) ở môi trường đó nếu không kiểm tra trước. Vì vậy bắt buộc **xác nhận `flyway_schema_history` hoặc xác nhận cách DB hiện tại đang khởi tạo** ở bước phê duyệt!

---

### PHẦN B – NHÓM P1 (HOÀN THIỆN TIẾP ĐÓN, ĐỒNG BỘ HÀNG ĐỢI & BẢO MẬT TV)

#### [P1-1] Xử lý bác sĩ đặt lịch nghỉ đột xuất hoặc mất khả năng tiếp nhận khi Check-in (Mục 4.6)
- **Bằng chứng hiện tại:**
  - `ReceptionService.checkInAppointment:177-182` và `confirmAndCheckIn:300-305` gán thẳng `visit.setPrimaryDoctor(appointment.getDoctor())` bất kể bác sĩ đó đang nghỉ phép (`LeaveStatus.APPROVED`), ngừng hoạt động (`StaffStatus != ACTIVE`), chuyển chi nhánh hoặc đã quá tải/ngoài ca trực.
  - Ngược lại, `confirmAndCheckIn:265-270` gọi `policyService.ensureBookable(...)` cho lịch `PENDING` → **chặn đứng hoàn toàn (409)** không cho lễ tân tiếp đón bệnh nhân đang đứng ở quầy nếu bác sĩ gốc vừa nghỉ phép!
  - `LeaveRequestService.approveLeaveRequest:318-386` tự động đổi toàn bộ `PENDING/CONFIRMED` thành `CANCELLED` **không lock bi quan**, **không ghi `AppointmentHistory`**, **không hủy `APPOINTMENT_REMINDER`**.
- **Giải pháp đề xuất:**
  1. Trong `LeaveRequestService.approveLeaveRequest`: Không auto-cancel đột ngột toàn bộ lịch hẹn; nếu áp dụng chính sách giữ lịch chờ xử lý hoặc giữ/đánh dấu "Cần điều phối bác sĩ thay thế", khóa `Appointment` theo thứ tự `Branch -> Appointment`, hoặc nếu buộc hủy theo chính sách cũ thì phải lock + ghi `AppointmentHistory` + hủy reminder + phát `ClinicQueueDomainEvent` + báo CSKH liên hệ. *(Xin xác nhận chính sách tại Mục 5: **Giữ lịch & gắn cờ cần đổi bác sĩ** hay **Vẫn hủy lịch nhưng ghi đầy đủ history/lock**?)*
  2. Trong `ReceptionService.checkInAppointment` và `confirmAndCheckIn`:
     - Viết helper kiểm tra **bác sĩ đặt trước còn đủ điều kiện tiếp nhận tại thời điểm check-in không** (`ACTIVE`, `StaffBranchAssignment ACTIVE`, có `StaffSchedule` phù hợp hôm nay/ca hiện tại, không có `LeaveRequest APPROVED`, chưa vượt tải `MAX_DOCTOR_QUEUE_LIMIT`).
     - Nếu **đủ điều kiện**: giữ nguyên luồng gán bác sĩ cũ.
     - Nếu **không đủ điều kiện**:
       - Không tự gán bừa bác sĩ khác (tránh trái ý muốn bệnh nhân nếu không có quyền điều phối, Mục 4.6);
       - Với người có quyền `CLINICAL_DISPATCH`/`ADMIN` (hoặc trong luồng mở rộng modal Check-in): cho phép chọn `replacementDoctorId` trong danh sách bác sĩ hợp lệ cùng cơ sở và ghi `AppointmentHistory` + `SystemAuditLog` lý do đổi bác sĩ!
       - Với lễ tân thường (chỉ có `RECEPTION_CHECKIN`): cho phép hiển thị cảnh báo rõ *"BS. X hiện đã nghỉ phép / hết ca / quá tải. Không thể xếp trực tiếp vào BS. X"* và hoặc yêu cầu chuyển điều phối (nếu tạo `Visit` với `primaryDoctor = null` ở `confirmOverride` đẩy sang `/reception/dispatch`, hoặc giữ chờ điều phối không gán `QueueTicket` cho bác sĩ đã nghỉ). Tuyệt đối không tạo `Visit` trỏ tới bác sĩ đã nghỉ/ngừng hoạt động!

#### [P1-2] Hoàn thiện quy trình Walk-in (Mục 4.7)
- **Bằng chứng hiện tại:** `ReceptionService.checkInWalkIn` (`lines 418-465`) và `autoAssignDoctorOnCheckIn` (`lines 672-739`): không check `workingDays`, `openingTime`, `closingTime`; khối `try/catch (Exception ignored)` nuốt sạch lỗi; bỏ qua `MAX_DOCTOR_QUEUE_LIMIT = 8`; khi không có bác sĩ trong ca trực trả `Visit` với `primaryDoctor = null` mà không cảnh báo lễ tân.
- **Giải pháp đề xuất:**
  1. Bổ sung kiểm tra `branch.isActive`, `workingDays` hôm nay, `openingTime <= now < closingTime` trước khi tạo Walk-in (cho phép ngoại lệ riêng nếu là `emergency == true` theo chính sách phòng khám nếu bạn muốn — cần xác nhận ở Mục 5).
  2. Xóa bỏ `try/catch` nuốt lỗi trong `autoAssignDoctorOnCheckIn`; chỉ chọn bác sĩ đang `ACTIVE`, thuộc chi nhánh, trong ca trực hiện tại (`!now.isBefore(start) && now.isBefore(end)`), không nghỉ phép và có tải chờ hiện tại `< MAX_DOCTOR_QUEUE_LIMIT (8)` và có ghế hoạt động khả dụng.
  3. Khi **không có bác sĩ trực** hoặc **tất cả bác sĩ đều đạt giới hạn `8` ca** (và không có ghế sẵn sàng): không tự ý gán vượt giới hạn `8`. Tùy chính sách chốt: từ chối tạo Walk-in với thông báo nghiệp vụ rõ ràng cho lễ tân (409) *hoặc* yêu cầu lễ tân xác nhận "Đưa vào danh sách chờ điều phối thủ công tại `/reception/dispatch`".
  4. **Đóng lỗ hổng bác sĩ mở thẳng `Visit` từ màn khám:** Kiểm tra `DoctorPatientRestController.openOrCreateVisitForPatient` (`142-268`) xem có tạo `Visit` vượt quầy lễ tân khi bệnh nhân đã có lịch `CONFIRMED` hay gây deadlock `Visit -> Branch` không → chuẩn hóa lock `PatientProfile -> Branch` trước khi lưu và cảnh báo nếu bệnh nhân còn lịch hẹn chưa check-in.

#### [P1-3] Chuẩn hóa Quyền & Nghiệp vụ Ưu tiên (`Priority`) và Cấp cứu (`Emergency`) (Mục 4.8)
- **Bằng chứng hiện tại:**
  - `ReceptionCheckInController` nhận `boolean priority` + `boolean emergency`; `ReceptionService:173,445` chỉ append `"[ƯU TIÊN]"` vào `visit.notes` (vô nghĩa với `ClinicQueueCoordinatorService`).
  - `FeaturePermissionInterceptor:137` cấp quyền `/reception/queue/**` (bao gồm `POST /reception/queue/{id}/EMERGENCY`) cho bất kỳ ai chỉ có quyền xem `APPOINTMENT_VIEW`.
  - Chưa có action hủy/bỏ đánh dấu cấp cứu hoặc bỏ ưu tiên khi bấm nhầm.
  - `ClinicQueueCoordinatorService.java:48`: `ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING = true`.
- **Giải pháp đề xuất:**
  1. Tắt `ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING = false` trên `ClinicQueueCoordinatorService`.
  2. Tách biệt rạch ròi:
     - **Emergency (Cấp cứu - Hạng 1):** Chỉ cho phép người có quyền `RECEPTION_CHECKIN`, `RECEPTION_DISPATCH`, `CLINICAL_DISPATCH`, `CLINICAL_DAILY_VISITS` hoặc `ADMIN` (chặn `APPOINTMENT_VIEW` chỉ xem trong `FeaturePermissionInterceptor` & `QueueWorkflowService`). Bắt buộc lý do 10–255 ký tự; lưu đầy đủ người đánh dấu + thời điểm vào `SystemAuditLog`. Bổ sung action **`REVOKE_EMERGENCY`** (yêu cầu lý do 10–255 ký tự, ghi audit log cũ/mới, phục hồi đúng hạng ban đầu từ `VisitType` / `arrivalTiming` thay vì giữ cứng hạng 1).
     - **Priority thông thường (Nếu giữ nút `priority` cho người già/trẻ nhỏ/thai sản/khách VIP):** Không trộn với Emergency, hoặc quy định rõ thứ hạng ưu tiên so với Đặt lịch đúng giờ (VD nếu giữ 4 nhóm chuẩn thì chỉ có `PRIORITY` cộng điểm nhẹ trong cùng nhóm Walk-in, hoặc bỏ hẳn nút Priority giả để giữ đúng 4 tầng ưu tiên chuẩn tại Mục 3.4!). Bổ sung kiểm tra lý do + audit log + action bỏ ưu tiên tương tự. *(Đưa vào danh sách câu hỏi xác nhận Mục 5).*

#### [P1-4] Thống nhất 1 Nguồn Chân Lý Hàng Đợi Tiếp Đón & Loại bỏ `WAITING_RESULT` khỏi Hàng Đợi Tiếp Đón Ban Đầu (Mục 3.4, 4.9)
- **Bằng chứng hiện tại:** 5 nơi (`ClinicQueueCoordinatorService`, `QueueWorkflowService`, `DispatchService`, `ReceptionService`, `DoctorPatientRestController` + `VisitRepository`) tự viết bộ sắp xếp riêng; `WAITING_RESULT` chen vào trên Nhóm 2 ở 3/4 nơi và tụt xuống dưới ở `ReceptionService`.
- **Giải pháp đề xuất:**
  - Chuẩn hóa **duy nhất 1 comparator tại `ClinicQueueCoordinatorService`** cho **Hàng đợi Tiếp đón ban đầu (`VisitStatus == WAITING`)**:
    1. Lọc riêng **`EARLY_WAITING`** vào danh sách chờ đến giờ hẹn (`chưa đủ điều kiện auto-call`, hiển thị badge rõ ở Lễ tân & Bác sĩ).
    2. Hàng đợi tiếp đón đủ điều kiện gọi chỉ xét các `Visit` ở `VisitStatus.WAITING` (không phải `EARLY_WAITING`), sắp theo đúng **4 cấp**:
       - Tier 1: `EMERGENCY`
       - Tier 2: `APPOINTMENT ON_TIME` (gồm cả ca `EARLY_WAITING` đã tới mốc `-15p` được nâng hạng)
       - Tier 3: `WALK_IN`
       - Tier 4: `APPOINTMENT LATE_BOOKING`
       - **Tie-break duy nhất trong cùng cấp:** `Visit.checkInAt` tăng dần (thời điểm check-in thực tế) → `Visit.visitId` tăng dần.
  - **`VisitStatus.WAITING_RESULT`** (bệnh nhân quay lại sau cận lâm sàng):
    - **KHÔNG** trộn vào tập hàng đợi tiếp đón ban đầu của `ReceptionService.dashboard`, `DispatchService.board` (tab Chờ tiếp đón), `QueueWorkflowService.board` (làn Tiếp đón ban đầu), `DoctorPatientRestController.getWaitingQueue`.
    - Trên bàn bác sĩ (`/daily-visits`, `waiting-queue`, `ClinicQueueCoordinatorService`): tách thành danh sách/tab riêng `waitingResultQueue` (giữ nguyên logic chuyên môn sau khám, **không** dùng `WAITING_RESULT` để đẩy lùi bệnh nhân Đặt lịch đúng giờ trong hàng đợi tiếp đón mới, đúng yêu cầu *"không sửa logic điều phối khám tiếp theo nếu không thực sự cần thiết"* nhưng bảo đảm không chen vào hàng đợi tiếp đón ban đầu!).
  - Cả `ReceptionService`, `DispatchService`, `QueueWorkflowService`, `DoctorPatientRestController` **đều gọi về cùng `ClinicQueueCoordinatorService`** (xóa các comparator nhân bản).

#### [P1-5] Ẩn danh dữ liệu trên Màn hình TV & API Công khai (Mục 4.10)
- **Bằng chứng hiện tại:** `QueueWorkflowService.publicBoard` / `buildPublicQueueList` (`lines 172-286`) trả đầy đủ `fullName`, `dob`, `doctorName` qua `/tv-display` và `/reception/queue/tv/{branchId}/data` (`permitAll()`). `reception-queue-tv.js:133, 186-192` hiển thị `dob`, `doctorName` và đọc tên bác sĩ qua loa TTS.
- **Giải pháp đề xuất:**
  - Gọi `maskName(p.getFullName())` cho `patientName` (hoặc kết hợp mã số phiếu `P-01` + tên viết tắt ẩn danh `Nguyễn V. A`).
  - **Loại bỏ hoàn toàn** `dob`, triệu chứng, tên bác sĩ (`doctor` / `doctorName`) khỏi `PublicNumber`, `PublicQueueRow`, bảng `reception/queue-tv.html` và câu đọc TTS trong `reception-queue-tv.js` (chỉ đọc: *"Xin mời số phiếu P-01 vào Phòng 101 - Ghế 01"*).

#### [P1-6] Sửa Lỗi Đồng Bộ Khóa, Ngăn Trùng Ghế/Check-in/NO_SHOW & Sửa Realtime Tiếp Đón (Mục 5 & Mục 7)
1. **Đồng nhất thứ tự khóa chống AB-BA Deadlock:** Mọi transaction đều khóa theo thứ tự: **`PatientProfile -> Branch -> StaffProfile -> StaffSchedule -> Appointment -> Visit -> QueueTicket`**.
   - Sửa `LeaveRequestService.approveLeaveRequest` (khóa `Branch -> StaffProfile -> StaffSchedule -> Appointment`).
   - Sửa `DoctorPatientRestController.openOrCreateVisitForPatient` (khóa `PatientProfile -> Branch` trước khi INSERT `Visit`).
2. **Ngăn 2 bệnh nhân dùng chung 1 ghế nha:** Trong `DispatchService.assign`, không tự gán cứng `chairId` chiếm độc quyền trước khi vào ghế nếu còn ca chờ, hoặc nếu gán `chairId` trước ở bước điều phối thì đánh dấu rõ đây là "ghế dự kiến", khi `START`/`enterExamination` bắt buộc `SELECT ... FOR UPDATE` trên bản ghi `DentalChair` (thêm `@Lock(PESSIMISTIC_WRITE)` ở `DentalChairRepository`) kiểm tra `status == AVAILABLE` + `occupiedChair == 0`, đồng bộ không để 2 lượt `IN_EXAM`/`IN_SERVICE` dùng chung 1 ghế cùng lúc!
3. **Sửa lỗi nhỏ ở `ReceptionAppointmentService`:** Sửa suy luận `EVENING` (`> 17:30`), dọn `Visit`/`QueueTicket` trong `quickStatus(CANCELLED)` giống `cancel()`, đồng bộ `AppointmentConflictDetectionServiceImpl` không coi `COMPLETED`/`WAITING_PAYMENT` là trùng ca.
4. **Database Integrity Constraints & Distributed Scheduler Lock:**
   - Thêm `@Version` cho `Appointment` và `Visit` (nếu không làm gãy test).
   - Sau preflight sạch trên DB, đảm bảo ràng buộc `uk_visit_appointment UNIQUE (appointment_id)` (vì `revertCheckIn` đã set `appointment_id = null`) và xem xét cột sinh MySQL 8 `active_session_slot_key` `GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','CONFIRMED','CHECKED_IN') THEN CONCAT(...) ELSE NULL END) STORED UNIQUE` (tương tự pattern `V100` tại `branch_bank_accounts`!) để khóa trùng cùng buổi ở tầng Database.
   - Cho `ReceptionNoShowJob` (và job auto-promote `EARLY_WAITING`): sử dụng DB advisory lock (`MySQL GET_LOCK('cvdcms_noshow_job', 0)`) hoặc bảng lock 1 dòng chuẩn JPA/JDBC (tránh thêm thư viện mới nếu không cần thiết) để an toàn khi deploy multi-instance!
5. **Sửa Realtime WebSocket & UX Chống Double-Submit:**
   - Bundle cục bộ `sockjs.min.js` & `stomp.min.js` vào `src/main/resources/static/js/vendor/`, nạp đủ cho `reception/queue.html`, `reception/check-in.html`, `reception/dispatch.html`, `reception/appointments/dashboard.html`, `doctor/daily-visits.html`.
   - Thêm reconnect tự động + **fetch reload state chuẩn từ DB sau khi kết nối lại**; thay `window.location.reload()` trên `check-in.html` bằng AJAX refresh bảng.
   - UI `portal/home.html`, `portal-appointments.js`, `reception-appointments.js`, `check-in.html`: khóa nút submit trong khi chờ API (`inFlight`), hiển thị rõ badge `Đến sớm`, `Đúng giờ`, `Đến muộn`, cảnh báo bác sĩ nghỉ/hết sức chứa, và xử lý `loginRequired` cho Guest.

---

## 4. CÁC QUYẾT ĐỊNH NGHIỆP VỤ ĐÃ ÁP DỤNG (ĐÚNG MỤC 3)

1. **Loại bỏ mặc định 3 ca trùng giờ của 1 bác sĩ:** Mọi bác sĩ chỉ tiếp nhận **1 cuộc hẹn tại cùng khoảng `[start, end)`** trừ khi có tài nguyên riêng khác được chứng minh bằng dữ liệu. Thời lượng dùng `Service.estimatedDurationMinutes` (mặc định `30` phút).
2. **Lịch linh hoạt (`expectedTime == null`):** Phải trừ vào sức chứa buổi và quỹ thời lượng làm việc của chi nhánh/bác sĩ; không vượt qua `lockAndValidateDoctorSlot`.
3. **Phân loại đến khám 4 mốc thời gian:** `< -15p` là `EARLY_WAITING` (đến sớm, giữ chờ riêng, không gán `LATE_BOOKING`, tự kích hoạt lên Nhóm 2 lúc chạm `-15p`); `[-15p .. +15p]` là `ON_TIME` (Nhóm 2); `[+16p .. +30p]` là `LATE_BOOKING` (Nhóm 4, kiểm tra lại khả năng tiếp nhận trước khi nhận); `> +30p` bị từ chối và xử lý `NO_SHOW`.
4. **Thứ tự hàng đợi tiếp đón ban đầu chuẩn 4 cấp:** `1. EMERGENCY -> 2. APPOINTMENT ON_TIME -> 3. WALK_IN -> 4. LATE_BOOKING`; tie-break `checkInAt` tăng dần -> `visitId` tăng dần; **`WAITING_RESULT` không chen vào hàng đợi tiếp đón ban đầu**.
5. **Guest trùng SĐT:** Không dùng SMS OTP; chặn gắn lịch vào hồ sơ/tài khoản có sẵn và yêu cầu đăng nhập với thông báo trung lập không lộ PII; Guest chưa có tài khoản lưu sang luồng yêu cầu đặt lịch tư vấn (không chiếm slot chính thức).

---

## 5. NHỮNG ĐIỂM CẦN BẠN XÁC NHẬN TRƯỚC KHI TRIỂN KHAI (MỤC 12)

Xin vui lòng xác nhận **6 điểm chốt** dưới đây trước khi tôi tiến hành sửa code Giai đoạn 2:

1. **Thiết kế Guest chưa có tài khoản (`PublicBookingRestController` - Mục 4.1B):**
   - Tôi đề xuất tạo bảng + Entity mới `booking_consultation_requests` (`BookingConsultationRequest`) để lưu yêu cầu tư vấn/đặt lịch của Guest chưa có tài khoản (kèm màn xử lý chuyển thành `Appointment` cho Lễ tân), **không tạo `User`/`PatientProfile`/`Appointment` tạm và không chiếm slot bác sĩ**. Bạn đồng ý phương án bảng mới này chứ?
2. **Phương án xử lý Flyway trùng `V107` (Mục 4.5):**
   - Trong môi trường bạn đang chạy (`cv_dcms_db` local/staging/prod), trong 2 file `V107__customer_care_realtime_notifications.sql` và `V107__doctor_cover_grants.sql`, file nào **đã có dòng tương ứng trong bảng `flyway_schema_history`** (hoặc DB hiện tại có đang tắt Flyway bằng `spring.flyway.enabled=false` ở `application.properties` và chạy schema tay từ Hibernate/SQL)? Vui lòng kiểm tra giúp `SELECT version, script, success FROM flyway_schema_history WHERE version='107'` để tôi giữ nguyên tên/checksum file đã chạy và đổi file còn lại lên `V115`.
3. **Độ chính xác phần giây tại biên `+15 phút` và `+30 phút` (Mục 3.3 & 8.3):**
   - Với lịch `16:00`, lúc `16:15:01` và `16:30:01` (nếu có giây lẻ), bạn muốn:
     - **Phương án A (Theo phút trọn vẹn - Khuyến nghị):** Làm tròn xuống phút (`truncatedTo(MINUTES)`) → từ `16:15:00` đến `16:15:59` vẫn là phút `16:15` (`ON_TIME`), từ `16:16:00` tính `LATE_BOOKING`; từ `16:30:00` đến `16:30:59` vẫn tiếp nhận muộn, từ `16:31:00` trở đi mới quá 30p/`NO_SHOW`.
     - **Phương án B (Chính xác tới từng giây):** Quá `16:15:00` một giây (`16:15:01`) đã là `LATE_BOOKING` và `16:30:01` đã quá 30p/`NO_SHOW` (khớp với `LocalDateTime.now().isAfter(...)` đang có trong code cũ).
4. **Cấp số hiển thị cho bệnh nhân `EARLY_WAITING` (đến trước `-15p`, ví dụ `15:44`):**
   - Nên: (A) Chưa cấp số `P-` vội lúc `15:44` (chỉ giữ trạng thái `EARLY_WAITING`, đến `15:45` tự cấp số `P-xx` và xếp Nhóm 2 theo `checkInAt = 15:44`), hay (B) Cấp ngay số thường (`xx`) lúc `15:44` rồi lúc `15:45` tự đổi thành `P-xx` (hoặc cấp `P-xx` từ đầu nhưng gắn cờ chưa được gọi trước `15:45`)? *(Kiến nghị Phương án C: Cấp ngay mã nhận diện/số chờ `E-xx` hoặc `P-xx` với trạng thái `EARLY_WAITING` & `eligiblePriorityAt = expectedTime - 15m` để bệnh nhân có số in cầm tay tại quầy ngay lúc `15:44`, đến `15:45` tự mở khóa gọi theo Nhóm 2 mà không phải in lại phiếu cho bệnh nhân!)*
5. **Chính sách nút `Priority` (khác `Emergency`) & Bác sĩ nghỉ đột xuất:**
   - Nút `priority` thông thường trên API quầy check-in: bạn muốn **giữ lại** (nâng ưu tiên riêng có phân quyền + lý do + audit) hay **gỡ bỏ hoàn toàn** để hệ thống tuân thủ thuần túy 4 nhóm ưu tiên (`Emergency -> On-Time -> Walk-in -> Late`)?
   - Khi duyệt đơn nghỉ phép (`LeaveRequestService.approveLeaveRequest`), các lịch `PENDING/CONFIRMED` bị trùng ca: nên **giữ lịch và gắn cờ `NEEDS_REASSIGNMENT` / thông báo Lễ tân gọi đổi bác sĩ** thay vì **auto-cancel hàng loạt** như hiện nay, đúng chứ?
   - Khi tiếp nhận **Walk-in**, nếu là trường hợp **`EMERGENCY` cấp cứu** sau giờ đóng cửa một chút (hoặc bác sĩ đã đạt 8 người), có cho phép cấp cứu ngoại lệ vượt giới hạn `8` ca không hay cũng áp dụng chung quy tắc?
6. **Xác nhận cập nhật 3 test cũ trong `AppointmentSlotServiceTest`:**
   - Chốt chuyển `maxConcurrentPerDoctorSlot` về `1` nghĩa là các test cũ đang kỳ vọng 1 bác sĩ nhận cùng lúc 3 khách ở `08:00` / `15:30` trong `AppointmentSlotServiceTest` sẽ cần điều chỉnh assertion sang `1` (hoặc test kịch bản 2 bác sĩ khi còn nhiều chỗ tại 1 mốc giờ). Bạn đồng ý điều chỉnh theo quy tắc mới này chứ?

---

## 6. DANH SÁCH KIỂM THỬ BẮT BUỘC SẼ TRIỂN KHAI (MỤC 8)

Sẽ bổ sung đủ cả **Unit Test** và **Integration Test với H2 MODE=MySQL + JPA Transaction + Concurrency (`CyclicBarrier`/`ExecutorService`) + MockMvc Security**:
- **8.1 Guest & Hồ sơ:** Đủ 8 kịch bản (Guest SĐT mới -> Lead; Guest SĐT đã có User; Guest SĐT hồ sơ người khác -> 409 không lộ PII; Logged-in bản thân/phụ thuộc; Giả `profileId`; Request lặp `requestKey`; Concurrent đồng thời; Chống spam rate-limit).
- **8.2 Sức chứa & Slot:** Đủ 10 kịch bản (2 khách đặt cùng bác sĩ cùng giờ -> 1 người thành công, 1 người 409; 2 lễ tân tạo đồng thời; 2 dịch vụ 20p & 45p; Trùng ghế / vượt trần ghế chi nhánh; Lịch linh hoạt + lịch giờ cụ thể cùng buổi; Không ca trực; Nghỉ phép; Khác cơ sở; Quá giờ đóng cửa; Tái khám).
- **8.3 Mười mốc Check-in biên (`16:00`):** `15:44`, `15:45`, `15:59`, `16:00`, `16:15`, `16:15:01`, `16:16`, `16:30`, `16:30:01`, `16:31` + chuyển trạng thái tự động từ `EARLY_WAITING` sang `ON_TIME` tại `15:45`.
- **8.4 Check-in, Phân quyền & Ghế:** `PENDING`, `CONFIRMED`, `CANCELLED`, `NO_SHOW`, quá 30p, bác sĩ nghỉ phép (chỉ định thay thế bởi điều phối vs cảnh báo cho lễ tân thường), bác sĩ đủ sức chứa, Walk-in ngoài giờ, Walk-in không bác sĩ/quá tải, Đến sớm, Cấp cứu (lỗi quyền `APPOINTMENT_VIEW`, thu hồi cấp cứu, audit log), 2 lễ tân check-in cùng 1 Appointment (chỉ sinh 1 Visit), 2 lễ tân phân cùng 1 ghế khi `START` (chỉ 1 người vào ghế).
- **8.5 Hàng đợi đa màn hình:** Bộ dữ liệu đủ 6 loại (`EMERGENCY`, `EARLY_WAITING`, `ON_TIME`, `WALK_IN`, `LATE_BOOKING`, `WAITING_RESULT`) đối chiếu đồng nhất giữa `ReceptionService.dashboard`, `DispatchService.board`, `QueueWorkflowService.board`, `DoctorPatientRestController` (`/daily-visits`, `/waiting-queue`) và `ReceptionAccess` branch scope (403 khi thiếu chi nhánh / sai chi nhánh).

---

## 7. THỨ TỰ TRIỂN KHAI AN TOÀN (MỤC 9 & 12)

Sau khi bạn phê duyệt Mục 5:
1. **Giai đoạn 2 – Sửa P0 (từng nhóm nhỏ, chạy test & kiểm `git diff` sau mỗi nhóm):**
   - Bước 2.1: `ReceptionAccess`, `PatientAccess`, `ChatServiceImpl` (khóa phân quyền chi nhánh) + Test Security.
   - Bước 2.2: Schema Migration (`V107` resolution + `booking_consultation_requests` + `visits.arrival_timing` + preflight DB constraints) + `PublicBookingRestController` Guest/OTP-free security + Home Drawer JS/UI.
   - Bước 2.3: `AppointmentSlotService` (chuyển `maxConcurrent = 1`, branch chair ceiling, flexible slots `expectedTime == null`), `AppointmentPolicyServiceImpl`, `PortalAppointmentService`, `ReceptionAppointmentService`, `ReExaminationServiceImpl`.
   - Bước 2.4: Chuẩn hóa thời gian biên (giây/phút), `EARLY_WAITING` & cơ chế tự nâng hạng lúc `-15 phút` + Test 10 mốc biên.
2. **Giai đoạn 3 – Sửa P1:**
   - Bước 3.1: Xử lý bác sĩ nghỉ đột xuất (`LeaveRequestService` + Check-in bác sĩ thay thế).
   - Bước 3.2: Hoàn thiện Walk-in + `DoctorPatientRestController.openOrCreateVisitForPatient` + thứ tự Lock chống AB-BA deadlock + khóa `DentalChair`.
   - Bước 3.3: Quyền & Thu hồi Priority/Emergency + tắt `ALLOW_ADMIN_DESK_ACCESS_FOR_TESTING`.
   - Bước 3.4: Thống nhất Comparator Hàng đợi Tiếp đón trên 5 màn hình + tách `WAITING_RESULT`.
   - Bước 3.5: Ẩn danh TV công cộng + Sửa Realtime WebSocket cục bộ (không phụ thuộc CDN + resync DB) + đồng bộ UI Check-in/Queue/Dispatch.
3. **Giai đoạn 4 – Kiểm thử tổng thể & Báo cáo cuối cùng:**
   - Chạy toàn bộ Unit Test, Integration Test, Concurrency Test, Security Test, Build kiểm chứng và xuất báo cáo `CV_DCMS_APPOINTMENT_RECEPTION_REMEDIATION_REPORT.md` (kèm `git diff summary`).
