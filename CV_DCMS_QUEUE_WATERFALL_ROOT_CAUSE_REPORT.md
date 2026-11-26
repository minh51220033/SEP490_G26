# CV-DCMS Queue Waterfall — Root Cause Report

## 1. Hiện tượng thực tế
Tại màn hình TV gọi số `http://localhost:8089/reception/queue/tv/1`:
- Phiếu `05` (`Bệnh nhân 7`) đang ở trạng thái `WAITING` (`Đang chờ`) nhưng đã hiển thị bác sĩ phụ trách cố định là `BS Trần B` (`Đã được phân bổ`).
- Phiếu `07` (`Dinh Xuan Hieu`) đang ở trạng thái `WAITING` (`Đang chờ`) nhưng đã hiển thị bác sĩ phụ trách cố định là `BS Phạm D` (`Đã được phân bổ`).
- Khi `BS Phạm D` khám xong trước `BS Trần B`, phiếu `07` (đứng sau) có nguy cơ được gọi trước phiếu `05` (đứng trước) chỉ vì đã bị gán cứng bác sĩ từ lúc check-in.

## 2. Truy vết nguyên nhân gốc rễ (4 Agent Audit)

### 2.1. Check-in & Tạo Ticket (`ReceptionService`)
1. **Gán bác sĩ quá sớm khi check-in vãng lai & check-in lịch hẹn:**
   - Trong `ReceptionService.checkInWalkIn(...)`, hệ thống gọi `autoAssignDoctorOnCheckIn(...)` để gán sẵn `visit.setPrimaryDoctor(...)` ngay khi tạo lượt khám vãng lai, trước khi bệnh nhân vào hàng đợi.
   - Trong `ReceptionService.checkInAppointment(...)` và `confirmAndCheckIn(...)`, hệ thống copy `appointment.getDoctor()` vào `visit.setPrimaryDoctor(...)` bất kể bệnh nhân có chủ động chọn bác sĩ hay không, và còn fallback gọi `autoAssignDoctorOnCheckIn(...)` nếu lịch hẹn chưa có bác sĩ.
2. **Rào chắn `visit.getPrimaryDoctor() != null`:**
   - Trước đây `ReceptionService` yêu cầu `visit.getPrimaryDoctor() != null` mới gọi `queueCoordinator.issueTicketForVisitIfEligible(...)`.

### 2.2. Điều phối hàng đợi (`ClinicQueueCoordinatorService`)
1. **Điều phối theo từng bác sĩ riêng lẻ thay vì Waterfall toàn chi nhánh:**
   - `autoCallNextForDoctorIfIdleLocked(...)` chỉ lọc các ticket đã được gán sẵn `primaryDoctor` trùng với `doctorId` đang rảnh (hoặc logic cũ chưa xét toàn bộ hàng đợi `WAITING` chung theo `waitingQueueComparator()`).
   - Hệ quả: một bác sĩ B rảnh trước sẽ kéo bệnh nhân phía sau (đã bị gán sẵn cho B), bỏ qua bệnh nhân đứng đầu hàng đợi đang bị gán nhầm cho bác sĩ A.

### 2.3. Phân biệt bệnh nhân đặt riêng bác sĩ (`Appointment`)
1. **Thiếu cờ phân biệt nguồn gốc `Appointment.doctor`:**
   - Khi bệnh nhân đặt lịch thông thường (không chọn bác sĩ), các luồng `PortalAppointmentService`, `ReceptionAppointmentService`, `PublicBookingRestController`, và `ResourceBookingTransactionService` vẫn có thể tự động gán `Appointment.doctor` (qua `autoAssignDoctorForSlot(...)` hoặc chọn mặc định theo slot).
   - Vì bảng `appointments` chưa có cờ đánh dấu bệnh nhân có chủ động yêu cầu bác sĩ hay không, tầng check-in và tầng TV không phân biệt được đâu là `Booked/Requested Doctor` thực sự và đâu là bác sĩ được hệ thống tự động điền vào slot.

### 2.4. Hiển thị TV Display (`QueueWorkflowService` & `queue-tv.html`)
1. **Hiển thị `Đã được phân bổ` cho ticket `WAITING`:**
   - `QueueWorkflowService.buildPublicQueueList(...)` và template `src/main/resources/templates/reception/queue-tv.html` lấy trực tiếp `primaryDoctor` gắn trên `Visit` để hiển thị cho các phiếu `WAITING` như thể đã phân bổ chính thức, thay vì hiển thị `Chưa phân công` cho bệnh nhân hàng đợi chung chưa được điều phối.
