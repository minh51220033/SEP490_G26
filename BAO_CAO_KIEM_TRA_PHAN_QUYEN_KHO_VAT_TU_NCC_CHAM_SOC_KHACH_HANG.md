# BÁO CÁO KIỂM TRA, SỬA LỖI VÀ ĐỒNG BỘ PHÂN QUYỀN HỆ THỐNG CV-DCMS
**Phạm vi kiểm tra & nâng cấp:**
1. Sidebar – Tin nhắn trực tuyến (`/chat`, `fragments/sidebar.html`).
2. Quản lý kho vật tư (`/inventory`, `/api/inventory/**`, `/api/materials/**`).
3. Quản lý nhà cung cấp & công nợ (`/suppliers`, `/api/suppliers/**`, `/api/inventory/supplier-payments/**`).
4. Quản lý chăm sóc khách hàng (`/customer-care`, `/api/customer-care/**`).

---

## MỤC 1. NGUYÊN NHÂN VÀ CÁCH XỬ LÝ MỤC “TIN NHẮN TRỰC TUYẾN” BỊ TRÙNG Ở SIDEBAR

### 1.1. Vị trí phát hiện trong Source Code
- **File:** `src/main/resources/templates/fragments/sidebar.html`
- **Vị trí dòng lỗi trước khi sửa:** Dòng `66–79` trong nhóm menu `CHUNG`.

### 1.2. Nguyên nhân kỹ thuật
Trong `fragments/sidebar.html`, tồn tại song song 2 thẻ `<a th:href="@{/chat}">` cùng hiển thị nhãn `"Tin nhắn trực tuyến"`:
1. **Dòng 66–72 (mục tĩnh cũ):** Chỉ có icon `bi-chat-dots` và nhãn `"Tin nhắn trực tuyến"`, không gắn phần tử badge thông báo tin nhắn chưa đọc.
2. **Dòng 73–79 (mục đang hoạt động thực tế):** Có đầy đủ `th:classappend="${active == 'chat'} ? 'is-active' : ''"`, gắn trực tiếp `<span id="sidebarChatUnreadBadge" data-chat-unread-badge ...>` được đồng bộ thời gian thực bởi `static/js/chat-widget.js` và `GlobalModelAttributesAdvice`.

### 1.3. Cách xử lý
- Đã xóa mục tĩnh thừa ở dòng `66–72` trong `src/main/resources/templates/fragments/sidebar.html`.
- Giữ lại duy nhất mục `Tin nhắn trực tuyến` có `id="sidebarChatUnreadBadge"` và `data-chat-unread-badge`, đảm bảo hoạt động đồng nhất cho mọi vai trò (`ADMIN`, `LE_TAN`, `BAC_SI`, `QUAN_LY_KHO`, `KE_TOAN`).

---

## MỤC 2. NGUYÊN NHÂN VÌ SAO TÀI KHOẢN QUẢN LÝ KHO BỊ THIẾU TAB TRONG MODULE QUẢN LÝ KHO VẬT TƯ

### 2.1. Truy vết Source Code
1. **Frontend Template (`src/main/resources/templates/inventory/list.html` – dòng `139–156`):**
   Thanh điều hướng 8 tab kiểm tra từng mã quyền chi tiết (`permissionCodes`) độc lập cho mỗi tab:
   - Tab 1 (`Tồn kho`): `INVENTORY_STOCK`
   - Tab 2 (`Lô & Hạn dùng`): `INVENTORY_BATCHES`
   - Tab 3 (`Phiếu nhập kho`): `INVENTORY_RECEIPT`
   - Tab 4 (`Phiếu xuất kho`): `INVENTORY_ISSUE`
   - Tab 5 (`Điều chuyển kho`): `INVENTORY_TRANSFER`
   - Tab 6 (`Danh mục vật tư`): `INVENTORY_MATERIALS`
   - Tab 7 (`Nhóm vật tư`): `INVENTORY_CATEGORIES`
   - Tab 8 (`NCC & Công nợ`): `SUPPLIER_MANAGE`
2. **Backend Seeder (`src/main/java/com/cvdcms/config/PermissionDataInitializer.java` – dòng `218–228`) & SQL Seed (`database_seed_full_scenarios.sql` – dòng `86–97`):**
   - Trong `database_seed_full_scenarios.sql`, vai trò `QUAN_LY_KHO` (`role_id = 4`) trước đó chỉ được gán 2 quyền `INVENTORY_STOCK` và `SUPPLIER_MANAGE`.
   - Trong `PermissionDataInitializer.java` (dòng `221`), điều kiện `if (role.getPermissions() == null || role.getPermissions().isEmpty())` khiến hệ thống bỏ qua việc bổ sung 6 mã quyền mới (`INVENTORY_BATCHES`, `INVENTORY_RECEIPT`, `INVENTORY_ISSUE`, `INVENTORY_TRANSFER`, `INVENTORY_MATERIALS`, `INVENTORY_CATEGORIES`) nếu vai trò `QUAN_LY_KHO` trong CSDL đã có sẵn ít nhất 1 quyền cũ.
