package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
public class DoctorShiftAssignmentService {

    private final DoctorShiftAssignmentRepository shiftAssignmentRepository;
    private final DoctorShiftReplacementLogRepository replacementLogRepository;
    private final StaffScheduleRepository staffScheduleRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final BranchRepository branchRepository;
    private final WorkShiftRepository workShiftRepository;
    private final RoomRepository roomRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final DoctorRoomAssignmentRepository doctorRoomAssignmentRepository;
    private final DoctorServicePermissionRepository doctorServicePermissionRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentResourceReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final SystemAuditLogRepository systemAuditLogRepository;
    private final AppointmentHistoryRepository appointmentHistoryRepository;

    @Autowired(required = false)
    private DentalChairRepository dentalChairRepository;

    @Autowired
    public DoctorShiftAssignmentService(
            DoctorShiftAssignmentRepository shiftAssignmentRepository,
            DoctorShiftReplacementLogRepository replacementLogRepository,
            StaffScheduleRepository staffScheduleRepository,
            StaffProfileRepository staffProfileRepository,
            BranchRepository branchRepository,
            WorkShiftRepository workShiftRepository,
            RoomRepository roomRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            DoctorRoomAssignmentRepository doctorRoomAssignmentRepository,
            DoctorServicePermissionRepository doctorServicePermissionRepository,
            LeaveRequestRepository leaveRequestRepository,
            AppointmentRepository appointmentRepository,
            AppointmentResourceReservationRepository reservationRepository,
            UserRepository userRepository,
            SystemAuditLogRepository systemAuditLogRepository,
            AppointmentHistoryRepository appointmentHistoryRepository) {
        this.shiftAssignmentRepository = shiftAssignmentRepository;
        this.replacementLogRepository = replacementLogRepository;
        this.staffScheduleRepository = staffScheduleRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.branchRepository = branchRepository;
        this.workShiftRepository = workShiftRepository;
        this.roomRepository = roomRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.doctorRoomAssignmentRepository = doctorRoomAssignmentRepository;
        this.doctorServicePermissionRepository = doctorServicePermissionRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.appointmentRepository = appointmentRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.systemAuditLogRepository = systemAuditLogRepository;
        this.appointmentHistoryRepository = appointmentHistoryRepository;
    }

    public record AffectedAppointmentSummary(
            Long appointmentId,
            String appointmentCode,
            String patientName,
            LocalTime expectedTime,
            Long serviceId,
            String serviceName,
            String status) {}

    public record ReplacementCandidateSummary(
            Long staffId,
            String staffCode,
            String fullName,
            String specialty,
            boolean hasRoomPermission,
            boolean hasTimeConflict,
            String conflictMessage,
            List<String> missingServices,
            boolean eligibleWithoutOverride) {}

    public record ReplacementPreview(
            Long originalAssignmentId,
            Long branchId,
            LocalDate workDate,
            Long shiftId,
            String shiftLabel,
            Long roomId,
            String roomLabel,
            Long originalStaffId,
            String originalStaffName,
            List<AffectedAppointmentSummary> affectedAppointments,
            List<ReplacementCandidateSummary> candidates) {}

    public record ReplacementResult(
            Long replacementLogId,
            Long newShiftAssignmentId,
            int transferredAppointments,
            int needsReassignmentAppointments) {}

