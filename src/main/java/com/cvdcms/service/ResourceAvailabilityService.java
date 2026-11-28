package com.cvdcms.service;

import com.cvdcms.DTO.availability.AvailabilityCheckRequest;
import com.cvdcms.DTO.availability.AvailableResourceOption;
import com.cvdcms.DTO.availability.ResourceAvailabilityResult;
import com.cvdcms.DTO.availability.ServiceTimeWindow;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Resource Availability Engine (Milestone 3 - READ-ONLY).
 * Chỉ đọc dữ liệu và kiểm tra xung đột đa nguồn lực (Bác sĩ, Phòng, Ghế, Thiết bị)
 * theo khoảng nửa mở [start, end) + travel buffer. Không ghi reservation hay thay đổi dữ liệu.
 */
@Service
public class ResourceAvailabilityService {

    private static final List<AppointmentStatus> ACTIVE_APPOINTMENT_STATUSES = List.of(
            AppointmentStatus.PENDING,
            AppointmentStatus.CONFIRMED,
            AppointmentStatus.CHECKED_IN
    );

    private final BranchRepository branchRepository;
    private final ServiceRepository serviceRepository;
    private final DoctorShiftAssignmentRepository shiftAssignmentRepository;
    private final DoctorRoomAssignmentRepository doctorRoomAssignmentRepository;
    private final DoctorServicePermissionRepository doctorServicePermissionRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final ServiceResourceRequirementRepository serviceRequirementRepository;
    private final DentalChairRepository dentalChairRepository;
    private final ClinicEquipmentRepository clinicEquipmentRepository;
    private final AppointmentResourceReservationRepository reservationRepository;
    private final AppointmentRepository appointmentRepository;
    private final BranchTransitionRuleService transitionRuleService;

    public ResourceAvailabilityService(
            BranchRepository branchRepository,
            ServiceRepository serviceRepository,
            DoctorShiftAssignmentRepository shiftAssignmentRepository,
            DoctorRoomAssignmentRepository doctorRoomAssignmentRepository,
            DoctorServicePermissionRepository doctorServicePermissionRepository,
            LeaveRequestRepository leaveRequestRepository,
            ServiceResourceRequirementRepository serviceRequirementRepository,
            DentalChairRepository dentalChairRepository,
            ClinicEquipmentRepository clinicEquipmentRepository,
            AppointmentResourceReservationRepository reservationRepository,
            AppointmentRepository appointmentRepository,
            BranchTransitionRuleService transitionRuleService) {
        this.branchRepository = branchRepository;
        this.serviceRepository = serviceRepository;
        this.shiftAssignmentRepository = shiftAssignmentRepository;
        this.doctorRoomAssignmentRepository = doctorRoomAssignmentRepository;
        this.doctorServicePermissionRepository = doctorServicePermissionRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.serviceRequirementRepository = serviceRequirementRepository;
        this.dentalChairRepository = dentalChairRepository;
        this.clinicEquipmentRepository = clinicEquipmentRepository;
        this.reservationRepository = reservationRepository;
        this.appointmentRepository = appointmentRepository;
        this.transitionRuleService = transitionRuleService;
    }