3. **Interceptor & Repository (`FeaturePermissionInterceptor.java`, `UserRepository.java`):**
   - Đường dẫn `/api/materials` chưa được khai báo tường minh trong `FeaturePermissionInterceptor.resolveRequiredPermissions`.
   - Truy vấn `UserRepository.hasActiveSupplierManagementRole` (dòng `348`) chỉ kiểm tra `UPPER(r.roleName) IN ('ADMIN', 'ROLE_ADMIN')`, chưa đưa `'QUAN_LY_KHO', 'ROLE_QUAN_LY_KHO'` vào mệnh đề kiểm tra trực tiếp theo role.
4. **Template `inventory/list.html` nạp trùng file script:**
   - Dòng `1400` và dòng `1492` cùng nạp `<script th:src="@{/js/inventory.js...}" defer></script>`.

---

## MỤC 3. CÁCH ĐỒNG BỘ 8 TAB VÀ PHÂN QUYỀN GIỮA ADMIN VÀ QUẢN LÝ KHO

1. **`PermissionDataInitializer.java`:**
   - Bỏ điều kiện chặn `isEmpty()` ở nhánh `QUAN_LY_KHO` / `ROLE_QUAN_LY_KHO`, luôn đồng bộ đầy đủ 8 quyền kho & nhà cung cấp (`INVENTORY_STOCK`, `INVENTORY_BATCHES`, `INVENTORY_RECEIPT`, `INVENTORY_ISSUE`, `INVENTORY_TRANSFER`, `INVENTORY_MATERIALS`, `INVENTORY_CATEGORIES`, `SUPPLIER_MANAGE`) cho vai trò `QUAN_LY_KHO` khi ứng dụng khởi động.
2. **`database_seed_full_scenarios.sql`:**
   - Bổ sung đầy đủ các mã quyền `405` -> `410` và gán trọn bộ 8 quyền kho vào bảng `role_permissions` cho `QUAN_LY_KHO` (`role_id = 4`).
3. **`src/main/resources/templates/inventory/list.html`:**
   - Bổ sung biến `isWarehouseManager=${isAdmin or (roleNames != null and (#lists.contains(roleNames, 'QUAN_LY_KHO') or #lists.contains(roleNames, 'ROLE_QUAN_LY_KHO')))}` cho cả 8 tab chức năng và 2 nút hành động trên Header (`Danh mục NCC`, `+ Thêm mới`).
   - Loại bỏ dòng `<script>` nạp trùng `inventory.js` ở dòng `1400`.
4. **`FeaturePermissionInterceptor.java` & `UserRepository.java`:**
   - Mở quyền truy cập `/api/materials/**`, `/suppliers/**`, `/api/suppliers/**`, `/api/inventory/supplier-payments/**` và `/api/inventory/**` cho `QUAN_LY_KHO`.
   - Cập nhật `UserRepository.hasActiveSupplierManagementRole` bao gồm `'QUAN_LY_KHO', 'ROLE_QUAN_LY_KHO'`.

---

## MỤC 4. CÁCH KIỂM SOÁT PHÂN QUYỀN THEO CƠ SỞ CHO QUẢN LÝ KHO VÀ ADMIN

### 4.1. Nguyên nhân lỗi rò rỉ chi nhánh trước khi sửa
- Trong `BranchService.getUserAccessibleBranches(Long userId)` (dòng `453–457`), tồn tại đoạn fallback:
  `if (branchMap.isEmpty() && !allActive.isEmpty())` tự động trả về toàn bộ các chi nhánh đang hoạt động nếu tài khoản chưa có bản ghi phân công chi nhánh.
- Khi `ADMIN` chuyển đổi cơ sở trên thanh Header (`branchSelectInline`), URL chuyển sang `?branchId=...` hoặc lưu vào `session.currentBranchId`, nhưng `inventory.js` và `suppliers.js` chưa ưu tiên đọc `branchId` từ URL query parameter.

### 4.2. Cơ chế kiểm soát 2 lớp (Frontend + Backend) sau khi sửa
1. **Đối với `ADMIN` (`branchAccessService.isAdmin(userId) == true`):**
   - Xem và thao tác trên toàn bộ các cơ sở đang hoạt động (`CN01`, `CN02`...).
   - Khi chuyển đổi cơ sở ở dropdown Header (`branchSelectInline`), `GlobalBranchControllerAdvice`, `inventory.js` và `suppliers.js` đồng bộ ngay `branchId` đang chọn, tự động tải lại 4 thẻ KPI tổng quan, danh sách tồn kho, lô & hạn dùng, phiếu nhập, phiếu xuất, điều chuyển kho và công nợ nhà cung cấp của đúng cơ sở đó.
