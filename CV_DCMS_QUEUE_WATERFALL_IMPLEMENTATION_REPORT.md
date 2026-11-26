# CV-DCMS Queue Waterfall — Implementation Report

## 1. Tổng quan thay đổi
Hệ thống đã được chuyển sang mô hình **Waterfall Queue**: bệnh nhân hàng đợi chung (`!isDoctorRequested`) không bị gán bác sĩ cố định khi check-in hoặc khi đang `WAITING`, luôn được xét theo thứ tự `waitingQueueComparator()` trên toàn bảng hàng đợi chi nhánh khi có bác sĩ rảnh, và chỉ được gán `primaryDoctor` + phòng/ghế tại thời điểm chuyển sang `CALLED`.

## 2. Chi tiết thay đổi theo tầng

### 2.1. Database & Entity (`V121` & `Appointment`)
- `src/main/resources/db/migration/V121__appointment_doctor_requested_flag.sql`:
  - Thêm cột `is_doctor_requested TINYINT(1) NOT NULL DEFAULT 0` vào bảng `appointments`.
  - Đồng bộ dữ liệu demo trên `cv_dcms_v118_verify` (đánh dấu `is_doctor_requested = 1` cho `DEMO-XQUANG-01` và đặt `primary_doctor_id = NULL` cho các lượt `WAITING` vãng lai hiện tại).
- `src/main/java/com/cvdcms/entity/Appointment.java` (`L50-55`):
  - Bổ sung field `@Column(name = "is_doctor_requested", nullable = false) private Boolean isDoctorRequested = false;`.

### 2.2. Ghi nhận chính xác cờ `isDoctorRequested` khi đặt lịch / xác nhận / đổi lịch
- `src/main/java/com/cvdcms/service/PortalAppointmentService.java` (`L297`, `L445`, `L673`)
- `src/main/java/com/cvdcms/service/ReceptionAppointmentService.java` (`L506`, `L730-734`, `L821-823`)
- `src/main/java/com/cvdcms/controller/PublicBookingRestController.java` (`L869`): `appointment.setIsDoctorRequested(form.getDoctorId() != null)`
- `src/main/java/com/cvdcms/service/ResourceBookingTransactionService.java` (`L367`, `L1022`): `!request.allowDoctorAutoAssign()`

### 2.3. Check-in & Cấp số thứ tự (`ReceptionService`)
- `src/main/java/com/cvdcms/service/ReceptionService.java`:
  - `checkInAppointment` (`L194-210`) & `confirmAndCheckIn` (`L319-334`): chỉ copy `appointment.getDoctor()` sang `visit.setPrimaryDoctor(...)` khi `Boolean.TRUE.equals(appointment.getIsDoctorRequested()) && appointment.getDoctor() != null`; bỏ fallback `autoAssignDoctorOnCheckIn` và bỏ điều kiện `visit.getPrimaryDoctor() != null` trước `queueCoordinator.issueTicketForVisitIfEligible(...)`.
  - `checkInWalkIn` (`L467-470`): bỏ `autoAssignDoctorOnCheckIn(...)` và bỏ điều kiện `visit.getPrimaryDoctor() != null` trước `issueTicketForVisitIfEligible(...)`.

### 2.4. Điều phối Waterfall Queue (`ClinicQueueCoordinatorService`)
- `src/main/java/com/cvdcms/service/ClinicQueueCoordinatorService.java`:
  - `issueTicketForVisitIfEligible`: cho phép `visit.getPrimaryDoctor() == null`, gọi `autoCallNextForAnyIdleDoctorLocked` khi `doctorId == null`.
  - `autoCallNextForDoctorIfIdleLocked`: duyệt toàn bộ `tickets.findForBoard(...)` trong chi nhánh theo đúng thứ tự `waitingQueueComparator()`, kiểm tra điều kiện phục vụ qua `canDoctorServeVisit(branchId, doctorId, visit, today)`. Cấp phát ghế/phòng (`allocateChairForCallLocked`) TRƯỚC khi gán `primaryDoctor`, đồng bộ thông tin khám và chuyển sang `CALLED`.
  - `canDoctorServeVisit` / `isWaterfallPullable`: bảo vệ ngoại lệ đặt riêng bác sĩ (`Boolean.TRUE.equals(appt.getIsDoctorRequested())`) và kiểm tra phân quyền dịch vụ (`doctorServicePermissions`) khi bác sĩ có cấu hình quyền dịch vụ riêng.
  - Cập nhật `reconcileTodayWaitingQueuesOnStartup`, `autoCallNextForAnyIdleDoctor`, `promoteEligibleEarlyWaitingTickets` để hỗ trợ ticket `primaryDoctor == null`.

### 2.5. TV Display & Turnover (`QueueWorkflowService` & `queue-tv.html`)
- `src/main/java/com/cvdcms/service/QueueWorkflowService.java`:
  - `buildPublicQueueList` (`L311-348`): chỉ gắn `BOOKED_DOCTOR` khi `isDoctorRequested == true`; các ticket `WAITING` hàng đợi chung (`!isCallingOrExam`) trả về `doctorDisplay = "Chưa phân công"`, `doctorAssignmentType = "UNSPECIFIED"`, `doctorStatusLabel = "Chưa phân công"`.
  - `completeChairTurnover` (`L559`): gọi `queueCoordinator.autoCallNextForAnyIdleDoctor(branchId, scope.user().getUserId())`.
- `src/main/resources/templates/reception/queue-tv.html` (`L609-614`):
  - SSR sử dụng `row.doctorDisplay`, `row.doctorAssignmentType`, `row.doctorStatusLabel` với fallback `"Chưa phân công"`.