    @Transactional(readOnly = true)
    public ResourceAvailabilityResult checkAvailability(AvailabilityCheckRequest request) {
        if (request == null || request.branchId() == null || request.serviceId() == null
                || request.appointmentDate() == null || request.startTime() == null) {
            return ResourceAvailabilityResult.unavailable(
                    "INVALID_TIME_REQUEST",
                    "Thiếu thông tin chi nhánh, dịch vụ, ngày hoặc giờ khám.",
                    null,
                    List.of());
        }

        Optional<Branch> branchOpt = branchRepository.findById(request.branchId());
        if (branchOpt.isEmpty() || !Boolean.TRUE.equals(branchOpt.get().getIsActive())) {
            return ResourceAvailabilityResult.unavailable(
                    "BRANCH_NOT_FOUND",
                    "Chi nhánh không tồn tại hoặc đang ngưng hoạt động.",
                    null,
                    List.of());
        }

        Optional<com.cvdcms.entity.Service> serviceOpt = serviceRepository.findById(request.serviceId());
        if (serviceOpt.isEmpty() || serviceOpt.get().getStatus() != ServiceStatus.ACTIVE) {
            return ResourceAvailabilityResult.unavailable(
                    "SERVICE_UNAVAILABLE",
                    "Dịch vụ không tồn tại hoặc đang ngưng cung cấp.",
                    null,
                    List.of());
        }

        com.cvdcms.entity.Service service = serviceOpt.get();
        LocalDate date = request.appointmentDate();
        ServiceTimeWindow window = ResourceTimeIntervalHelper.buildWindow(service, date, request.startTime());

        List<DoctorShiftAssignment> allShiftAssignments = shiftAssignmentRepository
                .findActiveByBranchAndDateWithDetails(request.branchId(), date);
        List<DoctorShiftAssignment> candidateShifts = filterByShiftWindowAndPreferredDoctor(
                allShiftAssignments, window, request.preferredDoctorId());

        if (candidateShifts.isEmpty()) {
            return ResourceAvailabilityResult.unavailable(
                    "NO_SHIFT_FOR_TIME",
                    "Không có bác sĩ nào được phân công phòng trực trong khung giờ này.",
                    window,
                    List.of("Không tìm thấy DoctorShiftAssignment ACTIVE bao phủ ["
                            + window.doctorStart().toLocalTime() + ", " + window.doctorEnd().toLocalTime() + ")."));
        }

        List<ServiceResourceRequirement> serviceReqs = serviceRequirementRepository
                .findByServiceServiceIdOrderByRequirementIdAsc(service.getServiceId());
        List<DoctorServicePermission> activeSvcPerms = doctorServicePermissionRepository
                .findByServiceServiceIdAndStatusOrderByPermissionIdAsc(
                        service.getServiceId(), DoctorServicePermissionStatus.ACTIVE);

        int maxBranchBuffer = transitionRuleService.getActiveRules(request.branchId()).stream()
                .mapToInt(r -> r.getTravelBufferMinutes() != null ? r.getTravelBufferMinutes() : 0)
                .max()
                .orElse(0);

        Set<Long> candidateDoctorIds = new LinkedHashSet<>();
        for (DoctorShiftAssignment dsa : candidateShifts) {
            candidateDoctorIds.add(dsa.getStaff().getStaffId());
        }

        LocalDateTime docQueryStart = window.doctorStart().minusMinutes(maxBranchBuffer);
        LocalDateTime docQueryEnd = window.doctorEnd().plusMinutes(maxBranchBuffer);
        List<AppointmentResourceReservation> doctorReservations = reservationRepository
                .findActiveDoctorReservationsInRange(candidateDoctorIds, docQueryStart, docQueryEnd);
        List<Appointment> doctorAppointments = appointmentRepository
                .findActiveByDoctorIdsAndDateWithDetails(candidateDoctorIds, date, ACTIVE_APPOINTMENT_STATUSES);

        List<AppointmentResourceReservation> branchOverlapReservations = reservationRepository
                .findActiveBranchReservationsByTypesInRange(
                        request.branchId(),
                        List.of(ReservationResourceType.ROOM, ReservationResourceType.CHAIR, ReservationResourceType.EQUIPMENT),
                        window.t0(),
                        window.t3());

        List<ClinicEquipment> activeBranchEquipments = hasEquipmentRequirement(serviceReqs)
                ? clinicEquipmentRepository.findByBranchBranchIdAndStatusOrderByEquipmentCodeAsc(
                        request.branchId(), EquipmentStatus.ACTIVE)
                : List.of();

        List<AvailableResourceOption> options = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        String primaryFailureCode = null;

        for (DoctorShiftAssignment dsa : candidateShifts) {
            StaffProfile doctor = dsa.getStaff();
            Room room = dsa.getRoom();
            WorkShift shift = dsa.getShift();

            if (doctor.getStatus() != StaffStatus.ACTIVE
                    || dsa.getStaffSchedule() == null
                    || dsa.getStaffSchedule().getStatus() != ScheduleStatus.SCHEDULED) {
                primaryFailureCode = firstNonNull(primaryFailureCode, "NO_SHIFT_FOR_TIME");
                diagnostics.add(doctor.getStaffCode() + ": StaffProfile hoặc StaffSchedule không ở trạng thái hoạt động.");
                continue;
            }

            if (isDoctorOnApprovedLeave(doctor.getStaffId(), request.branchId(), date, shift.getShiftId())) {
                primaryFailureCode = firstNonNull(primaryFailureCode, "DOCTOR_ON_LEAVE");
                diagnostics.add(doctor.getStaffCode() + ": Bác sĩ có đơn nghỉ phép APPROVED.");
                continue;
            }

            if (!hasActiveServicePermission(activeSvcPerms, doctor.getStaffId(), request.branchId(), date)) {
                primaryFailureCode = firstNonNull(primaryFailureCode, "DOCTOR_INELIGIBLE_SERVICE");
                diagnostics.add(doctor.getStaffCode() + ": Không có quyền thực hiện dịch vụ " + service.getServiceCode() + ".");
                continue;
            }

            if (room.getStatus() != RoomStatus.ACTIVE || !Objects.equals(room.getBranch().getBranchId(), request.branchId())) {
                primaryFailureCode = firstNonNull(primaryFailureCode, "NO_ACTIVE_ROOM");
                diagnostics.add(room.getRoomCode() + ": Phòng không ở trạng thái ACTIVE.");
                continue;
            }

            if (!roomMatchesRequirements(room, serviceReqs)) {
                primaryFailureCode = firstNonNull(primaryFailureCode, "NO_ACTIVE_ROOM");
                diagnostics.add(room.getRoomCode() + ": Loại phòng (" + room.getRoomType() + ") không đáp ứng yêu cầu dịch vụ.");
                continue;
            }

            if (!Boolean.TRUE.equals(dsa.getRoomPermissionOverridden())
                    && !hasActiveRoomPermission(request.branchId(), doctor.getStaffId(), room.getRoomId(), date)) {
                primaryFailureCode = firstNonNull(primaryFailureCode, "DOCTOR_INELIGIBLE_ROOM");
                diagnostics.add(doctor.getStaffCode() + " @ " + room.getRoomCode() + ": Chưa có DoctorRoomAssignment ACTIVE.");
                continue;
            }

            String docConflictCode = checkDoctorTimeAndBufferConflict(
                    doctor.getStaffId(),
                    request.branchId(),
                    room,
                    window,
                    request.excludeAppointmentId(),
                    doctorReservations,
                    doctorAppointments,
                    diagnostics,
                    doctor.getStaffCode());
            if (docConflictCode != null) {
                primaryFailureCode = firstNonNull(primaryFailureCode, docConflictCode);
                continue;
            }

            Long preferredChairId = dsa.getChair() != null ? dsa.getChair().getChairId() : null;
            ChairSelectionResult chairSelection = selectAvailableChair(
                    room,
                    preferredChairId,
                    window,
                    request.excludeAppointmentId(),
                    isImmediateStart(request, window),
                    branchOverlapReservations);
            if (!chairSelection.eligible()) {
                primaryFailureCode = firstNonNull(primaryFailureCode, chairSelection.failureCode());
                diagnostics.add(room.getRoomCode() + ": " + chairSelection.reason());
                continue;
            }

            EquipmentSelectionResult equipmentSelection = selectAvailableEquipments(
                    request.branchId(),
                    room,
                    window,
                    serviceReqs,
                    activeBranchEquipments,
                    branchOverlapReservations,
                    request.excludeAppointmentId());
            if (!equipmentSelection.eligible()) {
                primaryFailureCode = firstNonNull(primaryFailureCode, "MISSING_REQUIRED_EQUIPMENT");
                diagnostics.add(room.getRoomCode() + ": " + equipmentSelection.reason());
                continue;
            }

            options.add(new AvailableResourceOption(
                    doctor.getStaffId(),
                    doctor.getStaffCode(),
                    doctor.getFullName(),
                    doctor.getSpecialty(),
                    shift.getShiftId(),
                    shift.getShiftName(),
                    room.getRoomId(),
                    room.getRoomCode(),
                    room.getRoomName(),
                    room.getFloorLabel(),
                    chairSelection.chair() != null ? chairSelection.chair().getChairId() : null,
                    chairSelection.chair() != null ? chairSelection.chair().getChairCode() : null,
                    chairSelection.chair() != null ? chairSelection.chair().getChairName() : null,
                    equipmentSelection.equipmentIds(),
                    equipmentSelection.equipmentCodes(),
                    window));
        }

        if (options.isEmpty()) {
            String finalCode = primaryFailureCode != null ? primaryFailureCode : "NO_SHIFT_FOR_TIME";
            String msg = diagnostics.isEmpty()
                    ? "Không tìm thấy tổ hợp tài nguyên khả dụng cho khung giờ đã chọn."
                    : diagnostics.get(0);
            return ResourceAvailabilityResult.unavailable(finalCode, msg, window, diagnostics);
        }

        options.sort(Comparator
                .comparing(AvailableResourceOption::roomCode)
                .thenComparing(o -> o.chairId() != null ? o.chairId() : 0L)
                .thenComparing(AvailableResourceOption::doctorId));
        return ResourceAvailabilityResult.available(window, options, diagnostics);
    }