2. **Đối với `QUAN_LY_KHO` (`isAdmin == false`):**
   - `BranchService.getUserAccessibleBranches(userId)` chỉ trả về duy nhất cơ sở được gán trong `users.branch_id` hoặc `staff_branch_assignments` đang `ACTIVE`. Nếu tài khoản có `branch_id = null` và không có phân công hợp lệ, hàm trả về danh sách rỗng `[]` và giao diện hiển thị thông báo cảnh báo chưa được phân công cơ sở (không gây lỗi 500).
   - Ở mọi API Backend (`BranchInventoryController`, `MaterialBatchController`, `StockReceiptServiceImpl`, `StockIssueServiceImpl`, `SupplierPaymentServiceImpl`), hàm `branchAccessService.canAccessBranch(userId, branchId)` luôn đối chiếu `userId` trong `HttpSession` với `branchId` gửi lên. Nếu `QUAN_LY_KHO` của `CN01` cố tình sửa URL/DevTools/Postman truyền `branchId=2` (`CN02`), Backend chặn ngay với mã lỗi **HTTP 403 FORBIDDEN**.

---

## MỤC 5. CÁCH XỬ LÝ PHÂN QUYỀN VÀ NGHIỆP VỤ TRONG QUẢN LÝ NHÀ CUNG CẤP & CÔNG NỢ

### 5.1. Luồng hoạt động thực tế
- **Giao diện:**
  - Trang độc lập `/suppliers` (`templates/suppliers/list.html` + `static/js/suppliers.js`).
  - Tab thứ 8 `NCC & Công nợ` ngay trong `/inventory` (`templates/inventory/list.html` + `static/js/inventory.js`).
- **Controller & Service:**
  - `SupplierController` (`/api/suppliers`) -> `SupplierServiceImpl`: Tìm kiếm, xem chi tiết, thêm mới (`POST /api/suppliers`), cập nhật (`PUT /api/suppliers/{id}`), ngừng hợp tác/kích hoạt lại.
  - `SupplierPaymentController` (`/api/inventory/supplier-payments`) -> `SupplierPaymentServiceImpl`:
    - `GET /api/inventory/supplier-payments/debts?branchId=...`: Tổng hợp số phiếu nhập, tổng tiền nhập (`totalImportAmount`), đã thanh toán (`totalPaidAmount`) và công nợ còn lại (`remainingDebt`) của từng nhà cung cấp tại cơ sở.
    - `GET /api/inventory/supplier-payments?branchId=...`: Lịch sử phiếu chi trả công nợ nhà cung cấp tại cơ sở.
    - `POST /api/inventory/supplier-payments`: Lập phiếu thanh toán công nợ nhà cung cấp (tự động sinh mã phiếu chi `PC...`).

### 5.2. Các ràng buộc an toàn ở Backend (`SupplierPaymentServiceImpl.createPayment`)
1. **Kiểm tra quyền & cơ sở:** Bắt buộc `accessService.canAccessBranch(userId, dto.getBranchId())` phải hợp lệ; nếu sai cơ sở trả về `403 FORBIDDEN`.
2. **Kiểm tra dữ liệu đầu vào:**
   - Số tiền thanh toán `amount > 0`.
   - Ngày thanh toán không được ở tương lai (`!paymentDate.isAfter(today)`).
   - Phương thức thanh toán chỉ nhận `CASH` hoặc `BANK_TRANSFER`.
   - Số chứng từ / UNC kiểm tra theo mẫu `^[A-Za-z0-9._/-]{1,100}$`.
3. **Khóa bi quan (Pessimistic Write Lock) & Transaction (`@Transactional`):**
   - Khóa bản ghi nhà cung cấp (`supplierRepository.findByIdForUpdate`) và phiếu nhập kho (`receiptRepository.findByIdForUpdate`) trong suốt giao dịch.
   - Kiểm tra số tiền thanh toán không vượt quá dư nợ còn lại của phiếu nhập (`receiptRemaining`) và không vượt quá tổng dư nợ còn lại của nhà cung cấp tại cơ sở (`totalRemaining`).
4. **Chống gửi trùng (Idempotency):**
   - Kiểm tra khóa `clientRequestId` trong cửa sổ 30 giây và kiểm tra giao dịch trùng số tiền trong 5 giây gần nhất (`existsRecentDuplicatePayment`).
5. **Tự động liên kết hồ sơ nhân sự (`resolveOrCreateStaffProfile`):**
   - Đã cập nhật ở cả `SupplierPaymentServiceImpl`, `StockReceiptServiceImpl` và `StockIssueServiceImpl` để hỗ trợ cả `ADMIN` và `QUAN_LY_KHO`, tránh lỗi 400 nếu tài khoản `QUAN_LY_KHO` mới tạo chưa có bản ghi `staff_profiles`.

---

## MỤC 6. NGUỒN DỮ LIỆU ĐẦU VÀO CỦA MODULE CHĂM SÓC KHÁCH HÀNG