    /**
     * Phiên bản cô lập transaction (REQUIRES_NEW) phục vụ copyWeek: nếu 1 ca/bác sĩ không còn đủ điều kiện
     * (vd từng thay thế ngoại lệ hoặc nghỉ phép), chỉ rollback item đó và không làm hỏng outer transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<DoctorShiftAssignment> assignDoctorsToRoomIsolated(
            Long branchId,
            LocalDate workDate,
            Long shiftId,
            Long roomId,
            List<Long> rawStaffIds,
            DoctorShiftAssignmentRole role,
            String note,
            Long actorUserId) {
        return assignDoctorsToRoom(branchId, workDate, shiftId, roomId, null, rawStaffIds, role, note, actorUserId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<DoctorShiftAssignment> assignDoctorsToRoomIsolated(
            Long branchId,
            LocalDate workDate,
            Long shiftId,
            Long roomId,
            Long chairId,
            List<Long> rawStaffIds,
            DoctorShiftAssignmentRole role,
            String note,
            Long actorUserId) {
        return assignDoctorsToRoom(branchId, workDate, shiftId, roomId, chairId, rawStaffIds, role, note, actorUserId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<DoctorShiftAssignment> assignDoctorsToRoom(
            Long branchId,
            LocalDate workDate,
            Long shiftId,
            Long roomId,
            List<Long> rawStaffIds,
            DoctorShiftAssignmentRole role,
            String note,
            Long actorUserId) {
        return assignDoctorsToRoom(branchId, workDate, shiftId, roomId, null, rawStaffIds, role, note, actorUserId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<DoctorShiftAssignment> assignDoctorsToRoom(
            Long branchId,
            LocalDate workDate,
            Long shiftId,
            Long roomId,
            Long chairId,
            List<Long> rawStaffIds,
            DoctorShiftAssignmentRole role,
            String note,
            Long actorUserId) {
        if (branchId == null || workDate == null || shiftId == null || roomId == null
                || rawStaffIds == null || rawStaffIds.isEmpty()) {
            throw new IllegalArgumentException("Thiếu thông tin chi nhánh, ngày, ca, phòng hoặc bác sĩ.");
        }
        if (workDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Không thể phân công ca trực cho ngày đã qua.");
        }

        List<Long> sortedStaffIds = rawStaffIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (sortedStaffIds.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một bác sĩ.");
        }
        if (chairId != null && sortedStaffIds.size() > 1) {
            throw new IllegalArgumentException("Mỗi ghế khám cố định trong ca chỉ gán cho một bác sĩ. Vui lòng chọn 1 bác sĩ hoặc để 'Linh hoạt mọi ghế trong phòng'.");
        }

        // Global Lock Ordering: Branch(3) -> StaffProfile(4, staff_id ASC) -> StaffSchedule/DoctorShiftAssignment(5) -> Room(6)
        Branch branch = branchRepository.findByIdForUpdate(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        WorkShift shift = workShiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca làm việc."));
        if (shift.getBranch() == null || !Objects.equals(branchId, shift.getBranch().getBranchId())) {
            throw new IllegalArgumentException("Ca làm việc không thuộc chi nhánh đã chọn.");
        }
        if (!Boolean.TRUE.equals(shift.getIsActive())) {
            throw new IllegalArgumentException("Ca làm việc đang ngưng hoạt động.");
        }

        Map<Long, StaffProfile> lockedDoctors = lockDoctorsInOrder(sortedStaffIds);
        for (Long sid : sortedStaffIds) {
            staffScheduleRepository.lockDoctorSchedulesForDate(sid, workDate, ScheduleStatus.SCHEDULED);
            shiftAssignmentRepository.lockByStaffAndDateOrderByIdAsc(sid, workDate);
        }
        Room room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng khám."));
        if (room.getBranch() == null || !Objects.equals(branchId, room.getBranch().getBranchId())) {
            throw new IllegalArgumentException("Phòng khám không thuộc chi nhánh đã chọn.");
        }
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new IllegalArgumentException("Chỉ được phân công vào phòng đang ở trạng thái ACTIVE.");
        }

        DentalChair assignedChair = null;
        if (chairId != null && dentalChairRepository != null) {
            assignedChair = dentalChairRepository.findById(chairId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ghế khám đã chọn."));
            if (assignedChair.getBranch() == null || !Objects.equals(branchId, assignedChair.getBranch().getBranchId())
                    || assignedChair.getRoom() == null || !Objects.equals(roomId, assignedChair.getRoom().getRoomId())) {
                throw new IllegalArgumentException("Ghế khám đã chọn không thuộc phòng " + room.getRoomCode() + ".");
            }
            if (assignedChair.getStatus() == ChairStatus.MAINTENANCE || assignedChair.getStatus() == ChairStatus.INACTIVE) {
                throw new IllegalArgumentException("Ghế " + assignedChair.getChairCode() + " đang bảo trì hoặc không hoạt động, không thể gán trực.");
            }
        }

        User actor = actorUserId != null ? userRepository.findById(actorUserId).orElse(null) : null;
        DoctorShiftAssignmentRole effectiveRole = (role != null && role != DoctorShiftAssignmentRole.REPLACEMENT)
                ? role : DoctorShiftAssignmentRole.CO_ATTENDING;
        if (effectiveRole == DoctorShiftAssignmentRole.PRIMARY && sortedStaffIds.size() > 1) {
            throw new IllegalArgumentException("Mỗi phòng trong một ca chỉ có tối đa 1 Bác sĩ chính. Vui lòng chọn 1 bác sĩ hoặc đổi sang vai trò 'Bác sĩ cùng trực'.");
        }

        List<DoctorShiftAssignment> created = new ArrayList<>();
        for (Long sid : sortedStaffIds) {
            StaffProfile doctor = lockedDoctors.get(sid);
            validateDoctorEligibleForShiftAndRoom(doctor, branchId, workDate, shift, room, false);

            StaffSchedule schedule = findOrCreateDoctorScheduleForShift(doctor, branch, shift, workDate, note);

            // Bảo đảm không tồn tại phân công ACTIVE cho cùng staffSchedule (active_slot_key đã bảo vệ thêm ở DB)
            List<DoctorShiftAssignment> existingAtSameRoom = shiftAssignmentRepository
                    .findByBranch_BranchIdAndWorkDateAndShift_ShiftIdAndRoom_RoomIdAndStatusOrderByShiftAssignmentIdAsc(
                            branchId, workDate, shiftId, roomId, DoctorShiftAssignmentStatus.ACTIVE);
            boolean alreadyInRoom = existingAtSameRoom.stream()
                    .anyMatch(d -> d.getStaff() != null && Objects.equals(sid, d.getStaff().getStaffId()));
            if (alreadyInRoom) {
                throw new IllegalArgumentException("Bác sĩ " + doctor.getFullName() + " đã được phân công vào phòng " + room.getRoomCode() + " trong ca này.");
            }
            if (effectiveRole == DoctorShiftAssignmentRole.PRIMARY) {
                Optional<DoctorShiftAssignment> existingPrimary = existingAtSameRoom.stream()
                        .filter(d -> d.getAssignmentRole() == DoctorShiftAssignmentRole.PRIMARY)
                        .findFirst();
                if (existingPrimary.isPresent()) {
                    String primaryName = existingPrimary.get().getStaff() != null ? existingPrimary.get().getStaff().getFullName() : "bác sĩ khác";
                    throw new IllegalArgumentException("Phòng " + room.getRoomCode() + " trong ca này đã có Bác sĩ chính (" + primaryName + "). Vui lòng chọn vai trò 'Bác sĩ cùng trực'.");
                }
            }
            if (assignedChair != null) {
                Long targetChairId = assignedChair.getChairId();
                Optional<DoctorShiftAssignment> chairConflict = existingAtSameRoom.stream()
                        .filter(d -> d.getChair() != null && Objects.equals(targetChairId, d.getChair().getChairId()))
                        .findFirst();
                if (chairConflict.isPresent()) {
                    String otherDoc = chairConflict.get().getStaff() != null ? chairConflict.get().getStaff().getFullName() : "bác sĩ khác";
                    throw new IllegalArgumentException("Ghế " + assignedChair.getChairCode() + " đã được gán cho " + otherDoc + " trong ca này.");
                }
            }

            DoctorShiftAssignment assignment = new DoctorShiftAssignment();
            assignment.setStaffSchedule(schedule);
            assignment.setBranch(branch);
            assignment.setWorkDate(workDate);
            assignment.setShift(shift);
            assignment.setRoom(room);
            assignment.setChair(assignedChair);
            assignment.setStaff(doctor);
            assignment.setAssignmentRole(effectiveRole);
            assignment.setStatus(DoctorShiftAssignmentStatus.ACTIVE);
            assignment.setRoomPermissionOverridden(false);
            assignment.setNote(cleanNote(note));
            assignment.setCreatedBy(actor);
            assignment.syncActiveSlotKey();

            created.add(shiftAssignmentRepository.saveAndFlush(assignment));
        }
        return created;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeDoctorFromRoom(Long shiftAssignmentId, Long actorUserId) {
        DoctorShiftAssignment snapshot = shiftAssignmentRepository.findById(shiftAssignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công bác sĩ theo ca."));
        if (snapshot.getWorkDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Không thể gỡ phân công của ngày đã qua.");
        }

        Long branchId = snapshot.getBranch().getBranchId();
        Long staffId = snapshot.getStaff().getStaffId();
        Long roomId = snapshot.getRoom().getRoomId();
        LocalDate workDate = snapshot.getWorkDate();

        List<Long> candidateApptIds = appointmentRepository.findAffectedAppointments(
                        staffId, workDate, workDate,
                        List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN))
                .stream()
                .map(Appointment::getAppointmentId)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        // Global Lock Ordering: 3 -> 4 -> 5 -> 6 -> 9 -> 10
        branchRepository.findByIdForUpdate(branchId);
        staffProfileRepository.findByIdInForUpdateOrderByStaffIdAsc(List.of(staffId));
        staffScheduleRepository.lockDoctorSchedulesForDate(staffId, workDate, ScheduleStatus.SCHEDULED);
        shiftAssignmentRepository.lockByStaffAndDateOrderByIdAsc(staffId, workDate);
        roomRepository.findByIdForUpdate(roomId);
        if (!candidateApptIds.isEmpty()) {
            appointmentRepository.findByIdInForUpdateOrderByAppointmentIdAsc(candidateApptIds);
            reservationRepository.lockActiveByAppointmentIdsOrderByReservationIdAsc(candidateApptIds, ReservationStatus.RESERVED);
        }

        DoctorShiftAssignment locked = shiftAssignmentRepository.lockById(shiftAssignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công bác sĩ theo ca."));
        if (locked.getStatus() == DoctorShiftAssignmentStatus.CANCELLED || locked.getStatus() == DoctorShiftAssignmentStatus.REPLACED) {
            throw new IllegalArgumentException("Phân công này đã được đóng hoặc thay thế trước đó.");
        }

        WorkShift shift = locked.getShift();
        List<Appointment> activeAppts = findAppointmentsInShift(staffId, branchId, workDate, shift,
                List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN));
        if (!activeAppts.isEmpty()) {
            boolean hasCheckedIn = activeAppts.stream().anyMatch(a -> a.getStatus() == AppointmentStatus.CHECKED_IN);
            if (hasCheckedIn) {
                throw new IllegalStateException("Không thể gỡ bác sĩ vì đang có bệnh nhân CHECKED_IN trong ca trực.");
            }
            throw new IllegalStateException("Bác sĩ vẫn còn " + activeAppts.size()
                    + " lịch hẹn trong ca. Vui lòng sử dụng chức năng 'Thay thế bác sĩ' để xử lý an toàn.");
        }

        locked.setStatus(DoctorShiftAssignmentStatus.CANCELLED);
        locked.syncActiveSlotKey();
        shiftAssignmentRepository.saveAndFlush(locked);

        // Nếu StaffSchedule gốc được tạo riêng cho ca trực này và không còn phân công ACTIVE khác, có thể gỡ/giữ tùy lịch nhân sự;
        // ở đây giữ an toàn: chỉ xóa StaffSchedule nếu không có attendance và chưa có lịch hẹn nào
        StaffSchedule sched = locked.getStaffSchedule();
        if (sched != null && sched.getStatus() == ScheduleStatus.SCHEDULED) {
            List<DoctorShiftAssignment> remainingActive = shiftAssignmentRepository
                    .findByStaffSchedule_ScheduleIdAndStatusInOrderByShiftAssignmentIdAsc(
                            sched.getScheduleId(),
                            List.of(DoctorShiftAssignmentStatus.ACTIVE, DoctorShiftAssignmentStatus.ON_LEAVE));
            if (remainingActive.isEmpty()) {
                sched.setStatus(ScheduleStatus.CANCELLED);
                staffScheduleRepository.save(sched);
            }
        }
        writeAuditLog(actorUserId, locked.getBranch(), "REMOVE_DOCTOR_SHIFT_ROOM", "doctor_shift_assignments",
                locked.getShiftAssignmentId(),
                Map.of("staffId", staffId, "roomId", roomId, "status", "ACTIVE"),
                Map.of("status", "CANCELLED"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DoctorShiftAssignment moveDoctorToRoom(Long shiftAssignmentId, Long targetRoomId, Long actorUserId) {
        DoctorShiftAssignment snapshot = shiftAssignmentRepository.findById(shiftAssignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công bác sĩ theo ca."));
        if (snapshot.getWorkDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Không thể chuyển phòng cho ca đã qua.");
        }
        Long branchId = snapshot.getBranch().getBranchId();
        Long staffId = snapshot.getStaff().getStaffId();
        Long oldRoomId = snapshot.getRoom().getRoomId();
        LocalDate workDate = snapshot.getWorkDate();
        if (Objects.equals(oldRoomId, targetRoomId)) {
            throw new IllegalArgumentException("Bác sĩ đang được phân công tại chính phòng này.");
        }

        List<Long> candidateApptIds = appointmentRepository.findAffectedAppointments(
                        staffId, workDate, workDate,
                        List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN))
                .stream().map(Appointment::getAppointmentId).filter(Objects::nonNull).sorted().toList();

        List<Long> sortedRoomIds = Arrays.stream(new Long[]{oldRoomId, targetRoomId}).distinct().sorted().toList();

        // Global Lock Ordering: 3 -> 4 -> 5 -> 6 (sorted roomId ASC) -> 9 -> 10
        branchRepository.findByIdForUpdate(branchId);
        staffProfileRepository.findByIdInForUpdateOrderByStaffIdAsc(List.of(staffId));
        staffScheduleRepository.lockDoctorSchedulesForDate(staffId, workDate, ScheduleStatus.SCHEDULED);
        shiftAssignmentRepository.lockByStaffAndDateOrderByIdAsc(staffId, workDate);
        List<Room> lockedRooms = roomRepository.findByIdInForUpdateOrderByRoomIdAsc(sortedRoomIds);
        if (!candidateApptIds.isEmpty()) {
            appointmentRepository.findByIdInForUpdateOrderByAppointmentIdAsc(candidateApptIds);
            reservationRepository.lockActiveByAppointmentIdsOrderByReservationIdAsc(candidateApptIds, ReservationStatus.RESERVED);
        }

        DoctorShiftAssignment lockedOld = shiftAssignmentRepository.lockById(shiftAssignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công bác sĩ theo ca."));
        if (lockedOld.getStatus() != DoctorShiftAssignmentStatus.ACTIVE) {
            throw new IllegalArgumentException("Chỉ có thể chuyển phòng cho phân công đang ở trạng thái ACTIVE.");
        }

        Room targetRoom = lockedRooms.stream()
                .filter(r -> Objects.equals(r.getRoomId(), targetRoomId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng đích."));
        if (targetRoom.getBranch() == null || !Objects.equals(branchId, targetRoom.getBranch().getBranchId())) {
            throw new IllegalArgumentException("Phòng đích không thuộc chi nhánh này.");
        }
        if (targetRoom.getStatus() != RoomStatus.ACTIVE) {
            throw new IllegalArgumentException("Phòng đích không ở trạng thái ACTIVE.");
        }

        StaffProfile doctor = lockedOld.getStaff();
        validateDoctorHasActiveRoomPermission(doctor.getStaffId(), branchId, targetRoomId, workDate, doctor.getFullName(), targetRoom.getRoomCode());

        List<Appointment> activeAppts = findAppointmentsInShift(staffId, branchId, workDate, lockedOld.getShift(),
                List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN));
        if (activeAppts.stream().anyMatch(a -> a.getStatus() == AppointmentStatus.CHECKED_IN)) {
            throw new IllegalStateException("Bác sĩ đang có bệnh nhân CHECKED_IN trong ca trực, không thể chuyển phòng.");
        }

        // Đóng bản ghi ở phòng cũ để nhả active_slot_key, giữ nguyên các BS khác ở phòng cũ
        lockedOld.setStatus(DoctorShiftAssignmentStatus.CANCELLED);
        lockedOld.syncActiveSlotKey();
        shiftAssignmentRepository.saveAndFlush(lockedOld);

        User actor = actorUserId != null ? userRepository.findById(actorUserId).orElse(null) : null;
        DoctorShiftAssignment moved = new DoctorShiftAssignment();
        moved.setStaffSchedule(lockedOld.getStaffSchedule());
        moved.setBranch(lockedOld.getBranch());
        moved.setWorkDate(workDate);
        moved.setShift(lockedOld.getShift());
        moved.setRoom(targetRoom);
        moved.setStaff(doctor);
        DoctorShiftAssignmentRole movedRole = lockedOld.getAssignmentRole() == DoctorShiftAssignmentRole.REPLACEMENT
                ? DoctorShiftAssignmentRole.CO_ATTENDING : lockedOld.getAssignmentRole();
        if (movedRole == DoctorShiftAssignmentRole.PRIMARY) {
            boolean targetHasPrimary = shiftAssignmentRepository
                    .findByBranch_BranchIdAndWorkDateAndShift_ShiftIdAndRoom_RoomIdAndStatusOrderByShiftAssignmentIdAsc(
                            branchId, workDate, lockedOld.getShift().getShiftId(), targetRoomId, DoctorShiftAssignmentStatus.ACTIVE)
                    .stream()
                    .anyMatch(d -> d.getAssignmentRole() == DoctorShiftAssignmentRole.PRIMARY);
            if (targetHasPrimary) {
                movedRole = DoctorShiftAssignmentRole.CO_ATTENDING;
            }
        }
        moved.setAssignmentRole(movedRole);
        moved.setStatus(DoctorShiftAssignmentStatus.ACTIVE);
        moved.setNote(lockedOld.getNote());
        moved.setCreatedBy(actor);
        moved.syncActiveSlotKey();
        moved = shiftAssignmentRepository.saveAndFlush(moved);

        // Nếu có lịch hẹn đang giữ chỗ phòng cũ của BS này -> đánh dấu NEEDS_REASSIGNMENT để điều phối lại ghế/phòng an toàn
        for (Appointment app : activeAppts) {
            if (app.getAssignedRoom() != null && Objects.equals(oldRoomId, app.getAssignedRoom().getRoomId())) {
                app.setResourceStatus(AppointmentResourceStatus.NEEDS_REASSIGNMENT);
                app.setResourceConflictNote("Bác sĩ chuyển từ phòng " + lockedOld.getRoom().getRoomCode() + " sang " + targetRoom.getRoomCode() + "; cần xác nhận lại phòng/ghế.");
                appointmentRepository.save(app);
            }
        }

        writeAuditLog(actorUserId, lockedOld.getBranch(), "MOVE_DOCTOR_SHIFT_ROOM", "doctor_shift_assignments",
                moved.getShiftAssignmentId(),
                Map.of("oldRoomId", oldRoomId, "staffId", staffId),
                Map.of("newRoomId", targetRoomId, "newShiftAssignmentId", moved.getShiftAssignmentId()));

        return moved;
    }

    @Transactional(readOnly = true)
    public ReplacementPreview previewReplacement(Long originalShiftAssignmentId) {
        DoctorShiftAssignment orig = shiftAssignmentRepository.findById(originalShiftAssignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công bác sĩ theo ca."));
        Long branchId = orig.getBranch().getBranchId();
        LocalDate workDate = orig.getWorkDate();
        WorkShift shift = orig.getShift();
        Room room = orig.getRoom();
        StaffProfile origStaff = orig.getStaff();

        List<Appointment> affected = findAppointmentsInShift(
                origStaff.getStaffId(), branchId, workDate, shift,
                List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED));

        List<AffectedAppointmentSummary> apptSummaries = affected.stream()
                .map(a -> new AffectedAppointmentSummary(
                        a.getAppointmentId(),
                        a.getAppointmentCode(),
                        a.getPatientProfile() != null ? a.getPatientProfile().getFullName() : "-",
                        a.getExpectedTime(),
                        a.getService() != null ? a.getService().getServiceId() : null,
                        a.getService() != null ? a.getService().getServiceName() : "(Chưa chọn dịch vụ)",
                        a.getStatus() != null ? a.getStatus().name() : ""))
                .toList();

        // Tìm tất cả bác sĩ ACTIVE được phân công làm việc tại chi nhánh
        Set<Long> doctorIds = new LinkedHashSet<>();
        Map<Long, StaffProfile> doctorMap = new LinkedHashMap<>();
        for (StaffBranchAssignment sba : staffBranchAssignmentRepository.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE)) {
            if (sba.getStaff() == null || sba.getStaff().getStatus() != StaffStatus.ACTIVE) continue;
            if (Objects.equals(sba.getStaff().getStaffId(), origStaff.getStaffId())) continue;
            if (sba.getStartDate() != null && sba.getStartDate().isAfter(workDate)) continue;
            if (sba.getEndDate() != null && sba.getEndDate().isBefore(workDate)) continue;
            if (sba.getRole() != null && isDoctorRole(sba.getRole().getRoleName())) {
                doctorIds.add(sba.getStaff().getStaffId());
                doctorMap.put(sba.getStaff().getStaffId(), sba.getStaff());
            }
        }

        List<ReplacementCandidateSummary> candidates = new ArrayList<>();
        for (Long candId : doctorIds) {
            StaffProfile cand = doctorMap.get(candId);
            boolean hasRoomPerm = hasActiveRoomPermission(candId, branchId, room.getRoomId(), workDate);
            String conflictMsg = findOverlapConflictMessage(candId, branchId, workDate, shift);
            boolean hasConflict = conflictMsg != null;

            List<String> missingServices = new ArrayList<>();
            for (Appointment a : affected) {
                if (a.getService() == null || !hasActiveServicePermission(candId, a.getService().getServiceId(), branchId, workDate)) {
                    String svcLabel = a.getService() != null ? a.getService().getServiceName() : "(Thiếu dịch vụ)";
                    if (!missingServices.contains(svcLabel)) {
                        missingServices.add(svcLabel);
                    }
                }
            }

            boolean eligible = hasRoomPerm && !hasConflict && missingServices.isEmpty();
            candidates.add(new ReplacementCandidateSummary(
                    cand.getStaffId(),
                    cand.getStaffCode(),
                    cand.getFullName(),
                    cand.getSpecialty(),
                    hasRoomPerm,
                    hasConflict,
                    conflictMsg,
                    missingServices,
                    eligible));
        }

        candidates.sort(Comparator
                .comparing(ReplacementCandidateSummary::eligibleWithoutOverride).reversed()
                .thenComparing(ReplacementCandidateSummary::hasTimeConflict)
                .thenComparing(ReplacementCandidateSummary::fullName));

        String shiftLabel = shift.getShiftName() + " (" + shift.getStartTime() + " - " + shift.getEndTime() + ")";
        String roomLabel = room.getRoomCode() + " - " + room.getRoomName();
        return new ReplacementPreview(
                orig.getShiftAssignmentId(),
                branchId,
                workDate,
                shift.getShiftId(),
                shiftLabel,
                room.getRoomId(),
                roomLabel,
                origStaff.getStaffId(),
                origStaff.getFullName(),
                apptSummaries,
                candidates);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReplacementResult replaceDoctorInShift(
            Long originalShiftAssignmentId,
            Long replacementStaffId,
            boolean overrideRoomPermission,
            String replacementReason,
            Long actorUserId) {
        if (originalShiftAssignmentId == null || replacementStaffId == null) {
            throw new IllegalArgumentException("Vui lòng chọn phân công gốc và bác sĩ thay thế.");
        }
        String cleanReason = replacementReason != null ? replacementReason.trim() : "";
        if (cleanReason.length() < 5) {
            throw new IllegalArgumentException("Vui lòng nhập lý do thay thế (tối thiểu 5 ký tự).");
        }
        if (overrideRoomPermission && cleanReason.length() < 10) {
            throw new IllegalArgumentException("Ngoại lệ thay thế ngoài phân công phòng yêu cầu lý do chi tiết tối thiểu 10 ký tự.");
        }

        User actor = actorUserId != null
                ? userRepository.findById(actorUserId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Người dùng không hợp lệ."))
                : null;
        if (overrideRoomPermission) {
            if (actor == null || !userRepository.hasActiveAdminRole(actor.getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Chỉ tài khoản ADMIN mới được phép bật ngoại lệ thay thế bác sĩ ngoài phòng.");
            }
        }

        DoctorShiftAssignment snapshot = shiftAssignmentRepository.findById(originalShiftAssignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công bác sĩ gốc."));
        if (snapshot.getWorkDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Không thể thay thế bác sĩ cho ngày đã qua.");
        }

        Long branchId = snapshot.getBranch().getBranchId();
        Long oldStaffId = snapshot.getStaff().getStaffId();
        Long roomId = snapshot.getRoom().getRoomId();
        LocalDate workDate = snapshot.getWorkDate();

        if (Objects.equals(oldStaffId, replacementStaffId)) {
            throw new IllegalArgumentException("Bác sĩ thay thế không được trùng với bác sĩ gốc.");
        }

        // Đọc danh sách ID Appointment tiềm năng của cả BS cũ và BS mới trước khi khóa (cùng ngày)
        List<Long> oldApptIds = appointmentRepository.findAffectedAppointments(
                        oldStaffId, workDate, workDate,
                        List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED))
                .stream().map(Appointment::getAppointmentId).filter(Objects::nonNull).toList();
        List<Long> newApptIds = appointmentRepository.findAffectedAppointments(
                        replacementStaffId, workDate, workDate,
                        List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN))
                .stream().map(Appointment::getAppointmentId).filter(Objects::nonNull).toList();

        List<Long> combinedApptIds = new ArrayList<>();
        combinedApptIds.addAll(oldApptIds);
        combinedApptIds.addAll(newApptIds);
        List<Long> sortedApptIds = combinedApptIds.stream().distinct().sorted().toList();

        List<Long> sortedStaffIds = Arrays.stream(new Long[]{oldStaffId, replacementStaffId}).sorted().toList();

        // Global Lock Ordering: 3(Branch) -> 4(StaffProfile ASC) -> 5(StaffSchedule & DoctorShiftAssignment ASC) -> 6(Room) -> 9(Appt ASC) -> 10(Reservation ASC)
        Branch branch = branchRepository.findByIdForUpdate(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        Map<Long, StaffProfile> lockedDoctors = lockDoctorsInOrder(sortedStaffIds);
        for (Long sid : sortedStaffIds) {
            staffScheduleRepository.lockDoctorSchedulesForDate(sid, workDate, ScheduleStatus.SCHEDULED);
            shiftAssignmentRepository.lockByStaffAndDateOrderByIdAsc(sid, workDate);
        }
        Room room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng khám."));

        Map<Long, Appointment> lockedApptMap = new LinkedHashMap<>();
        if (!sortedApptIds.isEmpty()) {
            for (Appointment a : appointmentRepository.findByIdInForUpdateOrderByAppointmentIdAsc(sortedApptIds)) {
                lockedApptMap.put(a.getAppointmentId(), a);
            }
            reservationRepository.lockActiveByAppointmentIdsOrderByReservationIdAsc(sortedApptIds, ReservationStatus.RESERVED);
        }

        DoctorShiftAssignment original = shiftAssignmentRepository.lockById(originalShiftAssignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công bác sĩ gốc."));
        if (original.getStatus() != DoctorShiftAssignmentStatus.ACTIVE && original.getStatus() != DoctorShiftAssignmentStatus.ON_LEAVE) {
            throw new IllegalStateException("Phân công gốc đã được thay thế hoặc hủy trước đó.");
        }

        StaffProfile oldDoctor = lockedDoctors.get(oldStaffId);
        StaffProfile newDoctor = lockedDoctors.get(replacementStaffId);
        WorkShift shift = original.getShift();

        // Kiểm tra điều kiện BS thay thế (chi nhánh, nghỉ phép, giao cắt [start, end) đa ca/đa chi nhánh, và phân công phòng)
        validateDoctorEligibleForShiftAndRoom(newDoctor, branchId, workDate, shift, room, overrideRoomPermission);

        // Re-verify sau khi lấy lock Appointment xem có lịch mới phát sinh cho oldDoctor ngoài danh sách đã lock không
        List<Appointment> currentAffectedOld = findAppointmentsInShift(oldStaffId, branchId, workDate, shift,
                List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED));
        for (Appointment cur : currentAffectedOld) {
            if (!lockedApptMap.containsKey(cur.getAppointmentId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Phát sinh lịch hẹn mới #" + cur.getAppointmentCode() + " trong lúc xử lý thay thế. Vui lòng thử lại.");
            }
        }

        // Chỉ cập nhật bản ghi của bác sĩ bị thay thế -> REPLACED (active_slot_key = null); các bác sĩ khác cùng phòng giữ nguyên ACTIVE!
        original.setStatus(DoctorShiftAssignmentStatus.REPLACED);
        original.syncActiveSlotKey();
        shiftAssignmentRepository.saveAndFlush(original);

        StaffSchedule newDoctorSchedule = findOrCreateDoctorScheduleForShift(newDoctor, branch, shift, workDate,
                "Thay thế BS " + oldDoctor.getFullName() + " tại phòng " + room.getRoomCode());

        DoctorShiftAssignment replacement = new DoctorShiftAssignment();
        replacement.setStaffSchedule(newDoctorSchedule);
        replacement.setBranch(branch);
        replacement.setWorkDate(workDate);
        replacement.setShift(shift);
        replacement.setRoom(room);
        replacement.setChair(original.getChair());
        replacement.setStaff(newDoctor);
        replacement.setAssignmentRole(DoctorShiftAssignmentRole.REPLACEMENT);
        replacement.setStatus(DoctorShiftAssignmentStatus.ACTIVE);
        replacement.setReplacesShiftAssignment(original);
        replacement.setReplacedForStaff(oldDoctor);
        replacement.setReplacementReason(cleanReason);
        replacement.setReplacedByUser(actor);
        replacement.setLeaveRequest(original.getLeaveRequest());
        replacement.setRoomPermissionOverridden(overrideRoomPermission);
        replacement.setNote("Thay thế " + oldDoctor.getFullName());
        replacement.setCreatedBy(actor);
        replacement.syncActiveSlotKey();
        replacement = shiftAssignmentRepository.saveAndFlush(replacement);

        // Xử lý từng lịch hẹn bị ảnh hưởng của BS cũ (không tự hủy lịch, chỉ chuyển lịch đủ quyền & không trùng)
        List<Appointment> newDocExistingAppts = findAppointmentsInShift(
                replacementStaffId, branchId, workDate, shift,
                List.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN));

        int transferredCount = 0;
        int needsReassignmentCount = 0;

        for (Appointment app : currentAffectedOld) {
            boolean hasSvcPerm = app.getService() != null
                    && hasActiveServicePermission(replacementStaffId, app.getService().getServiceId(), branchId, workDate);
            boolean hasApptConflict = hasDoctorAppointmentTimeOverlap(app, newDocExistingAppts);

            if (hasSvcPerm && !hasApptConflict) {
                String oldSnap = "doctor=" + oldStaffId + "|status=" + app.getStatus();
                app.setDoctor(newDoctor);
                app.setResourceStatus(AppointmentResourceStatus.OK);
                app.setResourceConflictNote(null);
                appointmentRepository.save(app);

                // Đồng bộ DOCTOR reservation (giữ nguyên ROOM/CHAIR/EQUIPMENT nếu vẫn phù hợp)
                List<AppointmentResourceReservation> appResList = reservationRepository
                        .findByAppointment_AppointmentIdAndStatusOrderByReservationIdAsc(
                                app.getAppointmentId(), ReservationStatus.RESERVED);
                for (AppointmentResourceReservation res : appResList) {
                    if (res.getResourceType() == ReservationResourceType.DOCTOR
                            && Objects.equals(oldStaffId, res.getResourceId())) {
                        res.setStatus(ReservationStatus.RELEASED);
                        res.setReleasedReason("DOCTOR_REPLACED_BY_SHIFT");
                        res.setReleasedAt(LocalDateTime.now());
                        reservationRepository.save(res);

                        AppointmentResourceReservation newDocRes = new AppointmentResourceReservation();
                        newDocRes.setAppointment(app);
                        newDocRes.setBranch(branch);
                        newDocRes.setResourceType(ReservationResourceType.DOCTOR);
                        newDocRes.setResourceId(replacementStaffId);
                        newDocRes.setUsageScope(res.getUsageScope());
                        newDocRes.setIsRoomExclusive(false);
                        newDocRes.setStartAt(res.getStartAt());
                        newDocRes.setEndAt(res.getEndAt());
                        newDocRes.setStatus(ReservationStatus.RESERVED);
                        reservationRepository.save(newDocRes);
                    }
                }

                if (appointmentHistoryRepository != null) {
                    AppointmentHistory hist = new AppointmentHistory();
                    hist.setAppointment(app);
                    hist.setActorUserId(actor != null ? actor.getUserId() : null);
                    hist.setAction("DOCTOR_REPLACED");
                    hist.setOldValue(oldSnap);
                    hist.setNewValue("doctor=" + replacementStaffId + "|room=" + room.getRoomCode());
                    hist.setReason("Thay thế bác sĩ trực (" + oldDoctor.getFullName() + " -> " + newDoctor.getFullName() + "): " + cleanReason);
                    appointmentHistoryRepository.save(hist);
                }

                newDocExistingAppts.add(app);
                transferredCount++;
            } else {
                app.setResourceStatus(AppointmentResourceStatus.NEEDS_REASSIGNMENT);
                String noteMsg = !hasSvcPerm
                        ? "BS thay thế " + newDoctor.getFullName() + " thiếu quyền thực hiện dịch vụ"
                        : "BS thay thế " + newDoctor.getFullName() + " bị trùng khung giờ khám";
                app.setResourceConflictNote(noteMsg);
                appointmentRepository.save(app);

                if (appointmentHistoryRepository != null) {
                    AppointmentHistory hist = new AppointmentHistory();
                    hist.setAppointment(app);
                    hist.setActorUserId(actor != null ? actor.getUserId() : null);
                    hist.setAction("NEEDS_REASSIGNMENT");
                    hist.setOldValue("doctor=" + oldStaffId);
                    hist.setNewValue("doctor=" + oldStaffId + "|NEEDS_REASSIGNMENT");
                    hist.setReason(noteMsg);
                    appointmentHistoryRepository.save(hist);
                }
                needsReassignmentCount++;
            }
        }

        DoctorShiftReplacementLog log = new DoctorShiftReplacementLog();
        log.setBranch(branch);
        log.setWorkDate(workDate);
        log.setShift(shift);
        log.setRoom(room);
        log.setReplacedForStaff(oldDoctor);
        log.setReplacementStaff(newDoctor);
        log.setOriginalShiftAssignment(original);
        log.setReplacementShiftAssignment(replacement);
        log.setLeaveRequest(original.getLeaveRequest());
        log.setRoomPermissionOverridden(overrideRoomPermission);
        log.setIsEmergency(original.getLeaveRequest() != null && Boolean.TRUE.equals(original.getLeaveRequest().getIsEmergency()));
        log.setTransferredAppointmentCount(transferredCount);
        log.setNeedsReassignmentCount(needsReassignmentCount);
        log.setReplacementReason(cleanReason);
        log.setReplacedByUser(actor);
        log = replacementLogRepository.save(log);

        if (actor != null) {
            writeAuditLog(actor.getUserId(), branch,
                    overrideRoomPermission ? "REPLACE_DOCTOR_SHIFT_ROOM_OVERRIDE" : "REPLACE_DOCTOR_SHIFT",
                    "doctor_shift_replacement_logs",
                    log.getReplacementLogId(),
                    Map.of("originalStaffId", oldStaffId, "originalAssignmentId", original.getShiftAssignmentId()),
                    Map.of("replacementStaffId", replacementStaffId,
                            "replacementAssignmentId", replacement.getShiftAssignmentId(),
                            "roomId", roomId,
                            "overrideRoomPermission", overrideRoomPermission,
                            "reason", cleanReason,
                            "transferred", transferredCount,
                            "needsReassignment", needsReassignmentCount));
        }

        return new ReplacementResult(
                log.getReplacementLogId(),
                replacement.getShiftAssignmentId(),
                transferredCount,
                needsReassignmentCount);
    }

    @Transactional(readOnly = true)
    public List<DoctorShiftAssignment> findWeekAssignments(Long branchId, LocalDate fromDate, LocalDate toDate) {
        if (branchId == null || fromDate == null || toDate == null) {
            return List.of();
        }
        return shiftAssignmentRepository.findByBranchAndDateRangeWithDetails(branchId, fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public List<DoctorShiftReplacementLog> findReplacementLogs(Long branchId, LocalDate fromDate, LocalDate toDate) {
        if (branchId == null || fromDate == null || toDate == null) {
            return List.of();
        }
        return replacementLogRepository.findByBranchAndDateRangeOrderByReplacedAtDesc(branchId, fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public List<StaffProfile> findBranchDoctors(Long branchId) {
        if (branchId == null) {
            return List.of();
        }
        List<StaffProfile> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        LocalDate today = LocalDate.now();
        for (StaffBranchAssignment a : staffBranchAssignmentRepository.findByBranchAndStatus(branchId, AssignmentStatus.ACTIVE)) {
            if (a.getStaff() == null || a.getStaff().getStatus() != StaffStatus.ACTIVE) continue;
            boolean validDate = (a.getStartDate() == null || !a.getStartDate().isAfter(today))
                    && (a.getEndDate() == null || !a.getEndDate().isBefore(today));
            if (validDate && a.getRole() != null && isDoctorRole(a.getRole().getRoleName()) && seen.add(a.getStaff().getStaffId())) {
                result.add(a.getStaff());
            }
        }
        result.sort(Comparator.comparing(StaffProfile::getFullName));
        return result;
    }

    private Map<Long, StaffProfile> lockDoctorsInOrder(List<Long> sortedStaffIds) {
        List<StaffProfile> locked = staffProfileRepository.findByIdInForUpdateOrderByStaffIdAsc(sortedStaffIds);
        Map<Long, StaffProfile> map = new LinkedHashMap<>();
        for (StaffProfile sp : locked) {
            map.put(sp.getStaffId(), sp);
        }
        for (Long sid : sortedStaffIds) {
            if (!map.containsKey(sid)) {
                throw new IllegalArgumentException("Không tìm thấy hồ sơ bác sĩ ID #" + sid);
            }
        }
        return map;
    }

    private void validateDoctorEligibleForShiftAndRoom(
            StaffProfile doctor,
            Long branchId,
            LocalDate workDate,
            WorkShift shift,
            Room room,
            boolean overrideRoomPermission) {
        if (doctor.getStatus() != StaffStatus.ACTIVE) {
            throw new IllegalArgumentException("Bác sĩ " + doctor.getFullName() + " không ở trạng thái ACTIVE.");
        }
        if (!staffBranchAssignmentRepository.hasActiveDoctorAssignment(doctor.getStaffId(), branchId)) {
            throw new IllegalArgumentException("Bác sĩ " + doctor.getFullName() + " không được phân công làm bác sĩ ACTIVE tại chi nhánh này.");
        }
        boolean onLeave = leaveRequestRepository.existsOverlapping(
                doctor.getStaffId(), workDate, workDate, shift.getShiftId(), List.of(LeaveStatus.APPROVED));
        if (onLeave) {
            throw new IllegalArgumentException("Bác sĩ " + doctor.getFullName() + " đã được duyệt nghỉ phép vào ca/ngày này.");
        }
        String conflict = findOverlapConflictMessage(doctor.getStaffId(), branchId, workDate, shift);
        if (conflict != null) {
            throw new IllegalArgumentException(conflict);
        }
        if (!overrideRoomPermission) {
            validateDoctorHasActiveRoomPermission(
                    doctor.getStaffId(), branchId, room.getRoomId(), workDate, doctor.getFullName(), room.getRoomCode());
        }
    }

    private void validateDoctorHasActiveRoomPermission(
            Long staffId, Long branchId, Long roomId, LocalDate workDate, String doctorName, String roomCode) {
        if (!hasActiveRoomPermission(staffId, branchId, roomId, workDate)) {
            throw new IllegalArgumentException("Bác sĩ " + doctorName + " chưa có phân công phòng nền ACTIVE tại phòng "
                    + roomCode + " vào ngày " + workDate + ".");
        }
    }

    private boolean hasActiveRoomPermission(Long staffId, Long branchId, Long roomId, LocalDate workDate) {
        for (DoctorRoomAssignment dra : doctorRoomAssignmentRepository
                .findByBranchBranchIdAndStaffStaffIdAndStatusOrderByIsPrimaryDescAssignmentIdAsc(
                        branchId, staffId, DoctorAssignmentStatus.ACTIVE)) {
            if (dra.getRoom() == null || !Objects.equals(roomId, dra.getRoom().getRoomId())) {
                continue;
            }
            boolean validFrom = dra.getEffectiveFrom() == null || !dra.getEffectiveFrom().isAfter(workDate);
            boolean validTo = dra.getEffectiveTo() == null || !dra.getEffectiveTo().isBefore(workDate);
            if (validFrom && validTo) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveServicePermission(Long staffId, Long serviceId, Long branchId, LocalDate workDate) {
        if (staffId == null || serviceId == null) {
            return false;
        }
        for (DoctorServicePermission perm : doctorServicePermissionRepository
                .findByStaffStaffIdAndStatusOrderByPermissionIdAsc(staffId, DoctorServicePermissionStatus.ACTIVE)) {
            if (perm.getService() == null || !Objects.equals(serviceId, perm.getService().getServiceId())) {
                continue;
            }
            if (perm.getBranch() != null && branchId != null && !Objects.equals(branchId, perm.getBranch().getBranchId())) {
                continue;
            }
            boolean validFrom = perm.getEffectiveFrom() == null || !perm.getEffectiveFrom().isAfter(workDate);
            boolean validTo = perm.getEffectiveTo() == null || !perm.getEffectiveTo().isBefore(workDate);
            if (validFrom && validTo) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kiểm tra trùng khoảng nửa mở [start, end) trên toàn bộ ca trực & phân công phòng đang hiệu lực
     * ở TẤT CẢ các chi nhánh của bác sĩ trong ngày.
     */
    private String findOverlapConflictMessage(Long staffId, Long targetBranchId, LocalDate workDate, WorkShift targetShift) {
        LocalTime targetStart = targetShift.getStartTime();
        LocalTime targetEnd = targetShift.getEndTime();
        if (targetStart == null || targetEnd == null || !targetEnd.isAfter(targetStart)) {
            return "Khung giờ ca làm việc không hợp lệ.";
        }

        // 1. Kiểm tra mọi DoctorShiftAssignment ACTIVE trên toàn bộ chi nhánh trong ngày
        for (DoctorShiftAssignment ex : shiftAssignmentRepository.findActiveByStaffAndDateWithDetails(staffId, workDate)) {
            WorkShift exShift = ex.getShift();
            if (exShift == null || exShift.getStartTime() == null || exShift.getEndTime() == null) {
                continue;
            }
            if (intervalsOverlap(targetStart, targetEnd, exShift.getStartTime(), exShift.getEndTime())) {
                if (Objects.equals(ex.getBranch().getBranchId(), targetBranchId)) {
                    return "Bác sĩ đã được phân công phòng " + ex.getRoom().getRoomCode()
                            + " trong ca " + exShift.getShiftName() + " (" + exShift.getStartTime() + "-" + exShift.getEndTime() + ").";
                }
                return "Bác sĩ bị trùng thời gian trực tại chi nhánh \"" + ex.getBranch().getBranchName()
                        + "\" phòng " + ex.getRoom().getRoomCode()
                        + " (" + exShift.getStartTime() + "-" + exShift.getEndTime() + ").";
            }
        }

        // 2. Kiểm tra StaffSchedule không phải CANCELLED (kể cả ở chi nhánh khác chưa gán phòng)
        for (StaffSchedule sc : staffScheduleRepository.findByStaffStaffIdAndWorkDateAndStatusNot(staffId, workDate, ScheduleStatus.CANCELLED)) {
            WorkShift exShift = sc.getShift();
            if (exShift == null || exShift.getStartTime() == null || exShift.getEndTime() == null) {
                continue;
            }
            // Nếu trùng chính ca ở cùng chi nhánh đang xếp -> hợp lệ để gắn StaffSchedule đó vào phòng
            if (Objects.equals(sc.getBranch().getBranchId(), targetBranchId)
                    && Objects.equals(exShift.getShiftId(), targetShift.getShiftId())
                    && sc.getStatus() == ScheduleStatus.SCHEDULED) {
                continue;
            }
            if (intervalsOverlap(targetStart, targetEnd, exShift.getStartTime(), exShift.getEndTime())) {
                return "Bác sĩ đã có lịch trực tại chi nhánh \"" + sc.getBranch().getBranchName()
                        + "\" trong ca " + exShift.getShiftName() + " (" + exShift.getStartTime() + "-" + exShift.getEndTime() + ").";
            }
        }

        return null;
    }