    private List<DoctorShiftAssignment> filterByShiftWindowAndPreferredDoctor(
            List<DoctorShiftAssignment> allShiftAssignments,
            ServiceTimeWindow window,
            Long preferredDoctorId) {
        LocalTime docStart = window.doctorStart().toLocalTime();
        LocalTime docEnd = window.doctorEnd().toLocalTime();
        boolean crossesMidnight = !window.doctorEnd().toLocalDate().equals(window.doctorStart().toLocalDate());
        if (crossesMidnight) {
            return List.of();
        }
        List<DoctorShiftAssignment> matched = new ArrayList<>();
        for (DoctorShiftAssignment dsa : allShiftAssignments) {
            if (preferredDoctorId != null && !Objects.equals(preferredDoctorId, dsa.getStaff().getStaffId())) {
                continue;
            }
            WorkShift shift = dsa.getShift();
            if (shift == null || !Boolean.TRUE.equals(shift.getIsActive())
                    || shift.getStartTime() == null || shift.getEndTime() == null) {
                continue;
            }
            if (!docStart.isBefore(shift.getStartTime()) && !docEnd.isAfter(shift.getEndTime())) {
                matched.add(dsa);
            }
        }
        return matched;
    }

    private boolean isDoctorOnApprovedLeave(Long staffId, Long branchId, LocalDate date, Long shiftId) {
        List<LeaveRequest> leaves = leaveRequestRepository.findApprovedForStaffOnDate(staffId, date, LeaveStatus.APPROVED);
        for (LeaveRequest lr : leaves) {
            boolean branchOk = lr.getBranch() == null || Objects.equals(branchId, lr.getBranch().getBranchId());
            boolean shiftOk = lr.getShift() == null || Objects.equals(shiftId, lr.getShift().getShiftId());
            if (branchOk && shiftOk) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveServicePermission(
            List<DoctorServicePermission> perms,
            Long staffId,
            Long branchId,
            LocalDate date) {
        for (DoctorServicePermission p : perms) {
            if (!Objects.equals(staffId, p.getStaff().getStaffId())) {
                continue;
            }
            if (p.getBranch() != null && !Objects.equals(branchId, p.getBranch().getBranchId())) {
                continue;
            }
            if (p.getEffectiveFrom() != null && p.getEffectiveFrom().isAfter(date)) {
                continue;
            }
            if (p.getEffectiveTo() != null && p.getEffectiveTo().isBefore(date)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean hasActiveRoomPermission(Long branchId, Long staffId, Long roomId, LocalDate date) {
        List<DoctorRoomAssignment> roomAssignments = doctorRoomAssignmentRepository
                .findByBranchBranchIdAndStaffStaffIdAndStatusOrderByIsPrimaryDescAssignmentIdAsc(
                        branchId, staffId, DoctorAssignmentStatus.ACTIVE);
        for (DoctorRoomAssignment dra : roomAssignments) {
            if (!Objects.equals(roomId, dra.getRoom().getRoomId())) {
                continue;
            }
            if (dra.getEffectiveFrom() != null && dra.getEffectiveFrom().isAfter(date)) {
                continue;
            }
            if (dra.getEffectiveTo() != null && dra.getEffectiveTo().isBefore(date)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean roomMatchesRequirements(Room room, List<ServiceResourceRequirement> reqs) {
        List<ServiceResourceRequirement> roomTypeReqs = reqs.stream()
                .filter(r -> r.getResourceKind() == ResourceRequirementKind.ROOM_TYPE && Boolean.TRUE.equals(r.getIsRequired()))
                .toList();
        if (!roomTypeReqs.isEmpty()) {
            boolean anyMatch = roomTypeReqs.stream().anyMatch(r -> r.getRoomType() == room.getRoomType());
            if (!anyMatch) {
                return false;
            }
        }
        List<ServiceResourceRequirement> specificRoomReqs = reqs.stream()
                .filter(r -> r.getResourceKind() == ResourceRequirementKind.SPECIFIC_ROOM && Boolean.TRUE.equals(r.getIsRequired()))
                .toList();
        if (!specificRoomReqs.isEmpty()) {
            return specificRoomReqs.stream()
                    .anyMatch(r -> r.getRoom() != null && Objects.equals(room.getRoomId(), r.getRoom().getRoomId()));
        }
        return true;
    }

    private String checkDoctorTimeAndBufferConflict(
            Long doctorId,
            Long branchId,
            Room candidateRoom,
            ServiceTimeWindow window,
            Long excludeAppointmentId,
            List<AppointmentResourceReservation> doctorReservations,
            List<Appointment> doctorAppointments,
            List<String> diagnostics,
            String doctorCode) {
        Set<Long> reservationCoveredApptIds = new HashSet<>();

        for (AppointmentResourceReservation res : doctorReservations) {
            if (!Objects.equals(doctorId, res.getResourceId())) {
                continue;
            }
            Appointment appt = res.getAppointment();
            if (excludeAppointmentId != null && Objects.equals(excludeAppointmentId, appt.getAppointmentId())) {
                continue;
            }
            reservationCoveredApptIds.add(appt.getAppointmentId());

            Room existingRoom = appt.getAssignedRoom();
            boolean sameBranch = res.getBranch() != null && Objects.equals(branchId, res.getBranch().getBranchId());
            int bufAfterExisting = (sameBranch && existingRoom != null)
                    ? transitionRuleService.resolveBufferMinutes(branchId, existingRoom, candidateRoom) : 0;
            int bufAfterCand = (sameBranch && existingRoom != null)
                    ? transitionRuleService.resolveBufferMinutes(branchId, candidateRoom, existingRoom) : 0;

            if (ResourceTimeIntervalHelper.conflictsWithBuffer(
                    window.doctorStart(), window.doctorEnd(),
                    res.getStartAt(), res.getEndAt(),
                    bufAfterExisting, bufAfterCand)) {
                boolean directOverlap = ResourceTimeIntervalHelper.overlapsHalfOpen(
                        window.doctorStart(), window.doctorEnd(), res.getStartAt(), res.getEndAt());
                String code = directOverlap ? "DOCTOR_TIME_CONFLICT" : "DOCTOR_TRAVEL_BUFFER_CONFLICT";
                diagnostics.add(doctorCode + ": Trùng/vi phạm buffer với reservation lịch #" + appt.getAppointmentCode()
                        + " [" + res.getStartAt().toLocalTime() + ", " + res.getEndAt().toLocalTime() + ").");
                return code;
            }
        }

        // Fallback an toàn cho các lịch hẹn cũ chưa có DOCTOR reservation
        for (Appointment appt : doctorAppointments) {
            if (appt.getDoctor() == null || !Objects.equals(doctorId, appt.getDoctor().getStaffId())) {
                continue;
            }
            if (excludeAppointmentId != null && Objects.equals(excludeAppointmentId, appt.getAppointmentId())) {
                continue;
            }
            if (reservationCoveredApptIds.contains(appt.getAppointmentId())) {
                continue;
            }
            ServiceTimeWindow exWin = ResourceTimeIntervalHelper.buildWindowFromAppointment(appt);
            if (exWin == null) {
                continue;
            }
            Room existingRoom = appt.getAssignedRoom();
            boolean sameBranch = appt.getBranch() != null && Objects.equals(branchId, appt.getBranch().getBranchId());
            int bufAfterExisting = (sameBranch && existingRoom != null)
                    ? transitionRuleService.resolveBufferMinutes(branchId, existingRoom, candidateRoom) : 0;
            int bufAfterCand = (sameBranch && existingRoom != null)
                    ? transitionRuleService.resolveBufferMinutes(branchId, candidateRoom, existingRoom) : 0;

            if (ResourceTimeIntervalHelper.conflictsWithBuffer(
                    window.doctorStart(), window.doctorEnd(),
                    exWin.doctorStart(), exWin.doctorEnd(),
                    bufAfterExisting, bufAfterCand)) {
                boolean directOverlap = ResourceTimeIntervalHelper.overlapsHalfOpen(
                        window.doctorStart(), window.doctorEnd(), exWin.doctorStart(), exWin.doctorEnd());
                String code = directOverlap ? "DOCTOR_TIME_CONFLICT" : "DOCTOR_TRAVEL_BUFFER_CONFLICT";
                diagnostics.add(doctorCode + ": Trùng/vi phạm buffer với lịch hẹn #" + appt.getAppointmentCode()
                        + " [" + exWin.doctorStart().toLocalTime() + ", " + exWin.doctorEnd().toLocalTime() + ").");
                return code;
            }
        }
        return null;
    }

    private boolean isImmediateStart(AvailabilityCheckRequest request, ServiceTimeWindow window) {
        if (request.immediateDispatch()) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        return request.appointmentDate().equals(LocalDate.now())
                && !window.t0().isAfter(now.plusMinutes(5));
    }

    private record ChairSelectionResult(boolean eligible, DentalChair chair, String failureCode, String reason) {}

    private ChairSelectionResult selectAvailableChair(
            Room room,
            Long preferredChairId,
            ServiceTimeWindow window,
            Long excludeAppointmentId,
            boolean immediateStart,
            List<AppointmentResourceReservation> branchOverlapReservations) {
        List<AppointmentResourceReservation> roomReservations = branchOverlapReservations.stream()
                .filter(r -> r.getResourceType() == ReservationResourceType.ROOM
                        && Objects.equals(room.getRoomId(), r.getResourceId())
                        && (excludeAppointmentId == null || !Objects.equals(excludeAppointmentId, r.getAppointment().getAppointmentId())))
                .toList();

        List<DentalChair> allRoomChairs = dentalChairRepository.findByRoomRoomIdOrderByChairCodeAsc(room.getRoomId());
        List<DentalChair> operableChairs = allRoomChairs.stream()
                .filter(c -> c.getStatus() != ChairStatus.MAINTENANCE && c.getStatus() != ChairStatus.INACTIVE)
                .sorted(Comparator.comparing(DentalChair::getChairId))
                .toList();
        Set<Long> roomChairIds = new HashSet<>();
        for (DentalChair c : allRoomChairs) {
            roomChairIds.add(c.getChairId());
        }

        List<AppointmentResourceReservation> chairReservationsInRoom = branchOverlapReservations.stream()
                .filter(r -> r.getResourceType() == ReservationResourceType.CHAIR
                        && roomChairIds.contains(r.getResourceId())
                        && (excludeAppointmentId == null || !Objects.equals(excludeAppointmentId, r.getAppointment().getAppointmentId())))
                .toList();

        if (window.roomExclusive()) {
            if (!roomReservations.isEmpty() || !chairReservationsInRoom.isEmpty()) {
                return new ChairSelectionResult(false, null, "ROOM_EXCLUSIVE_CONFLICT",
                        "Phòng yêu cầu độc quyền nhưng đang có lịch giao cắt trong khoảng ["
                                + window.t0().toLocalTime() + ", " + window.t3().toLocalTime() + ").");
            }
        } else {
            boolean hasExclusiveBooking = roomReservations.stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.getIsRoomExclusive()));
            if (hasExclusiveBooking) {
                return new ChairSelectionResult(false, null, "ROOM_EXCLUSIVE_CONFLICT",
                        "Phòng đang bị giữ độc quyền bởi một thủ thuật khác.");
            }
        }

        if (!window.requiresChair()) {
            if (!roomReservations.isEmpty()) {
                return new ChairSelectionResult(false, null, "ROOM_EXCLUSIVE_CONFLICT",
                        "Phòng không dùng ghế đã có lịch giữ chỗ giao cắt.");
            }
            return new ChairSelectionResult(true, null, null, null);
        }

        if (operableChairs.isEmpty()) {
            return new ChairSelectionResult(false, null, "NO_AVAILABLE_CHAIR",
                    "Phòng không có ghế nha khoa khả dụng (đều MAINTENANCE/INACTIVE hoặc chưa có ghế).");
        }

        List<DentalChair> candidateChairs = preferredChairId != null
                ? operableChairs.stream().filter(c -> Objects.equals(preferredChairId, c.getChairId())).toList()
                : operableChairs;
        if (candidateChairs.isEmpty()) {
            return new ChairSelectionResult(false, null, "NO_AVAILABLE_CHAIR",
                    "Ghế cố định được phân công cho bác sĩ trong ca này hiện không khả dụng (bảo trì hoặc ngưng hoạt động).");
        }

        Set<Long> busyChairIds = new HashSet<>();
        for (AppointmentResourceReservation cr : chairReservationsInRoom) {
            busyChairIds.add(cr.getResourceId());
        }

        boolean sawOperationalBusyChair = false;
        for (DentalChair chair : candidateChairs) {
            if (busyChairIds.contains(chair.getChairId())) {
                continue;
            }
            if (immediateStart && (chair.getStatus() == ChairStatus.IN_USE || chair.getStatus() == ChairStatus.TURNOVER)) {
                sawOperationalBusyChair = true;
                continue;
            }
            return new ChairSelectionResult(true, chair, null, null);
        }

        if (sawOperationalBusyChair) {
            return new ChairSelectionResult(false, null, "CHAIR_BUSY_IN_USE_OR_TURNOVER",
                    "Ghế trong phòng đang ở trạng thái IN_USE hoặc TURNOVER, chưa sẵn sàng đón ca tức thời.");
        }
        return new ChairSelectionResult(false, null, "NO_AVAILABLE_CHAIR",
                "Tất cả ghế khả dụng trong phòng đã có lịch giữ chỗ trong khoảng ["
                        + window.t0().toLocalTime() + ", " + window.t3().toLocalTime() + ").");
    }

    private boolean hasEquipmentRequirement(List<ServiceResourceRequirement> reqs) {
        return reqs.stream().anyMatch(r -> r.getResourceKind() == ResourceRequirementKind.EQUIPMENT_TYPE
                || r.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT);
    }

    private record EquipmentSelectionResult(
            boolean eligible,
            List<Long> equipmentIds,
            List<String> equipmentCodes,
            String reason) {}

    private EquipmentSelectionResult selectAvailableEquipments(
            Long branchId,
            Room room,
            ServiceTimeWindow window,
            List<ServiceResourceRequirement> serviceReqs,
            List<ClinicEquipment> activeBranchEquipments,
            List<AppointmentResourceReservation> branchOverlapReservations,
            Long excludeAppointmentId) {
        List<ServiceResourceRequirement> eqReqs = serviceReqs.stream()
                .filter(r -> r.getResourceKind() == ResourceRequirementKind.EQUIPMENT_TYPE
                        || r.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT)
                .toList();
        if (eqReqs.isEmpty()) {
            return new EquipmentSelectionResult(true, List.of(), List.of(), null);
        }

        List<ClinicEquipment> sortedActiveEquipments = activeBranchEquipments.stream()
                .sorted(Comparator.comparing(ClinicEquipment::getEquipmentId))
                .toList();

        List<Long> pickedIds = new ArrayList<>();
        List<String> pickedCodes = new ArrayList<>();

        for (ServiceResourceRequirement req : eqReqs) {
            LocalDateTime[] phaseRange = ResourceTimeIntervalHelper.phaseInterval(window, req.getUsagePhase());
            LocalDateTime eqStart = phaseRange[0];
            LocalDateTime eqEnd = phaseRange[1];

            ClinicEquipment matched = null;
            for (ClinicEquipment eq : sortedActiveEquipments) {
                if (pickedIds.contains(eq.getEquipmentId())) {
                    continue;
                }
                if (eq.getStatus() != EquipmentStatus.ACTIVE) {
                    continue;
                }
                if (req.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT) {
                    if (req.getEquipment() == null || !Objects.equals(eq.getEquipmentId(), req.getEquipment().getEquipmentId())) {
                        continue;
                    }
                } else if (req.getEquipmentType() != null && eq.getEquipmentType() != req.getEquipmentType()) {
                    continue;
                }

                if (eq.getAllocationMode() == EquipmentAllocationMode.FIXED_ROOM) {
                    if (eq.getFixedRoom() == null || !Objects.equals(room.getRoomId(), eq.getFixedRoom().getRoomId())) {
                        continue;
                    }
                } else if (eq.getBranch() == null || !Objects.equals(branchId, eq.getBranch().getBranchId())) {
                    continue;
                }

                boolean overlaps = branchOverlapReservations.stream()
                        .filter(r -> r.getResourceType() == ReservationResourceType.EQUIPMENT
                                && Objects.equals(eq.getEquipmentId(), r.getResourceId())
                                && (excludeAppointmentId == null || !Objects.equals(excludeAppointmentId, r.getAppointment().getAppointmentId())))
                        .anyMatch(r -> ResourceTimeIntervalHelper.overlapsHalfOpen(eqStart, eqEnd, r.getStartAt(), r.getEndAt()));
                if (!overlaps) {
                    matched = eq;
                    break;
                }
            }

            if (matched != null) {
                pickedIds.add(matched.getEquipmentId());
                pickedCodes.add(matched.getEquipmentCode());
            } else if (Boolean.TRUE.equals(req.getIsRequired())) {
                String label = req.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT && req.getEquipment() != null
                        ? req.getEquipment().getEquipmentCode()
                        : String.valueOf(req.getEquipmentType());
                return new EquipmentSelectionResult(false, List.of(), List.of(),
                        "Thiếu thiết bị ACTIVE bắt buộc (" + label + ") trong pha " + req.getUsagePhase() + ".");
            }
        }

        return new EquipmentSelectionResult(true, pickedIds, pickedCodes, null);
    }

    private static String firstNonNull(String current, String candidate) {
        return current != null ? current : candidate;
    }
}