Qua kiểm tra trực tiếp `CustomerCareTask.java`, `CustomerCareTaskRepository.java`, `CustomerCareServiceImpl.java`, `ReceptionAppointmentService.java` và `PortalAppointmentService.java`, dữ liệu trong bảng `customer_care_tasks` đến từ **4 nguồn thực tế**:

1. **Nguồn 1 – Tự động sinh khi Lễ tân đánh dấu Lịch hẹn là Vắng mặt (`NO_SHOW`):**
   - **File & Hàm:** `ReceptionAppointmentService.markNoShow` (dòng `535–548`).
   - **Cơ chế:** Khi lịch hẹn chuyển sang trạng thái `NO_SHOW`, các công việc `APPOINTMENT_REMINDER` đang chờ của lịch hẹn đó tự động chuyển sang `CANCELLED`; đồng thời hệ thống tự động tạo 1 bản ghi `CustomerCareTask` mới với `taskType = NO_SHOW_RECALL`, `status = PENDING`, `dueDate = LocalDate.now()`, gắn với `appointment`, `patientProfile` và `branch` của lịch hẹn.
2. **Nguồn 2 – Tổng hợp tự động theo cơ sở từ Ca khám (`Visit`), Đơn thuốc (`Prescription`) và Lịch hẹn (`Appointment`):**
   - **File & Hàm:** `CustomerCareRestController.autoGenerateTasks` (`POST /api/customer-care/tasks/auto-generate`) -> `CustomerCareServiceImpl.autoGenerateTasks(Long branchId, Long userId)` (dòng `815–942`).
   - **Cơ chế:** Quét dữ liệu thực tế tại cơ sở (`branchId`) để sinh 4 loại công việc CSKH (`POST_TREATMENT_FOLLOWUP`, `MEDICATION_REMINDER`, `APPOINTMENT_REMINDER`, `NO_SHOW_RECALL`).
3. **Nguồn 3 – Tạo lịch chăm sóc thủ công bởi Lễ tân / Bác sĩ / Admin:**
   - **File & Hàm:** `CustomerCareRestController.createTask` (`POST /api/customer-care/tasks`) -> `CustomerCareServiceImpl.createTask(CustomerCareCreateDTO dto, Long userId)` (dòng `659–714`).
   - **Cơ chế:** Cho phép tạo công việc CSKH gắn với bệnh nhân (`profileId`), cơ sở (`branchId`), phân nhóm chăm sóc (`POST_TREATMENT_FOLLOWUP`, `MEDICATION_REMINDER`, `APPOINTMENT_REMINDER`, `INSTALLMENT_REMINDER`, `NO_SHOW_RECALL`, `NO_SERVICE_FOLLOWUP`, `FEEDBACK_COMPLAINT`), hạn hoàn thành (`dueDate`), nhân viên phụ trách (`assignedStaffId`) và tùy chọn liên kết `visitId`, `prescriptionId`, `appointmentId`. Có kiểm tra chống tạo trùng (`assertNoActiveDuplicateTask`).
4. **Nguồn 4 – Khởi tạo trực tiếp từ màn hình Khám lâm sàng khi Bác sĩ/Lễ tân ghi nhận tương tác theo Ca khám:**
   - **File & Hàm:** `CustomerCareRestController.recordVisitInteraction` (`POST /api/customer-care/visits/{visitId}/interactions`) -> `CustomerCareServiceImpl.recordVisitInteraction` (dòng `1347–1394`).
   - **Cơ chế:** Nếu ca khám chưa có công việc CSKH đang mở, hệ thống tự động tạo `CustomerCareTask` loại `POST_TREATMENT_FOLLOWUP` thuộc đúng `visit.getBranch()` rồi lưu lịch sử tương tác vào `customer_care_interactions`.

---

## MỤC 7. LUỒNG HOẠT ĐỘNG CHI TIẾT CỦA CHỨC NĂNG “TỔNG HỢP TỰ ĐỘNG”

- **Nút trên giao diện:** `#autoGenerateBtn` (`"Tổng hợp tự động"`) trong `templates/customer-care/list.html`.
- **Sự kiện JS:** `static/js/customer-care.js` -> gọi `POST /api/customer-care/tasks/auto-generate?branchId=${state.branchId}`.
- **Controller:** `CustomerCareRestController.autoGenerateTasks(@RequestParam Long branchId, HttpSession session)` (dòng `120–126`).
- **Service:** `CustomerCareServiceImpl.autoGenerateTasks(Long branchId, Long userId)` (dòng `815–942`).
- **Kiểm tra phân quyền cơ sở:** Gọi `resolveAndAuthorizeBranchId(branchId, userId, true)`:
  - Nếu `ADMIN` chưa chọn cơ sở cụ thể (`branchId == null`), trả lỗi yêu cầu chọn 1 cơ sở cụ thể.
  - Nếu `LE_TAN` hoặc `BAC_SI` gửi `branchId` của cơ sở khác, chặn với lỗi `403 FORBIDDEN`.
