package com.cvdcms.service;

import com.cvdcms.DTO.availability.ResourceBookingRequest;
import com.cvdcms.DTO.availability.ResourceBookingResult;
import com.cvdcms.DTO.availability.ResourceRescheduleRequest;
import com.cvdcms.DTO.availability.ServiceTimeWindow;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Resource Booking Transaction Service (Milestone 4).
 * Thực hiện đặt giữ tài nguyên đa nguồn lực an toàn trong transaction READ_COMMITTED
 * theo thứ tự khóa toàn cục 2 -> 10, tái kiểm tra dưới khóa, idempotency và retry tối đa 2 lần
 * cho PessimisticLockingFailureException ngoài ranh giới transaction đã rollback.
 */
@Service
public class ResourceBookingTransactionService {

    private static final int MAX_LOCK_RETRIES = 2;
    private static final int MAX_ATTEMPTS = 1 + MAX_LOCK_RETRIES;

    private static final List<AppointmentStatus> ACTIVE_APPOINTMENT_STATUSES = List.of(
            AppointmentStatus.PENDING,
            AppointmentStatus.CONFIRMED,
            AppointmentStatus.CHECKED_IN
    );

    private static final List<ChairStatus> EXCLUDED_CHAIR_STATUSES = List.of(
            ChairStatus.MAINTENANCE,
            ChairStatus.INACTIVE
    );

    private final TransactionTemplate txTemplate;
    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;
    private final ServiceRepository serviceRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffScheduleRepository staffScheduleRepository;
    private final DoctorShiftAssignmentRepository shiftAssignmentRepository;
    private final DoctorRoomAssignmentRepository doctorRoomAssignmentRepository;
    private final DoctorServicePermissionRepository doctorServicePermissionRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final RoomRepository roomRepository;
    private final DentalChairRepository dentalChairRepository;
    private final ClinicEquipmentRepository clinicEquipmentRepository;
    private final ServiceResourceRequirementRepository serviceRequirementRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentResourceReservationRepository reservationRepository;
    private final BranchTransitionRuleService transitionRuleService;

    @Autowired(required = false)
    private VisitRepository visitRepository;

    public ResourceBookingTransactionService(
            PlatformTransactionManager transactionManager,
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository,
            ServiceRepository serviceRepository,
            StaffProfileRepository staffProfileRepository,
            StaffScheduleRepository staffScheduleRepository,
            DoctorShiftAssignmentRepository shiftAssignmentRepository,
            DoctorRoomAssignmentRepository doctorRoomAssignmentRepository,
            DoctorServicePermissionRepository doctorServicePermissionRepository,
            LeaveRequestRepository leaveRequestRepository,
            RoomRepository roomRepository,
            DentalChairRepository dentalChairRepository,
            ClinicEquipmentRepository clinicEquipmentRepository,
            ServiceResourceRequirementRepository serviceRequirementRepository,
            AppointmentRepository appointmentRepository,
            AppointmentResourceReservationRepository reservationRepository,
            BranchTransitionRuleService transitionRuleService) {
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.patientProfileRepository = patientProfileRepository;
        this.branchRepository = branchRepository;
        this.serviceRepository = serviceRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.staffScheduleRepository = staffScheduleRepository;
        this.shiftAssignmentRepository = shiftAssignmentRepository;
        this.doctorRoomAssignmentRepository = doctorRoomAssignmentRepository;
        this.doctorServicePermissionRepository = doctorServicePermissionRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.roomRepository = roomRepository;
        this.dentalChairRepository = dentalChairRepository;
        this.clinicEquipmentRepository = clinicEquipmentRepository;
        this.serviceRequirementRepository = serviceRequirementRepository;
        this.appointmentRepository = appointmentRepository;
        this.reservationRepository = reservationRepository;
        this.transitionRuleService = transitionRuleService;
    }

    /**
     * Đặt giữ tài nguyên với tối đa 2 lần retry (tổng 3 attempts) khi gặp PessimisticLockingFailureException.
     * Bắt buộc chạy ngoài @Transactional để mỗi attempt dùng một transaction READ_COMMITTED mới hoàn toàn.
     */
    public ResourceBookingResult bookWithRetry(ResourceBookingRequest request) {
        return bookWithRetry(request, AppointmentStatus.CONFIRMED);
    }