    private StaffSchedule findOrCreateDoctorScheduleForShift(
            StaffProfile doctor, Branch branch, WorkShift shift, LocalDate workDate, String note) {
        List<StaffSchedule> existingList = staffScheduleRepository.findByStaffStaffIdAndWorkDateBetween(
                doctor.getStaffId(), workDate, workDate);
        StaffSchedule sameBranchShift = null;
        long totalMinutes = 0;

        for (StaffSchedule sc : existingList) {
            if (sc.getBranch() != null && Objects.equals(branch.getBranchId(), sc.getBranch().getBranchId())
                    && sc.getShift() != null && Objects.equals(shift.getShiftId(), sc.getShift().getShiftId())) {
                sameBranchShift = sc;
                continue;
            }
            if (sc.getStatus() != ScheduleStatus.CANCELLED && sc.getShift() != null
                    && sc.getShift().getStartTime() != null && sc.getShift().getEndTime() != null) {
                totalMinutes += Duration.between(sc.getShift().getStartTime(), sc.getShift().getEndTime()).toMinutes();
            }
        }

        long newShiftMinutes = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
        if (sameBranchShift != null) {
            if (sameBranchShift.getStatus() == ScheduleStatus.ON_LEAVE) {
                throw new IllegalArgumentException("Bác sĩ " + doctor.getFullName() + " đang ở trạng thái nghỉ phép ở ca này.");
            }
            if (sameBranchShift.getStatus() == ScheduleStatus.CANCELLED) {
                if (totalMinutes + newShiftMinutes > WorkShiftService.MAX_SHIFT_MINUTES) {
                    throw new IllegalArgumentException("Tổng thời giờ làm việc trong ngày vượt quá 12 giờ.");
                }
                sameBranchShift.setStatus(ScheduleStatus.SCHEDULED);
                if (note != null && !note.isBlank()) {
                    sameBranchShift.setNote(note.trim());
                }
                return staffScheduleRepository.saveAndFlush(sameBranchShift);
            }
            return sameBranchShift;
        }

        if (totalMinutes + newShiftMinutes > WorkShiftService.MAX_SHIFT_MINUTES) {
            throw new IllegalArgumentException("Tổng thời giờ làm việc trong ngày của bác sĩ " + doctor.getFullName() + " vượt quá 12 giờ.");
        }

        StaffSchedule created = new StaffSchedule();
        created.setStaff(doctor);
        created.setBranch(branch);
        created.setShift(shift);
        created.setWorkDate(workDate);
        created.setStatus(ScheduleStatus.SCHEDULED);
        created.setNote(cleanNote(note));
        return staffScheduleRepository.saveAndFlush(created);
    }