- **4 quy tắc quét & chống tạo trùng trong `autoGenerateTasks`:**
  1. **Quét Ca khám hoàn thành (`VisitStatus.COMPLETED`):**
     - Truy vấn: `taskRepository.findCompletedVisitsByBranchForCare(effectiveBranchId)`.
     - Chống trùng: `!taskRepository.existsByVisit_VisitIdAndTaskType(v.getVisitId(), CustomerCareTaskType.POST_TREATMENT_FOLLOWUP)`.
     - Tạo task: `taskType = POST_TREATMENT_FOLLOWUP`, `dueDate = max(today, visitDate.plusDays(1))`, `status = PENDING`, ghi chú tự động tổng hợp từ mã ca khám, triệu chứng ban đầu và ghi chú điều trị.
  2. **Quét Đơn thuốc đã kê (`Prescription`):**
     - Truy vấn: `taskRepository.findValidPrescriptionsByBranchForCare(effectiveBranchId)`.
     - Chống trùng: `!taskRepository.existsByPrescription_PrescriptionIdAndTaskType(pr.getPrescriptionId(), CustomerCareTaskType.MEDICATION_REMINDER)`.
     - Tạo task: `taskType = MEDICATION_REMINDER`, `dueDate = COALESCE(pr.getFollowupDate(), today)`, `status = PENDING`, ghi chú tổng hợp từ mã đơn thuốc, chẩn đoán và lời dặn bác sĩ.
  3. **Quét Lịch hẹn sắp tới (`AppointmentStatus.CONFIRMED` hoặc `PENDING`):**
     - Truy vấn: `appointmentRepository.findByBranchWithDetails(effectiveBranchId)`.
     - Chống trùng: `!taskRepository.existsByAppointment_AppointmentIdAndTaskType(a.getAppointmentId(), CustomerCareTaskType.APPOINTMENT_REMINDER)`.
     - Tạo task: `taskType = APPOINTMENT_REMINDER`, `dueDate = appointmentDate.minusDays(1)`, `status = PENDING`.
  4. **Quét Lịch hẹn lỡ (`AppointmentStatus.NO_SHOW`):**
     - Chống trùng: `!taskRepository.existsByAppointment_AppointmentIdAndTaskType(a.getAppointmentId(), CustomerCareTaskType.NO_SHOW_RECALL)`.
     - Tạo task: `taskType = NO_SHOW_RECALL`, `dueDate = today`, `status = PENDING`.

---

## MỤC 8. LUỒNG HOẠT ĐỘNG CHI TIẾT CỦA CHỨC NĂNG “GHI NHẬN” (`GHI KQ`)

- **Nút trên giao diện:** Nút `Ghi KQ` (`data-action="interact"`) trên từng dòng công việc trong bảng CSKH (`static/js/customer-care.js`), mở modal `#interactionModal`.
- **Submit Form JS:** Hàm `submitInteractionForm` trong `static/js/customer-care.js` gửi `POST /api/customer-care/tasks/{taskId}/interactions` với payload `CustomerCareInteractionCreateDTO`:
  - `channel`: `PHONE_CALL` | `ZALO` | `SMS` | `IN_PERSON`
  - `contactResult`: `CONNECTED` | `NO_ANSWER` | `BUSY` | `SCHEDULED_APPOINTMENT`
  - `notes`: Nội dung cuộc gọi / phản hồi của bệnh nhân (`3–2000` ký tự)
  - `nextRetryAt`: Thời gian hẹn gọi lại (bắt buộc khi `contactResult` là `NO_ANSWER` hoặc `BUSY`)
  - `nextStatus`: Trạng thái công việc tiếp theo (tùy chọn)
- **Controller:** `CustomerCareRestController.recordInteraction` (dòng `128–136`).
- **Service:** `CustomerCareServiceImpl.recordInteraction(Long taskId, CustomerCareInteractionCreateDTO dto, Long userId)` (dòng `949–1026`):
  1. Kiểm tra công việc tồn tại và kiểm tra quyền cơ sở qua `assertCanAccessTask(task, userId)`.
  2. Kiểm tra điều kiện bắt buộc: nếu `contactResult` là `NO_ANSWER` hoặc `BUSY` mà thiếu `nextRetryAt` thì trả lỗi `400 BAD_REQUEST`.
  3. Tạo bản ghi lịch sử tương tác mới trong bảng `customer_care_interactions` (`CustomerCareInteraction`), gắn với `task`, `patientProfile`, và nhân viên thực hiện (`performer`).
  4. Tự động cập nhật trạng thái của `CustomerCareTask`:
     - Nếu `NO_ANSWER` hoặc `BUSY`: chuyển `task.status = IN_PROGRESS` và cập nhật `task.dueDate = nextRetryAt.toLocalDate()`.
     - Nếu `CONNECTED` hoặc `SCHEDULED_APPOINTMENT` (và không chỉ định `nextStatus` khác): chuyển `task.status = COMPLETED`.
  5. Trả về `CustomerCareTaskDTO` kèm danh sách `interactions` mới nhất; Frontend tự động cập nhật lại bảng công việc và 4 thẻ KPI.

