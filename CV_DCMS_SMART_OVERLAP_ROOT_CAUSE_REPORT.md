# CV-DCMS Smart Appointment Overlap — Root Cause Report

## 1. Các file đã kiểm tra
- `src/main/java/com/cvdcms/service/PortalAppointmentService.java`
- `src/main/java/com/cvdcms/service/AppointmentSlotService.java`
- `src/main/java/com/cvdcms/service/AppointmentPolicyService.java` & `service/impl/AppointmentPolicyServiceImpl.java`
- `src/main/java/com/cvdcms/controller/PortalAppointmentRestController.java`
- `src/main/java/com/cvdcms/controller/PublicBookingRestController.java`
- `src/main/resources/templates/portal/appointments/form.html`
- `src/main/resources/static/js/portal-appointments.js`
- `src/main/resources/templates/portal/home.html`

## 2. Nguyên nhân & Logic cũ
1. **Overlap bị hard-block (HTTP 409) khi đặt lịch:**
   - Trong `PortalAppointmentService.book` / `reschedule` và `PublicBookingRestController.submitBooking`, hệ thống gọi `AppointmentPolicyServiceImpl.ensureBookable(...)` và `AppointmentSlotService.resolveFlexibly(...)` -> `lockAndValidateDoctorSlot(...)` và `validateBranchResourceCapacity(...)`.
   - Mọi trường hợp trùng giờ bác sĩ (`maxConcurrentPerDoctorSlot = 1`), đầy công suất buổi, hoặc đông ghế nha đều ném `SlotAlreadyTakenException` / `ResponseStatusException(409 CONFLICT)`, từ chối đặt lịch thay vì cảnh báo mềm.
2. **Không tách biệt Clinic Overlap và Doctor Overlap:**
   - Endpoint `/slots` trước đây ẩn hoàn toàn các mốc giờ có lịch trùng hoặc đầy chỗ, khiến bệnh nhân không chọn được giờ mong muốn để nhận cảnh báo mềm và không phân biệt được đông toàn chi nhánh (Clinic) với trùng lịch của đúng bác sĩ đã chọn (Doctor).
3. **Thiếu bộ gợi ý khung giờ thay thế có phân loại rõ ràng:**
   - Không có endpoint `/overlap-check` trả về trạng thái riêng cho Clinic (`NO_OVERLAP` / `WITHIN_CAPACITY` / `OVERLAP_WARNING`) và Doctor (`NO_OVERLAP` / `HAS_OVERLAP` / `NOT_APPLICABLE`), cũng như không trả về danh sách gợi ý theo từng nhóm kèm thời lượng đầy đủ của dịch vụ.
4. **Ngưỡng đề xuất cho Clinic Overlap (dựa trên dữ liệu thực tế):**
   - `branchCapacity = operationalChairs > 0 ? min(workingDoctorCount, operationalChairs) : workingDoctorCount`.
   - `clinicWarningThreshold = branchCapacity <= 1 ? 1 : ceil(branchCapacity * 0.75)`.
   - Phân loại rõ 3 mức: `0` lịch giao nhau (`NO_OVERLAP`), `0 < count < threshold` (`WITHIN_CAPACITY` — `"Có thể đông"`), và `count >= threshold` (`OVERLAP_WARNING`).