    private List<Appointment> findAppointmentsInShift(
            Long staffId, Long branchId, LocalDate workDate, WorkShift shift, List<AppointmentStatus> statuses) {
        List<Appointment> candidates = appointmentRepository.findAffectedAppointments(staffId, workDate, workDate, statuses);
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        LocalTime start = shift != null ? shift.getStartTime() : null;
        LocalTime end = shift != null ? shift.getEndTime() : null;
        List<Appointment> matched = new ArrayList<>();
        for (Appointment app : candidates) {
            if (app.getBranch() == null || !Objects.equals(branchId, app.getBranch().getBranchId())) {
                continue;
            }
            LocalTime t = app.getExpectedTime();
            if (t == null || start == null || end == null || (!t.isBefore(start) && t.isBefore(end))) {
                matched.add(app);
            }
        }
        return matched;
    }

    private boolean hasDoctorAppointmentTimeOverlap(Appointment candidate, List<Appointment> existingList) {
        if (candidate.getExpectedTime() == null) {
            return false;
        }
        LocalTime aStart = candidate.getExpectedTime();
        LocalTime aEnd = candidate.getExpectedEndTime() != null
                ? candidate.getExpectedEndTime()
                : aStart.plusMinutes(candidate.getDurationMinutes() != null ? candidate.getDurationMinutes() : 30);
        for (Appointment ex : existingList) {
            if (ex.getExpectedTime() == null || Objects.equals(ex.getAppointmentId(), candidate.getAppointmentId())) {
                continue;
            }
            LocalTime bStart = ex.getExpectedTime();
            LocalTime bEnd = ex.getExpectedEndTime() != null
                    ? ex.getExpectedEndTime()
                    : bStart.plusMinutes(ex.getDurationMinutes() != null ? ex.getDurationMinutes() : 30);
            if (intervalsOverlap(aStart, aEnd, bStart, bEnd)) {
                return true;
            }
        }
        return false;
    }