---

## MỤC 9. LUỒNG HOẠT ĐỘNG CHI TIẾT CỦA CHỨC NĂNG “CHUYỂN ĐẾN BÁC SĨ” VÀ “PHẢN HỒI CỦA BÁC SĨ”

### 9.1. Luồng “Chuyển BS” (Escalate to Doctor)
- **Giao diện:** Khi bệnh nhân phản ánh đau nhức, sưng tấy, chảy máu hoặc thắc mắc chuyên môn sau điều trị, Lễ tân (hoặc Admin) bấm nút `Chuyển BS` (`data-action="escalate"`), mở modal `#escalateModal`.
- **Dữ liệu gửi lên:** `POST /api/customer-care/tasks/{taskId}/escalate` với `CustomerCareEscalateDTO` (`escalatedToDoctorId`, `escalationReason` từ `5–2000` ký tự).
- **Xử lý Backend (`CustomerCareServiceImpl.escalateToDoctor` – dòng `1033–1069`):**
  1. Kiểm tra `assertCanAccessTask(task, userId)` (đảm bảo Lễ tân chỉ chuyển ca thuộc đúng cơ sở của mình).
  2. Kiểm tra hồ sơ Bác sĩ nhận bàn giao (`doctor.getStatus() == StaffStatus.ACTIVE`).
  3. Cập nhật `CustomerCareTask`:
     - `task.setEscalatedToDoctor(doctor)`
     - `task.setEscalatedAt(LocalDateTime.now())`
     - `task.setStatus(CustomerCareTaskStatus.ESCALATED_TO_DOCTOR)`
     - `task.setNotes(reason)`
  4. Sau khi lưu, ca chăm sóc xuất hiện ngay trong bộ lọc/KPI **"Ca chuyển Bác sĩ"** (`ESCALATED`) tại cơ sở đó.

### 9.2. Luồng “Phản hồi” của Bác sĩ (Doctor Response)
- **Giao diện:** Bác sĩ (hoặc Admin) tại cơ sở thấy nút `Phản hồi` (`data-action="doctor-response"`) trên dòng công việc, mở modal `#doctorResponseModal`.
- **Dữ liệu gửi lên:** `POST /api/customer-care/tasks/{taskId}/doctor-response` với `CustomerCareEscalateDTO` (`doctorResponse` từ `5–2000` ký tự, `nextStatus`).
- **Xử lý Backend (`CustomerCareServiceImpl.submitDoctorResponse` – dòng `1072–1121`):**
  1. Kiểm tra quyền cơ sở qua `assertCanAccessTask(task, userId)`.
  2. **Kiểm tra vai trò nghiêm ngặt (dòng `1093–1098`):**
     Nếu người gọi có vai trò `LE_TAN`, Backend chặn ngay với lỗi **HTTP 403 FORBIDDEN**: `"Chỉ Bác sĩ hoặc Admin tại cơ sở mới có quyền nhập phản hồi / chỉ định chuyên môn (doctor_response)."`.
  3. Cập nhật `task.setDoctorResponse(responseText)`, gán `escalatedToDoctor` (nếu chưa có) và cập nhật trạng thái công việc (`nextStatus`, ví dụ `IN_PROGRESS` để Lễ tân gọi lại thông báo chỉ định của Bác sĩ cho bệnh nhân, hoặc `COMPLETED`).
  4. Lễ tân và Admin tại cùng cơ sở nhìn thấy ngay nội dung phản hồi chuyên môn của Bác sĩ trên dòng công việc và trong Timeline lịch sử chăm sóc.

---

## MỤC 10. KẾT QUẢ KIỂM TRA PHÂN QUYỀN THEO CƠ SỞ TRONG CHĂM SÓC KHÁCH HÀNG

- **Nguyên nhân lỗi trước khi sửa:**
  Trong `CustomerCareServiceImpl.resolveAllowedBranches(Long userId, String careRole)` (dòng `205–219`), khi tài khoản nhân sự chưa có `explicitIds` hoặc suy diễn từ dữ liệu cũ, hàm rơi vào `return allActive;`, khiến tài khoản chưa gắn cơ sở hoặc dữ liệu seed cũ có thể nhìn thấy công việc của cơ sở khác.
- **Cách khắc phục:**
  Đã sửa `CustomerCareServiceImpl.resolveAllowedBranches` để đối với `LE_TAN` và `BAC_SI`, hệ thống **chỉ** lấy danh sách cơ sở được phân công chính thức (`taskRepository.findExplicitBranchIdsByUserId(userId)` từ `users.branch_id` và `staff_branch_assignments` đang `ACTIVE`). Nếu không có cơ sở được phân công, trả về `Collections.emptyList()`.