    public ResourceBookingResult bookWithRetry(ResourceBookingRequest request, AppointmentStatus initialStatus) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "bookWithRetry phải được gọi ngoài transaction đang mở để đảm bảo retry sau rollback sạch.");
        }
        validateBasicRequest(request);

        PessimisticLockingFailureException lastLockException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return txTemplate.execute(status -> executeSingleAttempt(request, initialStatus));
            } catch (PessimisticLockingFailureException ex) {
                lastLockException = ex;
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Hệ thống đang bận tranh chấp khóa tài nguyên sau " + MAX_ATTEMPTS + " lần thử.",
                            lastLockException);
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể khóa tài nguyên đặt lịch.", lastLockException);
    }

    /**
     * Dùng cho caller đã mở transaction READ_COMMITTED (ví dụ ReceptionConsultationService.convert / tạo hồ sơ mới đồng thời).
     */
    public ResourceBookingResult bookInExistingTransaction(ResourceBookingRequest request, AppointmentStatus initialStatus) {
        validateBasicRequest(request);
        return executeSingleAttempt(request, initialStatus);
    }

    public ResourceBookingResult rescheduleWithRetry(ResourceRescheduleRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "rescheduleWithRetry phải được gọi ngoài transaction đang mở để đảm bảo retry sau rollback sạch.");
        }
        validateRescheduleRequest(request);

        PessimisticLockingFailureException lastLockException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return txTemplate.execute(status -> executeRescheduleAttempt(request));
            } catch (PessimisticLockingFailureException ex) {
                lastLockException = ex;
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Hệ thống đang bận tranh chấp khóa tài nguyên khi dời lịch sau " + MAX_ATTEMPTS + " lần thử.",
                            lastLockException);
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể khóa tài nguyên dời lịch.", lastLockException);
    }

    public ResourceBookingResult rescheduleInExistingTransaction(ResourceRescheduleRequest request) {
        validateRescheduleRequest(request);
        return executeRescheduleAttempt(request);
    }

    private ResourceBookingResult executeSingleAttempt(ResourceBookingRequest request, AppointmentStatus initialStatus) {
        LocalDate workDate = request.appointmentDate();
        LocalTime startTime = request.startTime();
        String idempotentCode = deriveIdempotentAppointmentCode(request.idempotencyKey());

        // 0. Snapshot READ không lock để khám phá tập ID ứng viên cần sắp xếp ASC trước khi lấy khóa
        AppointmentLockSnapshot existingSnap = request.existingAppointmentId() != null
                ? appointmentRepository.findLockSnapshotById(request.existingAppointmentId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."))
                : null;
        Long effectiveProfileId = request.profileId() != null
                ? request.profileId()
                : (existingSnap != null ? existingSnap.patientProfileId() : null);
        if (effectiveProfileId == null) {
            throw new IllegalArgumentException("Thiếu thông tin hồ sơ bệnh nhân (profileId).");
        }

        com.cvdcms.entity.Service preService = serviceRepository.findById(request.serviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy dịch vụ."));
        if (preService.getStatus() != ServiceStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dịch vụ không ở trạng thái ACTIVE.");
        }
        ServiceTimeWindow preWindow = ResourceTimeIntervalHelper.buildWindow(preService, workDate, startTime);

        List<DoctorShiftAssignment> preShifts = shiftAssignmentRepository
                .findActiveByBranchAndDateWithDetails(request.branchId(), workDate);
        Set<Long> candidateDoctorIds = new TreeSet<>();
        Set<Long> candidateRoomIds = new TreeSet<>();
        for (DoctorShiftAssignment dsa : preShifts) {
            if (request.preferredDoctorId() != null && !Objects.equals(request.preferredDoctorId(), dsa.getStaff().getStaffId())) {
                continue;
            }
            WorkShift sh = dsa.getShift();
            if (sh != null && Boolean.TRUE.equals(sh.getIsActive())
                    && !preWindow.doctorStart().toLocalTime().isBefore(sh.getStartTime())
                    && !preWindow.doctorEnd().toLocalTime().isAfter(sh.getEndTime())) {
                candidateDoctorIds.add(dsa.getStaff().getStaffId());
                candidateRoomIds.add(dsa.getRoom().getRoomId());
            }
        }
        if (request.preferredDoctorId() != null) {
            candidateDoctorIds.add(request.preferredDoctorId());
        }

        List<ServiceResourceRequirement> preReqs = serviceRequirementRepository
                .findByServiceServiceIdOrderByRequirementIdAsc(preService.getServiceId());
        boolean needsEquipmentLock = preReqs.stream().anyMatch(r ->
                r.getResourceKind() == ResourceRequirementKind.EQUIPMENT_TYPE
                        || r.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT);
        List<Long> candidateEquipmentIds = needsEquipmentLock
                ? clinicEquipmentRepository.findByBranchBranchIdAndStatusOrderByEquipmentCodeAsc(
                        request.branchId(), EquipmentStatus.ACTIVE)
                        .stream().map(ClinicEquipment::getEquipmentId).sorted().toList()
                : List.of();

        // ==================== GLOBAL LOCK ORDERING: Cấp 2 -> Cấp 10 ====================

        // Cấp 2: PatientProfile (profileId ASC)
        PatientProfile patientProfile = patientProfileRepository.findByIdForUpdate(effectiveProfileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));

        // Cấp 3: Branch (branchId ASC)
        Branch branch = branchRepository.findByIdForUpdate(request.branchId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chi nhánh đang ngưng hoạt động.");
        }

        // Kiểm tra Idempotency dưới khóa Cấp 2 & 3 nếu có idempotencyKey
        if (idempotentCode != null) {
            Optional<Appointment> existingByCode = appointmentRepository.findByAppointmentCode(idempotentCode);
            if (existingByCode.isPresent()) {
                Appointment existing = existingByCode.get();
                assertIdempotentPayloadMatches(existing, effectiveProfileId, request);
                return buildIdempotentReplayResult(existing, preWindow);
            }
        }

        if (candidateDoctorIds.isEmpty() || candidateRoomIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không có bác sĩ nào được phân công phòng trực khả dụng trong khung giờ này.");
        }

        // Cấp 4: StaffProfile (staffId ASC)
        Map<Long, StaffProfile> lockedDoctors = new LinkedHashMap<>();
        for (StaffProfile sp : staffProfileRepository.findByIdInForUpdateOrderByStaffIdAsc(candidateDoctorIds)) {
            lockedDoctors.put(sp.getStaffId(), sp);
        }

        // Cấp 5: StaffSchedule & DoctorShiftAssignment (id ASC)
        List<DoctorShiftAssignment> lockedShiftAssignments = new ArrayList<>();
        for (Long doctorId : candidateDoctorIds) {
            staffScheduleRepository.lockDoctorSchedulesForDate(doctorId, workDate, ScheduleStatus.SCHEDULED);
            lockedShiftAssignments.addAll(shiftAssignmentRepository.lockByStaffAndDateOrderByIdAsc(doctorId, workDate));
        }

        // Cấp 6: Room (roomId ASC)
        Map<Long, Room> lockedRooms = new LinkedHashMap<>();
        for (Room r : roomRepository.findByIdInForUpdateOrderByRoomIdAsc(candidateRoomIds)) {
            lockedRooms.put(r.getRoomId(), r);
        }

        // Cấp 7: DentalChair (chairId ASC)
        List<DentalChair> lockedChairs = dentalChairRepository.findCandidatesByRoomIdsForUpdateOrderByChairIdAsc(
                candidateRoomIds, EXCLUDED_CHAIR_STATUSES);

        // Cấp 8: ClinicEquipment (equipmentId ASC)
        List<ClinicEquipment> lockedEquipments = candidateEquipmentIds.isEmpty()
                ? List.of()
                : clinicEquipmentRepository.findByIdInForUpdateOrderByEquipmentIdAsc(candidateEquipmentIds);

        // Cấp 9: Appointment (nếu giữ chỗ cho Appointment đã tồn tại)
        Appointment targetAppointment = null;
        if (request.existingAppointmentId() != null) {
            targetAppointment = appointmentRepository.lockById(request.existingAppointmentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));
            // Cấp 10: Kiểm tra Reservation hiện có của Appointment (idempotency cho existingAppointmentId)
            List<AppointmentResourceReservation> existingRes = reservationRepository
                    .lockActiveByAppointmentIdsOrderByReservationIdAsc(
                            List.of(targetAppointment.getAppointmentId()), ReservationStatus.RESERVED);
            if (!existingRes.isEmpty()) {
                return buildIdempotentReplayResult(targetAppointment, preWindow, existingRes);
            }
        }

        // ==================== RE-VERIFY TOÀN BỘ DƯỚI KHÓA ====================
        com.cvdcms.entity.Service service = serviceRepository.findById(request.serviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy dịch vụ."));
        if (service.getStatus() != ServiceStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dịch vụ không ở trạng thái ACTIVE.");
        }
        ServiceTimeWindow window = ResourceTimeIntervalHelper.buildWindow(service, workDate, startTime);
        List<ServiceResourceRequirement> serviceReqs = serviceRequirementRepository
                .findByServiceServiceIdOrderByRequirementIdAsc(service.getServiceId());
        List<DoctorServicePermission> activeSvcPerms = doctorServicePermissionRepository
                .findByServiceServiceIdAndStatusOrderByPermissionIdAsc(
                        service.getServiceId(), DoctorServicePermissionStatus.ACTIVE);

        int maxBranchBuffer = transitionRuleService.getActiveRules(branch.getBranchId()).stream()
                .mapToInt(r -> r.getTravelBufferMinutes() != null ? r.getTravelBufferMinutes() : 0)
                .max()
                .orElse(0);

        List<AppointmentResourceReservation> doctorReservations = reservationRepository
                .findActiveDoctorReservationsInRange(
                        candidateDoctorIds,
                        window.doctorStart().minusMinutes(maxBranchBuffer),
                        window.doctorEnd().plusMinutes(maxBranchBuffer));
        List<Appointment> doctorAppointments = appointmentRepository
                .findActiveByDoctorIdsAndDateWithDetails(candidateDoctorIds, workDate, ACTIVE_APPOINTMENT_STATUSES);
        List<AppointmentResourceReservation> branchOverlapReservations = reservationRepository
                .findActiveBranchReservationsByTypesInRange(
                        branch.getBranchId(),
                        List.of(ReservationResourceType.ROOM, ReservationResourceType.CHAIR, ReservationResourceType.EQUIPMENT),
                        window.t0(),
                        window.t3());

        SelectedAllocation allocation = selectValidAllocationUnderLocks(
                request,
                branch,
                service,
                window,
                lockedShiftAssignments,
                lockedDoctors,
                lockedRooms,
                lockedChairs,
                lockedEquipments,
                serviceReqs,
                activeSvcPerms,
                doctorReservations,
                doctorAppointments,
                branchOverlapReservations);

        // ==================== PERSIST APPOINTMENT + RESERVATIONS ATOMICALLY ====================
        if (targetAppointment == null) {
            targetAppointment = new Appointment();
            targetAppointment.setAppointmentCode(idempotentCode != null ? idempotentCode : generateUniqueCode());
            targetAppointment.setPatientProfile(patientProfile);
            targetAppointment.setBranch(branch);
            targetAppointment.setPreferredSession(request.preferredSession() != null
                    ? request.preferredSession() : resolveSession(startTime));
            targetAppointment.setSource(request.source() != null ? request.source() : AppointmentSource.ONLINE);
            targetAppointment.setInitialSymptoms(request.initialSymptoms());
            targetAppointment.setStatus(initialStatus != null ? initialStatus : AppointmentStatus.CONFIRMED);
        }

        targetAppointment.setDoctor(allocation.doctor());
        targetAppointment.setIsDoctorRequested(!request.allowDoctorAutoAssign());
        targetAppointment.setService(service);
        targetAppointment.setAppointmentDate(workDate);
        targetAppointment.setExpectedTime(startTime);
        targetAppointment.setDurationMinutes(window.totalMinutes());
        targetAppointment.setExpectedEndTime(window.t3().toLocalTime());
        targetAppointment.setPreparationDurationMinutes(window.prepMinutes());
        targetAppointment.setProcedureDurationMinutes(window.procedureMinutes());
        targetAppointment.setTurnoverDurationMinutes(window.turnoverMinutes());
        targetAppointment.setIsRoomExclusiveSnapshot(window.roomExclusive());
        targetAppointment.setDoctorRequiredInPrepSnapshot(window.doctorRequiredInPrep());
        targetAppointment.setDoctorRequiredInTurnoverSnapshot(window.doctorRequiredInTurnover());
        targetAppointment.setAssignedRoom(allocation.room());
        targetAppointment.setAssignedChair(allocation.chair());
        targetAppointment.setResourceStatus(AppointmentResourceStatus.OK);
        targetAppointment.setResourceConflictNote(null);

        targetAppointment = appointmentRepository.saveAndFlush(targetAppointment);

        List<AppointmentResourceReservation> createdReservations = persistReservations(
                targetAppointment, branch, window, allocation);

        return new ResourceBookingResult(
                targetAppointment.getAppointmentId(),
                targetAppointment.getAppointmentCode(),
                branch.getBranchId(),
                allocation.doctor().getStaffId(),
                allocation.room().getRoomId(),
                allocation.chair() != null ? allocation.chair().getChairId() : null,
                allocation.equipments().stream().map(ClinicEquipment::getEquipmentId).toList(),
                createdReservations.stream().map(AppointmentResourceReservation::getReservationId).toList(),
                window,
                false);
    }

    private record SelectedAllocation(
            StaffProfile doctor,
            Room room,
            DentalChair chair,
            List<ClinicEquipment> equipments,
            List<ResourceUsagePhase> equipmentPhases) {}

    private SelectedAllocation selectValidAllocationUnderLocks(
            ResourceBookingRequest request,
            Branch branch,
            com.cvdcms.entity.Service service,
            ServiceTimeWindow window,
            List<DoctorShiftAssignment> lockedShiftAssignments,
            Map<Long, StaffProfile> lockedDoctors,
            Map<Long, Room> lockedRooms,
            List<DentalChair> lockedChairs,
            List<ClinicEquipment> lockedEquipments,
            List<ServiceResourceRequirement> serviceReqs,
            List<DoctorServicePermission> activeSvcPerms,
            List<AppointmentResourceReservation> doctorReservations,
            List<Appointment> doctorAppointments,
            List<AppointmentResourceReservation> branchOverlapReservations) {

        LocalDate workDate = request.appointmentDate();
        LocalTime docStart = window.doctorStart().toLocalTime();
        LocalTime docEnd = window.doctorEnd().toLocalTime();

        List<DoctorShiftAssignment> eligibleShiftCandidates = lockedShiftAssignments.stream()
                .filter(dsa -> dsa.getStatus() == DoctorShiftAssignmentStatus.ACTIVE)
                .filter(dsa -> dsa.getBranch() != null && Objects.equals(branch.getBranchId(), dsa.getBranch().getBranchId()))
                .filter(dsa -> Objects.equals(workDate, dsa.getWorkDate()))
                .filter(dsa -> request.preferredDoctorId() == null
                        || Objects.equals(request.preferredDoctorId(), dsa.getStaff().getStaffId()))
                .filter(dsa -> {
                    WorkShift sh = dsa.getShift();
                    return sh != null && Boolean.TRUE.equals(sh.getIsActive())
                            && !docStart.isBefore(sh.getStartTime())
                            && !docEnd.isAfter(sh.getEndTime());
                })
                .sorted(Comparator
                        .comparing((DoctorShiftAssignment d) -> d.getRoom().getRoomCode())
                        .thenComparing(d -> d.getStaff().getStaffId()))
                .toList();

        String lastRejectReason = "Không có tổ hợp bác sĩ, phòng, ghế và thiết bị khả dụng sau khi khóa.";
        boolean immediateStart = request.immediateDispatch()
                || (workDate.equals(LocalDate.now()) && !window.t0().isAfter(LocalDateTime.now().plusMinutes(5)));

        for (DoctorShiftAssignment dsa : eligibleShiftCandidates) {
            StaffProfile doctor = lockedDoctors.get(dsa.getStaff().getStaffId());
            Room room = lockedRooms.get(dsa.getRoom().getRoomId());
            if (doctor == null || doctor.getStatus() != StaffStatus.ACTIVE
                    || dsa.getStaffSchedule() == null || dsa.getStaffSchedule().getStatus() != ScheduleStatus.SCHEDULED) {
                lastRejectReason = "Bác sĩ hoặc lịch trực không còn ở trạng thái hoạt động.";
                continue;
            }
            if (isDoctorOnApprovedLeave(doctor.getStaffId(), branch.getBranchId(), workDate, dsa.getShift().getShiftId())) {
                lastRejectReason = "Bác sĩ đã có đơn nghỉ phép APPROVED.";
                continue;
            }
            if (!hasActiveServicePermission(activeSvcPerms, doctor.getStaffId(), branch.getBranchId(), workDate)) {
                lastRejectReason = "Bác sĩ không có quyền thực hiện dịch vụ " + service.getServiceCode() + ".";
                continue;
            }
            if (room == null || room.getStatus() != RoomStatus.ACTIVE
                    || !Objects.equals(branch.getBranchId(), room.getBranch().getBranchId())
                    || !roomMatchesRequirements(room, serviceReqs)) {
                lastRejectReason = "Phòng không ở trạng thái ACTIVE hoặc không đáp ứng loại phòng của dịch vụ.";
                continue;
            }
            if (!Boolean.TRUE.equals(dsa.getRoomPermissionOverridden())
                    && !hasActiveRoomPermission(branch.getBranchId(), doctor.getStaffId(), room.getRoomId(), workDate)) {
                lastRejectReason = "Bác sĩ chưa có phân công phòng ACTIVE.";
                continue;
            }

            if (hasDoctorTimeOrBufferConflict(
                    doctor.getStaffId(), branch.getBranchId(), room, window,
                    request.existingAppointmentId(), doctorReservations, doctorAppointments)) {
                lastRejectReason = "Bác sĩ bị trùng lịch hoặc vi phạm travel buffer.";
                continue;
            }

            // Kiểm tra phòng độc quyền & chọn ghế theo chairId ASC từ tập ghế đã khóa
            List<DentalChair> chairsInRoom = lockedChairs.stream()
                    .filter(c -> c.getRoom() != null && Objects.equals(room.getRoomId(), c.getRoom().getRoomId()))
                    .sorted(Comparator.comparing(DentalChair::getChairId))
                    .toList();
            Set<Long> roomChairIds = new HashSet<>();
            chairsInRoom.forEach(c -> roomChairIds.add(c.getChairId()));

            List<AppointmentResourceReservation> roomReservations = branchOverlapReservations.stream()
                    .filter(r -> r.getResourceType() == ReservationResourceType.ROOM
                            && Objects.equals(room.getRoomId(), r.getResourceId())
                            && notSameAppointment(r, request.existingAppointmentId()))
                    .toList();
            List<AppointmentResourceReservation> chairReservations = branchOverlapReservations.stream()
                    .filter(r -> r.getResourceType() == ReservationResourceType.CHAIR
                            && roomChairIds.contains(r.getResourceId())
                            && notSameAppointment(r, request.existingAppointmentId()))
                    .toList();

            if (window.roomExclusive()) {
                if (!roomReservations.isEmpty() || !chairReservations.isEmpty()) {
                    lastRejectReason = "Phòng độc quyền đã có lịch giao cắt.";
                    continue;
                }
            } else if (roomReservations.stream().anyMatch(r -> Boolean.TRUE.equals(r.getIsRoomExclusive()))) {
                lastRejectReason = "Phòng đang bị giữ độc quyền bởi thủ thuật khác.";
                continue;
            }

            DentalChair selectedChair = null;
            if (window.requiresChair()) {
                Set<Long> busyChairIds = new HashSet<>();
                chairReservations.forEach(cr -> busyChairIds.add(cr.getResourceId()));
                for (DentalChair chair : chairsInRoom) {
                    if (busyChairIds.contains(chair.getChairId())) {
                        continue;
                    }
                    if (immediateStart && (chair.getStatus() == ChairStatus.IN_USE || chair.getStatus() == ChairStatus.TURNOVER)) {
                        continue;
                    }
                    selectedChair = chair;
                    break;
                }
                if (selectedChair == null) {
                    lastRejectReason = "Không còn ghế khả dụng trong phòng " + room.getRoomCode() + ".";
                    continue;
                }
            } else if (!roomReservations.isEmpty()) {
                lastRejectReason = "Phòng không dùng ghế đã có lịch giao cắt.";
                continue;
            }

            // Kiểm tra và chọn thiết bị từ tập đã khóa theo equipmentId ASC
            List<ClinicEquipment> pickedEquipments = new ArrayList<>();
            List<ResourceUsagePhase> pickedPhases = new ArrayList<>();
            boolean eqOk = selectEquipmentsUnderLock(
                    branch.getBranchId(), room, window, serviceReqs, lockedEquipments,
                    branchOverlapReservations, request.existingAppointmentId(), pickedEquipments, pickedPhases);
            if (!eqOk) {
                lastRejectReason = "Thiếu thiết bị ACTIVE khả dụng theo yêu cầu dịch vụ.";
                continue;
            }

            return new SelectedAllocation(doctor, room, selectedChair, pickedEquipments, pickedPhases);
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT, lastRejectReason);
    }

    private boolean selectEquipmentsUnderLock(
            Long branchId,
            Room room,
            ServiceTimeWindow window,
            List<ServiceResourceRequirement> serviceReqs,
            List<ClinicEquipment> lockedEquipments,
            List<AppointmentResourceReservation> branchOverlapReservations,
            Long excludeAppointmentId,
            List<ClinicEquipment> pickedOut,
            List<ResourceUsagePhase> phasesOut) {

        List<ServiceResourceRequirement> eqReqs = serviceReqs.stream()
                .filter(r -> r.getResourceKind() == ResourceRequirementKind.EQUIPMENT_TYPE
                        || r.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT)
                .toList();
        if (eqReqs.isEmpty()) {
            return true;
        }

        Set<Long> alreadyPicked = new HashSet<>();
        for (ServiceResourceRequirement req : eqReqs) {
            LocalDateTime[] range = ResourceTimeIntervalHelper.phaseInterval(window, req.getUsagePhase());
            ClinicEquipment chosen = null;
            for (ClinicEquipment eq : lockedEquipments) {
                if (alreadyPicked.contains(eq.getEquipmentId()) || eq.getStatus() != EquipmentStatus.ACTIVE) {
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
                                && notSameAppointment(r, excludeAppointmentId))
                        .anyMatch(r -> ResourceTimeIntervalHelper.overlapsHalfOpen(range[0], range[1], r.getStartAt(), r.getEndAt()));
                if (!overlaps) {
                    chosen = eq;
                    break;
                }
            }
            if (chosen != null) {
                alreadyPicked.add(chosen.getEquipmentId());
                pickedOut.add(chosen);
                phasesOut.add(req.getUsagePhase() != null ? req.getUsagePhase() : ResourceUsagePhase.PROCEDURE);
            } else if (Boolean.TRUE.equals(req.getIsRequired())) {
                return false;
            }
        }
        return true;
    }

    private List<AppointmentResourceReservation> persistReservations(
            Appointment appointment,
            Branch branch,
            ServiceTimeWindow window,
            SelectedAllocation allocation) {
        List<AppointmentResourceReservation> toSave = new ArrayList<>();

        // 1. Đúng 1 DOCTOR reservation
        ReservationUsageScope docScope = (window.doctorRequiredInPrep() && window.doctorRequiredInTurnover())
                ? ReservationUsageScope.FULL_STAY : ReservationUsageScope.PROCEDURE;
        toSave.add(buildReservation(
                appointment, branch, ReservationResourceType.DOCTOR, allocation.doctor().getStaffId(),
                docScope, false, window.doctorStart(), window.doctorEnd()));

        // 2. ROOM reservation
        toSave.add(buildReservation(
                appointment, branch, ReservationResourceType.ROOM, allocation.room().getRoomId(),
                ReservationUsageScope.FULL_STAY, window.roomExclusive(), window.t0(), window.t3()));

        // 3. CHAIR reservation (nếu dịch vụ dùng ghế)
        if (allocation.chair() != null) {
            toSave.add(buildReservation(
                    appointment, branch, ReservationResourceType.CHAIR, allocation.chair().getChairId(),
                    ReservationUsageScope.FULL_STAY, false, window.t0(), window.t3()));
        }

        // 4. EQUIPMENT reservations theo đúng ResourceUsagePhase
        for (int i = 0; i < allocation.equipments().size(); i++) {
            ClinicEquipment eq = allocation.equipments().get(i);
            ResourceUsagePhase phase = allocation.equipmentPhases().get(i);
            LocalDateTime[] range = ResourceTimeIntervalHelper.phaseInterval(window, phase);
            toSave.add(buildReservation(
                    appointment, branch, ReservationResourceType.EQUIPMENT, eq.getEquipmentId(),
                    toUsageScope(phase), false, range[0], range[1]));
        }

        return reservationRepository.saveAllAndFlush(toSave);
    }

    private AppointmentResourceReservation buildReservation(
            Appointment appointment,
            Branch branch,
            ReservationResourceType type,
            Long resourceId,
            ReservationUsageScope scope,
            boolean roomExclusive,
            LocalDateTime startAt,
            LocalDateTime endAt) {
        AppointmentResourceReservation res = new AppointmentResourceReservation();
        res.setAppointment(appointment);
        res.setBranch(branch);
        res.setResourceType(type);
        res.setResourceId(resourceId);
        res.setUsageScope(scope);
        res.setIsRoomExclusive(roomExclusive);
        res.setStartAt(startAt);
        res.setEndAt(endAt);
        res.setStatus(ReservationStatus.RESERVED);
        return res;
    }

    private ResourceBookingResult buildIdempotentReplayResult(Appointment appt, ServiceTimeWindow fallbackWindow) {
        List<AppointmentResourceReservation> reserved = reservationRepository
                .lockActiveByAppointmentIdsOrderByReservationIdAsc(
                        List.of(appt.getAppointmentId()), ReservationStatus.RESERVED);
        return buildIdempotentReplayResult(appt, fallbackWindow, reserved);
    }

    private ResourceBookingResult buildIdempotentReplayResult(
            Appointment appt,
            ServiceTimeWindow fallbackWindow,
            List<AppointmentResourceReservation> reserved) {
        ServiceTimeWindow win = ResourceTimeIntervalHelper.buildWindowFromAppointment(appt);
        List<Long> eqIds = reserved.stream()
                .filter(r -> r.getResourceType() == ReservationResourceType.EQUIPMENT)
                .map(AppointmentResourceReservation::getResourceId)
                .toList();
        return new ResourceBookingResult(
                appt.getAppointmentId(),
                appt.getAppointmentCode(),
                appt.getBranch().getBranchId(),
                appt.getDoctor() != null ? appt.getDoctor().getStaffId() : null,
                appt.getAssignedRoom() != null ? appt.getAssignedRoom().getRoomId() : null,
                appt.getAssignedChair() != null ? appt.getAssignedChair().getChairId() : null,
                eqIds,
                reserved.stream().map(AppointmentResourceReservation::getReservationId).toList(),
                win != null ? win : fallbackWindow,
                true);
    }

    private boolean hasDoctorTimeOrBufferConflict(
            Long doctorId,
            Long branchId,
            Room candidateRoom,
            ServiceTimeWindow window,
            Long excludeAppointmentId,
            List<AppointmentResourceReservation> doctorReservations,
            List<Appointment> doctorAppointments) {
        Set<Long> coveredAppts = new HashSet<>();
        for (AppointmentResourceReservation res : doctorReservations) {
            if (!Objects.equals(doctorId, res.getResourceId())) {
                continue;
            }
            Appointment appt = res.getAppointment();
            if (excludeAppointmentId != null && Objects.equals(excludeAppointmentId, appt.getAppointmentId())) {
                continue;
            }
            coveredAppts.add(appt.getAppointmentId());
            Room exRoom = appt.getAssignedRoom();
            boolean sameBranch = res.getBranch() != null && Objects.equals(branchId, res.getBranch().getBranchId());
            int b1 = (sameBranch && exRoom != null) ? transitionRuleService.resolveBufferMinutes(branchId, exRoom, candidateRoom) : 0;
            int b2 = (sameBranch && exRoom != null) ? transitionRuleService.resolveBufferMinutes(branchId, candidateRoom, exRoom) : 0;
            if (ResourceTimeIntervalHelper.conflictsWithBuffer(
                    window.doctorStart(), window.doctorEnd(), res.getStartAt(), res.getEndAt(), b1, b2)) {
                return true;
            }
        }
        for (Appointment appt : doctorAppointments) {
            if (appt.getDoctor() == null || !Objects.equals(doctorId, appt.getDoctor().getStaffId())) {
                continue;
            }
            if ((excludeAppointmentId != null && Objects.equals(excludeAppointmentId, appt.getAppointmentId()))
                    || coveredAppts.contains(appt.getAppointmentId())) {
                continue;
            }
            ServiceTimeWindow exWin = ResourceTimeIntervalHelper.buildWindowFromAppointment(appt);
            if (exWin == null) {
                continue;
            }
            Room exRoom = appt.getAssignedRoom();
            boolean sameBranch = appt.getBranch() != null && Objects.equals(branchId, appt.getBranch().getBranchId());
            int b1 = (sameBranch && exRoom != null) ? transitionRuleService.resolveBufferMinutes(branchId, exRoom, candidateRoom) : 0;
            int b2 = (sameBranch && exRoom != null) ? transitionRuleService.resolveBufferMinutes(branchId, candidateRoom, exRoom) : 0;
            if (ResourceTimeIntervalHelper.conflictsWithBuffer(
                    window.doctorStart(), window.doctorEnd(), exWin.doctorStart(), exWin.doctorEnd(), b1, b2)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDoctorOnApprovedLeave(Long staffId, Long branchId, LocalDate date, Long shiftId) {
        for (LeaveRequest lr : leaveRequestRepository.findApprovedOn(staffId, date, LeaveStatus.APPROVED)) {
            boolean branchOk = lr.getBranch() == null || Objects.equals(branchId, lr.getBranch().getBranchId());
            boolean shiftOk = lr.getShift() == null || Objects.equals(shiftId, lr.getShift().getShiftId());
            if (branchOk && shiftOk) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveServicePermission(
            List<DoctorServicePermission> perms, Long staffId, Long branchId, LocalDate date) {
        for (DoctorServicePermission p : perms) {
            if (!Objects.equals(staffId, p.getStaff().getStaffId())) continue;
            if (p.getBranch() != null && !Objects.equals(branchId, p.getBranch().getBranchId())) continue;
            if (p.getEffectiveFrom() != null && p.getEffectiveFrom().isAfter(date)) continue;
            if (p.getEffectiveTo() != null && p.getEffectiveTo().isBefore(date)) continue;
            return true;
        }
        return false;
    }

    private boolean hasActiveRoomPermission(Long branchId, Long staffId, Long roomId, LocalDate date) {
        for (DoctorRoomAssignment dra : doctorRoomAssignmentRepository
                .findByBranchBranchIdAndStaffStaffIdAndStatusOrderByIsPrimaryDescAssignmentIdAsc(
                        branchId, staffId, DoctorAssignmentStatus.ACTIVE)) {
            if (!Objects.equals(roomId, dra.getRoom().getRoomId())) continue;
            if (dra.getEffectiveFrom() != null && dra.getEffectiveFrom().isAfter(date)) continue;
            if (dra.getEffectiveTo() != null && dra.getEffectiveTo().isBefore(date)) continue;
            return true;
        }
        return false;
    }

    private boolean roomMatchesRequirements(Room room, List<ServiceResourceRequirement> reqs) {
        List<ServiceResourceRequirement> typeReqs = reqs.stream()
                .filter(r -> r.getResourceKind() == ResourceRequirementKind.ROOM_TYPE && Boolean.TRUE.equals(r.getIsRequired()))
                .toList();
        if (!typeReqs.isEmpty() && typeReqs.stream().noneMatch(r -> r.getRoomType() == room.getRoomType())) {
            return false;
        }
        List<ServiceResourceRequirement> specReqs = reqs.stream()
                .filter(r -> r.getResourceKind() == ResourceRequirementKind.SPECIFIC_ROOM && Boolean.TRUE.equals(r.getIsRequired()))
                .toList();
        return specReqs.isEmpty() || specReqs.stream()
                .anyMatch(r -> r.getRoom() != null && Objects.equals(room.getRoomId(), r.getRoom().getRoomId()));
    }

    private static boolean notSameAppointment(AppointmentResourceReservation r, Long excludeAppointmentId) {
        return excludeAppointmentId == null || !Objects.equals(excludeAppointmentId, r.getAppointment().getAppointmentId());
    }

    private static ReservationUsageScope toUsageScope(ResourceUsagePhase phase) {
        if (phase == null) return ReservationUsageScope.PROCEDURE;
        return switch (phase) {
            case PREP -> ReservationUsageScope.PREP;
            case PROCEDURE -> ReservationUsageScope.PROCEDURE;
            case TURNOVER -> ReservationUsageScope.TURNOVER;
            case ALL -> ReservationUsageScope.FULL_STAY;
        };
    }

    private static PreferredSession resolveSession(LocalTime startTime) {
        if (startTime.isBefore(LocalTime.NOON)) return PreferredSession.MORNING;
        if (startTime.isBefore(LocalTime.of(17, 30))) return PreferredSession.AFTERNOON;
        return PreferredSession.EVENING;
    }

    private static void assertIdempotentPayloadMatches(
            Appointment existing,
            Long effectiveProfileId,
            ResourceBookingRequest request) {
        Long existingProfileId = existing.getPatientProfile() != null ? existing.getPatientProfile().getProfileId() : null;
        Long existingBranchId = existing.getBranch() != null ? existing.getBranch().getBranchId() : null;
        Long existingServiceId = existing.getService() != null ? existing.getService().getServiceId() : null;
        Long existingDoctorId = existing.getDoctor() != null ? existing.getDoctor().getStaffId() : null;
        boolean matches = Objects.equals(existingProfileId, effectiveProfileId)
                && Objects.equals(existingBranchId, request.branchId())
                && Objects.equals(existingServiceId, request.serviceId())
                && Objects.equals(existing.getAppointmentDate(), request.appointmentDate())
                && Objects.equals(existing.getExpectedTime(), request.startTime())
                && (request.preferredDoctorId() == null || Objects.equals(existingDoctorId, request.preferredDoctorId()));
        if (!matches) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Mã yêu cầu (idempotency key) đã được sử dụng cho thông tin lịch hẹn khác.");
        }
    }

    private ResourceBookingResult executeRescheduleAttempt(ResourceRescheduleRequest request) {
        Appointment preAppt = appointmentRepository.findById(request.appointmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));
        Long profileId = preAppt.getPatientProfile() != null ? preAppt.getPatientProfile().getProfileId() : null;
        Long branchId = preAppt.getBranch() != null ? preAppt.getBranch().getBranchId() : null;
        if (profileId == null || branchId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch hẹn thiếu thông tin hồ sơ hoặc chi nhánh.");
        }
        if (request.expectedBranchId() != null && !Objects.equals(branchId, request.expectedBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không được đổi chi nhánh khi dời lịch.");
        }

        Long oldServiceId = preAppt.getService() != null ? preAppt.getService().getServiceId() : null;
        Long effectiveServiceId = request.newServiceId() != null ? request.newServiceId() : oldServiceId;
        if (effectiveServiceId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lịch hẹn chưa có dịch vụ hợp lệ để đặt giữ tài nguyên trên Resource Engine.");
        }
        boolean serviceChanged = request.newServiceId() != null && !Objects.equals(request.newServiceId(), oldServiceId);

        com.cvdcms.entity.Service preService = serviceRepository.findById(effectiveServiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy dịch vụ."));
        if (preService.getStatus() != ServiceStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dịch vụ không ở trạng thái ACTIVE.");
        }

        LocalDate newDate = request.newDate() != null ? request.newDate() : preAppt.getAppointmentDate();
        LocalTime newStart = request.newStartTime() != null ? request.newStartTime() : preAppt.getExpectedTime();
        if (newDate == null || newStart == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lịch hẹn cần ngày và giờ cụ thể để giữ tài nguyên.");
        }

        ServiceTimeWindow preWindow = (!serviceChanged && preAppt.getProcedureDurationMinutes() != null && preAppt.getProcedureDurationMinutes() > 0)
                ? ResourceTimeIntervalHelper.buildWindowFromAppointmentAt(preAppt, newDate, newStart)
                : ResourceTimeIntervalHelper.buildWindow(preService, newDate, newStart);

        Long effectiveDoctorId = request.targetDoctorId() != null
                ? request.targetDoctorId()
                : (!request.allowDoctorAutoAssign() && preAppt.getDoctor() != null ? preAppt.getDoctor().getStaffId() : null);

        List<DoctorShiftAssignment> preShifts = shiftAssignmentRepository
                .findActiveByBranchAndDateWithDetails(branchId, newDate);
        Set<Long> candidateDoctorIds = new TreeSet<>();
        Set<Long> candidateRoomIds = new TreeSet<>();
        for (DoctorShiftAssignment dsa : preShifts) {
            if (effectiveDoctorId != null && !Objects.equals(effectiveDoctorId, dsa.getStaff().getStaffId())) {
                continue;
            }
            WorkShift sh = dsa.getShift();
            if (sh != null && Boolean.TRUE.equals(sh.getIsActive())
                    && !preWindow.doctorStart().toLocalTime().isBefore(sh.getStartTime())
                    && !preWindow.doctorEnd().toLocalTime().isAfter(sh.getEndTime())) {
                candidateDoctorIds.add(dsa.getStaff().getStaffId());
                candidateRoomIds.add(dsa.getRoom().getRoomId());
            }
        }
        if (effectiveDoctorId != null) {
            candidateDoctorIds.add(effectiveDoctorId);
        }

        List<ServiceResourceRequirement> preReqs = serviceRequirementRepository
                .findByServiceServiceIdOrderByRequirementIdAsc(effectiveServiceId);
        boolean needsEquipmentLock = preReqs.stream().anyMatch(r ->
                r.getResourceKind() == ResourceRequirementKind.EQUIPMENT_TYPE
                        || r.getResourceKind() == ResourceRequirementKind.SPECIFIC_EQUIPMENT);
        List<Long> candidateEquipmentIds = needsEquipmentLock
                ? clinicEquipmentRepository.findByBranchBranchIdAndStatusOrderByEquipmentCodeAsc(
                        branchId, EquipmentStatus.ACTIVE)
                        .stream().map(ClinicEquipment::getEquipmentId).sorted().toList()
                : List.of();

        // ==================== GLOBAL LOCK ORDERING: Cấp 2 -> Cấp 10 ====================
        patientProfileRepository.findByIdForUpdate(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));
        Branch branch = branchRepository.findByIdForUpdate(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chi nhánh đang ngưng hoạt động.");
        }
        if (candidateDoctorIds.isEmpty() || candidateRoomIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không có bác sĩ nào được phân công phòng trực khả dụng trong khung giờ mới.");
        }

        Map<Long, StaffProfile> lockedDoctors = new LinkedHashMap<>();
        for (StaffProfile sp : staffProfileRepository.findByIdInForUpdateOrderByStaffIdAsc(candidateDoctorIds)) {
            lockedDoctors.put(sp.getStaffId(), sp);
        }
        List<DoctorShiftAssignment> lockedShiftAssignments = new ArrayList<>();
        for (Long doctorId : candidateDoctorIds) {
            staffScheduleRepository.lockDoctorSchedulesForDate(doctorId, newDate, ScheduleStatus.SCHEDULED);
            lockedShiftAssignments.addAll(shiftAssignmentRepository.lockByStaffAndDateOrderByIdAsc(doctorId, newDate));
        }
        Map<Long, Room> lockedRooms = new LinkedHashMap<>();
        for (Room r : roomRepository.findByIdInForUpdateOrderByRoomIdAsc(candidateRoomIds)) {
            lockedRooms.put(r.getRoomId(), r);
        }
        List<DentalChair> lockedChairs = dentalChairRepository.findCandidatesByRoomIdsForUpdateOrderByChairIdAsc(
                candidateRoomIds, EXCLUDED_CHAIR_STATUSES);
        List<ClinicEquipment> lockedEquipments = candidateEquipmentIds.isEmpty()
                ? List.of()
                : clinicEquipmentRepository.findByIdInForUpdateOrderByEquipmentIdAsc(candidateEquipmentIds);

        // Cấp 9: Appointment
        Appointment appt = appointmentRepository.lockById(request.appointmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn."));
        if (appt.getStatus() != AppointmentStatus.PENDING && appt.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Chỉ có thể dời lịch hoặc đổi tài nguyên khi lịch hẹn đang ở trạng thái PENDING hoặc CONFIRMED.");
        }
        assertNoActiveVisitForAppointment(appt.getAppointmentId());

        // Cấp 10: Lock existing RESERVED reservations (không xóa trước khi xác nhận đủ tài nguyên mới)
        List<AppointmentResourceReservation> oldReservations = reservationRepository
                .lockActiveByAppointmentIdsOrderByReservationIdAsc(
                        List.of(appt.getAppointmentId()), ReservationStatus.RESERVED);

        com.cvdcms.entity.Service service = serviceRepository.findById(effectiveServiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy dịch vụ."));
        if (service.getStatus() != ServiceStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dịch vụ không ở trạng thái ACTIVE.");
        }
        ServiceTimeWindow window = (!serviceChanged && appt.getProcedureDurationMinutes() != null && appt.getProcedureDurationMinutes() > 0)
                ? ResourceTimeIntervalHelper.buildWindowFromAppointmentAt(appt, newDate, newStart)
                : ResourceTimeIntervalHelper.buildWindow(service, newDate, newStart);
        List<ServiceResourceRequirement> serviceReqs = serviceRequirementRepository
                .findByServiceServiceIdOrderByRequirementIdAsc(service.getServiceId());
        List<DoctorServicePermission> activeSvcPerms = doctorServicePermissionRepository
                .findByServiceServiceIdAndStatusOrderByPermissionIdAsc(
                        service.getServiceId(), DoctorServicePermissionStatus.ACTIVE);

        int maxBranchBuffer = transitionRuleService.getActiveRules(branch.getBranchId()).stream()
                .mapToInt(r -> r.getTravelBufferMinutes() != null ? r.getTravelBufferMinutes() : 0)
                .max()
                .orElse(0);

        List<AppointmentResourceReservation> doctorReservations = reservationRepository
                .findActiveDoctorReservationsInRange(
                        candidateDoctorIds,
                        window.doctorStart().minusMinutes(maxBranchBuffer),
                        window.doctorEnd().plusMinutes(maxBranchBuffer));
        List<Appointment> doctorAppointments = appointmentRepository
                .findActiveByDoctorIdsAndDateWithDetails(candidateDoctorIds, newDate, ACTIVE_APPOINTMENT_STATUSES);
        List<AppointmentResourceReservation> branchOverlapReservations = reservationRepository
                .findActiveBranchReservationsByTypesInRange(
                        branch.getBranchId(),
                        List.of(ReservationResourceType.ROOM, ReservationResourceType.CHAIR, ReservationResourceType.EQUIPMENT),
                        window.t0(),
                        window.t3());

        ResourceBookingRequest probeReq = new ResourceBookingRequest(
                branchId, profileId, effectiveServiceId, newDate, newStart,
                effectiveDoctorId, request.allowDoctorAutoAssign(), appt.getAppointmentId(),
                request.newSession(), appt.getSource(), appt.getInitialSymptoms(), null, request.immediateDispatch());

        SelectedAllocation allocation = selectValidAllocationUnderLocks(
                probeReq, branch, service, window,
                lockedShiftAssignments, lockedDoctors, lockedRooms, lockedChairs, lockedEquipments,
                serviceReqs, activeSvcPerms, doctorReservations, doctorAppointments, branchOverlapReservations);

        // Chỉ chuyển reservation cũ sang CANCELLED sau khi đã xác nhận đủ tài nguyên mới
        LocalDateTime now = LocalDateTime.now();
        String releaseReason = (request.releaseReason() != null && !request.releaseReason().isBlank())
                ? request.releaseReason() : "RESCHEDULED";
        for (AppointmentResourceReservation oldRes : oldReservations) {
            oldRes.setStatus(ReservationStatus.CANCELLED);
            oldRes.setReleasedReason(releaseReason);
            oldRes.setReleasedAt(now);
        }
        if (!oldReservations.isEmpty()) {
            reservationRepository.saveAllAndFlush(oldReservations);
        }

        appt.setDoctor(allocation.doctor());
        appt.setIsDoctorRequested(!request.allowDoctorAutoAssign());
        appt.setService(service);
        appt.setAppointmentDate(newDate);
        appt.setExpectedTime(newStart);
        appt.setPreferredSession(request.newSession() != null ? request.newSession() : resolveSession(newStart));
        appt.setDurationMinutes(window.totalMinutes());
        appt.setExpectedEndTime(window.t3().toLocalTime());
        appt.setPreparationDurationMinutes(window.prepMinutes());
        appt.setProcedureDurationMinutes(window.procedureMinutes());
        appt.setTurnoverDurationMinutes(window.turnoverMinutes());
        appt.setIsRoomExclusiveSnapshot(window.roomExclusive());
        appt.setDoctorRequiredInPrepSnapshot(window.doctorRequiredInPrep());
        appt.setDoctorRequiredInTurnoverSnapshot(window.doctorRequiredInTurnover());
        appt.setAssignedRoom(allocation.room());
        appt.setAssignedChair(allocation.chair());
        appt.setResourceStatus(AppointmentResourceStatus.OK);
        appt.setResourceConflictNote(null);
        if (request.targetStatus() != null) {
            appt.setStatus(request.targetStatus());
        }
        if (request.incrementRescheduleCount()) {
            appt.setRescheduleCount(appt.getRescheduleCount() + 1);
        }
        appt = appointmentRepository.saveAndFlush(appt);

        List<AppointmentResourceReservation> createdReservations = persistReservations(appt, branch, window, allocation);
        return new ResourceBookingResult(
                appt.getAppointmentId(),
                appt.getAppointmentCode(),
                branch.getBranchId(),
                allocation.doctor().getStaffId(),
                allocation.room().getRoomId(),
                allocation.chair() != null ? allocation.chair().getChairId() : null,
                allocation.equipments().stream().map(ClinicEquipment::getEquipmentId).toList(),
                createdReservations.stream().map(AppointmentResourceReservation::getReservationId).toList(),
                window,
                false);
    }

    /**
     * Giải phóng reservation khi hủy hoặc đánh dấu NO_SHOW, có kiểm tra Visit thực tế và trạng thái ghế.
     */
    public int releaseReservationsOnCancelOrNoShow(
            Long appointmentId,
            ReservationStatus targetStatus,
            String reason,
            LocalDateTime now) {
        if (appointmentId == null) {
            return 0;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return doReleaseReservationsOnCancelOrNoShow(appointmentId, targetStatus, reason, now);
        }
        Integer count = txTemplate.execute(status ->
                doReleaseReservationsOnCancelOrNoShow(appointmentId, targetStatus, reason, now));
        return count != null ? count : 0;
    }

    private int doReleaseReservationsOnCancelOrNoShow(
            Long appointmentId,
            ReservationStatus targetStatus,
            String reason,
            LocalDateTime now) {
        List<AppointmentResourceReservation> active = reservationRepository
                .lockActiveByAppointmentIdsOrderByReservationIdAsc(
                        List.of(appointmentId), ReservationStatus.RESERVED);
        if (active.isEmpty()) {
            return 0;
        }
        assertNoActiveVisitForAppointment(appointmentId);
        List<Long> chairIds = active.stream()
                .filter(r -> r.getResourceType() == ReservationResourceType.CHAIR)
                .map(AppointmentResourceReservation::getResourceId)
                .distinct()
                .toList();
        for (Long chairId : chairIds) {
            dentalChairRepository.findById(chairId).ifPresent(ch -> {
                if (ch.getStatus() == ChairStatus.IN_USE || ch.getStatus() == ChairStatus.TURNOVER) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Không thể giải phóng tài nguyên vì ghế đang ở trạng thái " + ch.getStatus() + ".");
                }
            });
        }
        LocalDateTime releaseTime = now != null ? now : LocalDateTime.now();
        for (AppointmentResourceReservation res : active) {
            res.setStatus(targetStatus != null ? targetStatus : ReservationStatus.CANCELLED);
            res.setReleasedReason(reason);
            res.setReleasedAt(releaseTime);
        }
        reservationRepository.saveAllAndFlush(active);
        return active.size();
    }

    /**
     * Xử lý lifecycle khi Visit hoàn thành (DONE):
     * - Giải phóng DOCTOR reservation nếu xong sớm (trừ khi doctorRequiredInTurnover=true)
     * - Giải phóng EQUIPMENT chỉ dùng pha PROCEDURE
     * - Giữ nguyên ROOM, CHAIR và EQUIPMENT(TURNOVER/ALL) ở trạng thái RESERVED cho tới khi xác nhận Turnover.
     */
    public void onVisitDone(Long appointmentId, LocalDateTime now) {
        if (appointmentId == null) {
            return;
        }
        List<AppointmentResourceReservation> active = reservationRepository
                .lockActiveByAppointmentIdsOrderByReservationIdAsc(
                        List.of(appointmentId), ReservationStatus.RESERVED);
        if (active.isEmpty()) {
            return;
        }
        Appointment appt = appointmentRepository.findById(appointmentId).orElse(null);
        boolean doctorInTurnover = appt != null && Boolean.TRUE.equals(appt.getDoctorRequiredInTurnoverSnapshot());
        LocalDateTime ts = now != null ? now : LocalDateTime.now();
        List<AppointmentResourceReservation> toUpdate = new ArrayList<>();
        for (AppointmentResourceReservation res : active) {
            if (res.getResourceType() == ReservationResourceType.DOCTOR && !doctorInTurnover) {
                res.setStatus(ReservationStatus.RELEASED);
                res.setReleasedReason("COMPLETED_EARLY");
                res.setReleasedAt(ts);
                toUpdate.add(res);
            } else if (res.getResourceType() == ReservationResourceType.EQUIPMENT
                    && res.getUsageScope() == ReservationUsageScope.PROCEDURE) {
                res.setStatus(ReservationStatus.RELEASED);
                res.setReleasedReason("PROCEDURE_COMPLETED");
                res.setReleasedAt(ts);
                toUpdate.add(res);
            }
        }
        if (!toUpdate.isEmpty()) {
            reservationRepository.saveAllAndFlush(toUpdate);
        }
    }

    /**
     * Xác nhận hoàn tất vệ sinh ghế (Turnover completed):
     * chuyển ghế TURNOVER -> AVAILABLE đồng thời giải phóng ROOM/CHAIR/EQUIPMENT(TURNOVER, FULL_STAY).
     */
    public void completeChairTurnover(Long chairId, Long appointmentId, LocalDateTime now) {
        LocalDateTime ts = now != null ? now : LocalDateTime.now();
        if (chairId != null) {
            DentalChair chair = dentalChairRepository.findById(chairId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ghế."));
            if (chair.getStatus() == ChairStatus.TURNOVER) {
                chair.setStatus(ChairStatus.AVAILABLE);
                dentalChairRepository.saveAndFlush(chair);
            }
        }
        if (appointmentId != null) {
            List<AppointmentResourceReservation> active = reservationRepository
                    .lockActiveByAppointmentIdsOrderByReservationIdAsc(
                            List.of(appointmentId), ReservationStatus.RESERVED);
            for (AppointmentResourceReservation res : active) {
                res.setStatus(ReservationStatus.RELEASED);
                res.setReleasedReason("TURNOVER_COMPLETED");
                res.setReleasedAt(ts);
            }
            if (!active.isEmpty()) {
                reservationRepository.saveAllAndFlush(active);
            }
        }
    }

    private void assertNoActiveVisitForAppointment(Long appointmentId) {
        if (visitRepository == null || appointmentId == null) {
            return;
        }
        visitRepository.findByAppointmentId(appointmentId).ifPresent(v -> {
            if (v.getStatus() != VisitStatus.CANCELLED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Lịch hẹn đã có ca khám ở trạng thái " + v.getStatus() + ", không thể hủy hoặc giải phóng tài nguyên.");
            }
        });
    }

    private static void validateRescheduleRequest(ResourceRescheduleRequest request) {
        if (request == null || request.appointmentId() == null) {
            throw new IllegalArgumentException("Thiếu appointmentId khi dời lịch.");
        }
    }

    private static void validateBasicRequest(ResourceBookingRequest request) {
        if (request == null || request.branchId() == null || request.serviceId() == null
                || request.appointmentDate() == null || request.startTime() == null) {
            throw new IllegalArgumentException("Thiếu thông tin chi nhánh, dịch vụ, ngày hoặc giờ đặt lịch.");
        }
        if (request.existingAppointmentId() == null && request.profileId() == null) {
            throw new IllegalArgumentException("Cần profileId khi tạo lịch hẹn mới.");
        }
        if (request.preferredDoctorId() == null && !request.allowDoctorAutoAssign()) {
            throw new IllegalArgumentException("Phải chọn bác sĩ hoặc bật allowDoctorAutoAssign.");
        }
    }

    public static String deriveIdempotentAppointmentCode(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(17);
            for (int i = 0; i < 9 && hex.length() < 17; i++) {
                String b = Integer.toHexString(0xff & hash[i]);
                if (b.length() == 1) hex.append('0');
                hex.append(b);
            }
            return "BK-" + hex.substring(0, 17).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String generateUniqueCode() {
        return "BK" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
    }
}