    public static boolean intervalsOverlap(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private static boolean isDoctorRole(String roleName) {
        if (roleName == null) {
            return false;
        }
        String r = roleName.trim().toUpperCase(Locale.ROOT);
        return r.contains("BAC_SI") || r.contains("DOCTOR") || r.contains("BÁC SĨ");
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String t = note.trim();
        return t.length() > 250 ? t.substring(0, 250) : t;
    }

    private void writeAuditLog(Long actorUserId, Branch branch, String action, String entityTable, Long entityId,
                               Map<String, Object> oldValues, Map<String, Object> newValues) {
        if (systemAuditLogRepository == null) {
            return;
        }
        try {
            User actor = actorUserId != null ? userRepository.findById(actorUserId).orElse(null) : null;
            ObjectMapper mapper = new ObjectMapper();
            SystemAuditLog audit = new SystemAuditLog();
            audit.setUser(actor);
            audit.setBranch(branch);
            audit.setAction(action);
            audit.setEntityTable(entityTable);
            audit.setEntityId(entityId);
            audit.setOldValuesJson(mapper.writeValueAsString(oldValues));
            audit.setNewValuesJson(mapper.writeValueAsString(newValues));
            audit.setIpAddress("127.0.0.1");
            systemAuditLogRepository.save(audit);
        } catch (Exception ignored) {
        }
    }
}