- **Kết quả đạt được:**
  - `LE_TAN` CN01 (`letan01`) chỉ xem, tổng hợp tự động, ghi nhận kết quả và chuyển Bác sĩ đối với bệnh nhân/công việc của `CN01`. Nếu truyền `branchId=2` (`CN02`) qua API, Backend trả về `403 FORBIDDEN`.
  - `BAC_SI` CN01 (`bacsi01`, `bacsi02`) chỉ xem và phản hồi chuyên môn các ca của `CN01`.
  - `LE_TAN` CN02 (`letan02`) và `BAC_SI` CN02 (`bacsi03`, `bacsi04`) chỉ xem và xử lý các ca của `CN02`.
  - `ADMIN` (`admin`) có quyền xem `"Tất cả cơ sở / chi nhánh"` hoặc chuyển đổi giữa `CN01` và `CN02`.

---

## MỤC 11. DANH SÁCH TẤT CẢ CÁC FILE ĐÃ CHỈNH SỬA VÀ NỘI DUNG THAY ĐỔI

1. **`src/main/resources/templates/fragments/sidebar.html`**
   - Xóa mục menu `"Tin nhắn trực tuyến"` tĩnh bị lặp (dòng `66–72`), giữ lại duy nhất mục `"Tin nhắn trực tuyến"` có gắn badge đếm tin nhắn chưa đọc `#sidebarChatUnreadBadge`.
2. **`src/main/java/com/cvdcms/config/PermissionDataInitializer.java`**
   - Đồng bộ đầy đủ 8 quyền kho & nhà cung cấp (`INVENTORY_STOCK`, `INVENTORY_BATCHES`, `INVENTORY_RECEIPT`, `INVENTORY_ISSUE`, `INVENTORY_TRANSFER`, `INVENTORY_MATERIALS`, `INVENTORY_CATEGORIES`, `SUPPLIER_MANAGE`) cho vai trò `QUAN_LY_KHO` và quyền `CUSTOMER_CARE_MANAGE` cho `LE_TAN`, `BAC_SI`.
3. **`src/main/java/com/cvdcms/config/FeaturePermissionInterceptor.java`**
   - Bổ sung ánh xạ quyền cho `/api/materials` và đồng bộ quyền truy cập `/suppliers`, `/api/suppliers`, `/api/inventory/supplier-payments` cho vai trò `QUAN_LY_KHO`.
4. **`src/main/java/com/cvdcms/repository/UserRepository.java`**
   - Cập nhật `hasActiveSupplierManagementRole` bao gồm `'QUAN_LY_KHO', 'ROLE_QUAN_LY_KHO'`.
5. **`src/main/java/com/cvdcms/service/BranchService.java`**
   - Loại bỏ đoạn fallback trả về toàn bộ chi nhánh (`allActive`) trong `getUserAccessibleBranches(Long userId)` khi tài khoản nhân viên không phải Admin và chưa được gán cơ sở.
6. **`src/main/java/com/cvdcms/config/GlobalBranchControllerAdvice.java`**
   - Tự động gán `currentBranchId` mặc định về cơ sở đầu tiên được phép truy cập khi vào `/inventory` hoặc `/suppliers` để đồng bộ nhãn cơ sở trên Header với dữ liệu hiển thị.
7. **`src/main/java/com/cvdcms/service/impl/SupplierPaymentServiceImpl.java`**
   - Cập nhật `resolveOrCreateStaffProfile` hỗ trợ cả `ADMIN` và `QUAN_LY_KHO`.
8. **`src/main/java/com/cvdcms/service/impl/StockReceiptServiceImpl.java`**
   - Cập nhật `resolveOrCreateStaffProfile` hỗ trợ cả `ADMIN` và `QUAN_LY_KHO`.
9. **`src/main/java/com/cvdcms/service/impl/StockIssueServiceImpl.java`**
   - Cập nhật `resolveOrCreateStaffProfile` hỗ trợ cả `ADMIN` và `QUAN_LY_KHO`.
10. **`src/main/java/com/cvdcms/service/impl/CustomerCareServiceImpl.java`**
    - Siết chặt `resolveAllowedBranches` chỉ trả về cơ sở được phân công chính thức cho `LE_TAN` và `BAC_SI`; trả về danh sách rỗng nếu tài khoản chưa được phân công cơ sở.
11. **`src/main/resources/templates/inventory/list.html`**
    - Đồng bộ điều kiện hiển thị cả 8 tab chức năng kho và các nút Header cho `QUAN_LY_KHO` (`isWarehouseManager`); xóa thẻ `<script>` nạp trùng `inventory.js`.
12. **`src/main/resources/templates/suppliers/list.html`**
    - Gắn `th:attr="data-current-branch-id=${currentBranchId}"` vào thẻ `<body>` để đồng bộ cơ sở đang chọn.
13. **`src/main/resources/static/js/inventory.js`**
    - Ưu tiên đọc `branchId` từ URL parameter / `data-current-branch-id` khi Admin chuyển đổi cơ sở.
14. **`src/main/resources/static/js/suppliers.js`**
    - Đồng bộ `currentBranchId` theo URL parameter / `data-current-branch-id` và hiển thị cảnh báo rõ ràng khi tài khoản chưa được phân công cơ sở.
15. **`src/main/resources/static/js/customer-care.js`**
    - Xử lý an toàn trường hợp tài khoản nhân sự chưa được phân công cơ sở (`branches.length === 0`), hiển thị thông báo rõ ràng và không gây lỗi 500.
16. **`database_seed_full_scenarios.sql`**
    - Bổ sung đầy đủ 8 quyền module `INVENTORY` cho `QUAN_LY_KHO` và quyền `CUSTOMER_CARE_MANAGE` cho `LE_TAN`, `BAC_SI`.

---

## MỤC 12. KẾT QUẢ KIỂM THỬ THỰC TẾ (16 TRƯỜNG HỢP BẮT BUỘC)

| STT | Vai trò / Tài khoản | Hành động kiểm tra | Kết quả mong đợi | Kết quả thực tế |
|---|---|---|---|---|
| 1 | Admin (`admin`) | Xem Sidebar | Chỉ có 1 mục Tin nhắn trực tuyến (kèm badge chưa đọc) | **PASS** |
| 2 | Quản lý kho CN01 (`quanlykho01`) | Vào Quản lý kho vật tư (`/inventory`) | Hiển thị đầy đủ 8 tab kho & NCC giống Admin | **PASS** |
| 3 | Quản lý kho CN01 (`quanlykho01`) | Xem dữ liệu kho | Chỉ thấy dữ liệu tồn kho, lô, phiếu nhập/xuất, công nợ của CN01 | **PASS** |
| 4 | Quản lý kho CN02 (`quanlykho02`) | Xem dữ liệu kho | Chỉ thấy dữ liệu tồn kho, lô, phiếu nhập/xuất, công nợ của CN02 | **PASS** |
| 5 | Quản lý kho CN01 (`quanlykho01`) | Cố truy cập dữ liệu CN02 qua request (`?branchId=2`) | Backend từ chối đúng quyền (`403 FORBIDDEN`) | **PASS** |
| 6 | Quản lý kho CN01 (`quanlykho01`) | Thêm nhà cung cấp mới (`POST /api/suppliers`) | Tạo thành công, lưu vào bảng `suppliers` | **PASS** |
| 7 | Quản lý kho CN01 (`quanlykho01`) | Lập phiếu thanh toán công nợ NCC hợp lệ | Thành công, công nợ giảm đúng số tiền thanh toán, lưu lịch sử `PC...` | **PASS** |
| 8 | Quản lý kho CN01 (`quanlykho01`) | Thanh toán vượt công nợ còn lại | Backend chặn và báo lỗi rõ ràng (`400 BAD_REQUEST`) | **PASS** |
| 9 | Admin (`admin`) | Chuyển giữa CN01 và CN02 ở Quản lý kho | Dữ liệu KPI, tồn kho, lô, phiếu và công nợ thay đổi đúng theo cơ sở | **PASS** |
| 10 | Lễ tân CN01 (`letan01`) | Vào Chăm sóc khách hàng (`/customer-care`) | Chỉ thấy danh sách CSKH và thống kê của CN01 | **PASS** |
| 11 | Lễ tân CN01 (`letan01`) | Bấm Tổng hợp tự động | Tạo task đúng quy tắc từ ca khám/đơn thuốc/lịch hẹn CN01, không tạo trùng | **PASS** |
| 12 | Lễ tân CN01 (`letan01`) | Bấm Ghi nhận (`Ghi KQ`) | Lưu lịch sử liên hệ vào `customer_care_interactions` và cập nhật trạng thái đúng | **PASS** |
| 13 | Lễ tân CN01 (`letan01`) | Bấm Chuyển BS | Chuyển đúng cho bác sĩ актив tại CN01, trạng thái đổi sang `ESCALATED_TO_DOCTOR` | **PASS** |
| 14 | Bác sĩ CN01 (`bacsi01`) | Bấm Phản hồi | Lưu phản hồi chuyên môn (`doctor_response`) và đồng bộ về màn CSKH CN01 | **PASS** |
| 15 | Bác sĩ CN02 (`bacsi03`) | Cố phản hồi ca của CN01 | Backend từ chối (`403 FORBIDDEN`) | **PASS** |
| 16 | Tài khoản không gắn cơ sở (`branch_id = null`) | Truy cập kho hoặc CSKH | Hiển thị thông báo chưa được phân công cơ sở, không lỗi 500 | **PASS** |
