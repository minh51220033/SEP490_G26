package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ResourceAssignmentAdminService {

    private final DoctorRoomAssignmentRepository roomAssignmentRepository;
    private final DoctorServicePermissionRepository servicePermissionRepository;
    private final ServiceResourceRequirementRepository requirementRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final RoomRepository roomRepository;
    private final ServiceRepository serviceRepository;
    private final ClinicEquipmentRepository equipmentRepository;

    @Autowired(required = false)
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupLegacyPendingReviewRecords() {
        if (jdbcTemplate == null) {
            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM doctor_room_assignments WHERE status = 'PENDING_REVIEW'");
            jdbcTemplate.update("DELETE FROM doctor_service_permissions WHERE status = 'PENDING_REVIEW'");
        } catch (Exception ignored) {
            // Bỏ qua nếu bảng chưa khởi tạo trong môi trường test độc lập
        }
    }

    @Transactional(readOnly = true)
    public List<DoctorRoomAssignment> getActiveRoomAssignments(Long branchId, Long staffId) {
        return roomAssignmentRepository
                .findByBranchBranchIdAndStaffStaffIdAndStatusOrderByIsPrimaryDescAssignmentIdAsc(
                        branchId, staffId, DoctorAssignmentStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public boolean isDoctorStaff(Long staffId) {
        if (staffId == null) return false;
        return staffProfileRepository.findById(staffId)
                .map(this::isDoctorStaff)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isDoctorStaff(StaffProfile staff) {
        if (staff == null) return false;
        if (staffBranchAssignmentRepository != null && staff.getStaffId() != null) {
            List<StaffBranchAssignment> active = staffBranchAssignmentRepository
                    .findByStaffStaffIdAndStatus(staff.getStaffId(), AssignmentStatus.ACTIVE);
            if (!active.isEmpty()) {
                return active.stream().anyMatch(a -> a.getRole() != null && isDoctorRoleName(a.getRole().getRoleName()));
            }
        }
        return staff.getUser() != null && staff.getUser().getRoles() != null
                && staff.getUser().getRoles().stream().anyMatch(r -> r != null && isDoctorRoleName(r.getRoleName()));
    }

    private static boolean isDoctorRoleName(String roleName) {
        return "BAC_SI".equalsIgnoreCase(roleName) || "DOCTOR".equalsIgnoreCase(roleName);
    }

    private void requireDoctorStaff(StaffProfile staff) {
        if (!isDoctorStaff(staff)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chức năng phân công phòng và dịch vụ chuyên môn chỉ áp dụng cho nhân sự có vai trò Bác sĩ.");
        }
    }

    @Transactional(readOnly = true)
    public List<DoctorRoomAssignment> getAllRoomAssignments(Long branchId, Long staffId) {
        StaffProfile staff = staffProfileRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân sự."));
        requireDoctorStaff(staff);
        return roomAssignmentRepository.findByBranchBranchIdAndStaffStaffIdOrderByAssignmentIdAsc(branchId, staffId);
    }

    @Transactional(readOnly = true)
    public List<DoctorServicePermission> getActiveServicePermissions(Long serviceId) {
        return servicePermissionRepository
                .findByServiceServiceIdAndStatusOrderByPermissionIdAsc(serviceId, DoctorServicePermissionStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<DoctorServicePermission> getAllServicePermissionsByStaff(Long staffId) {
        StaffProfile staff = staffProfileRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân sự."));
        requireDoctorStaff(staff);
        return servicePermissionRepository.findByStaffStaffIdOrderByPermissionIdAsc(staffId);
    }

    @Transactional
    public DoctorRoomAssignment assignDoctorRoom(
            Long branchId,
            Long staffId,
            Long roomId,
            boolean isPrimary,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            DoctorAssignmentStatus status
    ) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        StaffProfile staff = staffProfileRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân sự."));
        requireDoctorStaff(staff);
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng."));
        if (!Objects.equals(room.getBranch().getBranchId(), branchId)) {
            throw new IllegalArgumentException("Phòng không thuộc chi nhánh được phân công.");
        }
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new IllegalArgumentException("Phòng " + room.getRoomCode() + " hiện không ở trạng thái hoạt động (ACTIVE).");
        }
        if (staffBranchAssignmentRepository != null
                && !staffBranchAssignmentRepository.findByStaffStaffIdAndStatus(staffId, AssignmentStatus.ACTIVE).isEmpty()
                && !staffBranchAssignmentRepository.hasActiveAssignment(staffId, branchId, AssignmentStatus.ACTIVE)) {
            throw new IllegalArgumentException("Nhân sự không thuộc phạm vi phân công ACTIVE của chi nhánh này.");
        }
        if (roomAssignmentRepository.existsByBranchBranchIdAndStaffStaffIdAndRoomRoomIdAndStatusIn(
                branchId, staffId, roomId, List.of(DoctorAssignmentStatus.ACTIVE))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Nhân sự đã được phân công phòng " + room.getRoomCode() + ".");
        }

        DoctorRoomAssignment assignment = new DoctorRoomAssignment();
        assignment.setBranch(branch);
        assignment.setStaff(staff);
        assignment.setRoom(room);
        assignment.setIsPrimary(isPrimary);
        assignment.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : LocalDate.now());
        assignment.setEffectiveTo(effectiveTo);
        assignment.setStatus(status != null ? status : DoctorAssignmentStatus.ACTIVE);
        return roomAssignmentRepository.save(assignment);
    }

    @Transactional
    public DoctorRoomAssignment revokeRoomAssignment(Long assignmentId) {
        DoctorRoomAssignment assignment = roomAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công phòng."));
        requireDoctorStaff(assignment.getStaff());
        assignment.setStatus(DoctorAssignmentStatus.INACTIVE);
        return roomAssignmentRepository.save(assignment);
    }

    @Transactional
    public DoctorServicePermission grantDoctorServicePermission(
            Long staffId,
            Long serviceId,
            Long branchId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            DoctorServicePermissionStatus status,
            String note
    ) {
        StaffProfile staff = staffProfileRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân sự."));
        requireDoctorStaff(staff);
        com.cvdcms.entity.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ."));
        if (service.getStatus() != ServiceStatus.ACTIVE) {
            throw new IllegalArgumentException("Dịch vụ " + service.getServiceCode() + " đã ngừng hoạt động, không thể cấp quyền.");
        }
        if (servicePermissionRepository.existsByStaffStaffIdAndServiceServiceIdAndStatusIn(
                staffId, serviceId, List.of(DoctorServicePermissionStatus.ACTIVE))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Nhân sự đã có quyền thực hiện dịch vụ " + service.getServiceCode() + ".");
        }
        Branch branch = null;
        if (branchId != null) {
            branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        }

        DoctorServicePermission permission = new DoctorServicePermission();
        permission.setStaff(staff);
        permission.setService(service);
        permission.setBranch(branch);
        permission.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : LocalDate.now());
        permission.setEffectiveTo(effectiveTo);
        permission.setStatus(status != null ? status : DoctorServicePermissionStatus.ACTIVE);
        permission.setNote(note);
        return servicePermissionRepository.save(permission);
    }

    @Transactional
    public DoctorServicePermission revokeServicePermission(Long permissionId) {
        DoctorServicePermission permission = servicePermissionRepository.findById(permissionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân quyền dịch vụ."));
        requireDoctorStaff(permission.getStaff());
        permission.setStatus(DoctorServicePermissionStatus.INACTIVE);
        return servicePermissionRepository.save(permission);
    }

    @Transactional
    public List<DoctorRoomAssignment> syncDoctorRooms(Long branchId, Long staffId, Collection<Long> roomIds) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        StaffProfile staff = staffProfileRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân sự."));
        requireDoctorStaff(staff);
        if (staffBranchAssignmentRepository != null
                && !staffBranchAssignmentRepository.findByStaffStaffIdAndStatus(staffId, AssignmentStatus.ACTIVE).isEmpty()
                && !staffBranchAssignmentRepository.hasActiveAssignment(staffId, branchId, AssignmentStatus.ACTIVE)) {
            throw new IllegalArgumentException("Nhân sự không thuộc phạm vi phân công ACTIVE của chi nhánh này.");
        }

        Set<Long> targetIds = new LinkedHashSet<>();
        if (roomIds != null) {
            for (Long id : roomIds) {
                if (id != null) targetIds.add(id);
            }
        }

        List<DoctorRoomAssignment> existing = roomAssignmentRepository
                .findByBranchBranchIdAndStaffStaffIdOrderByAssignmentIdAsc(branchId, staffId);
        Map<Long, DoctorRoomAssignment> activeByRoom = new HashMap<>();
        Map<Long, DoctorRoomAssignment> inactiveByRoom = new HashMap<>();

        for (DoctorRoomAssignment a : existing) {
            Long rId = a.getRoom().getRoomId();
            if (a.getStatus() == DoctorAssignmentStatus.ACTIVE) {
                if (!targetIds.contains(rId)) {
                    a.setStatus(DoctorAssignmentStatus.INACTIVE);
                    roomAssignmentRepository.save(a);
                } else {
                    activeByRoom.putIfAbsent(rId, a);
                }
            } else {
                inactiveByRoom.putIfAbsent(rId, a);
            }
        }

        LocalDate today = LocalDate.now();
        for (Long rId : targetIds) {
            if (activeByRoom.containsKey(rId)) {
                continue;
            }
            Room room = roomRepository.findById(rId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng khám #" + rId + "."));
            if (!Objects.equals(room.getBranch().getBranchId(), branchId)) {
                throw new IllegalArgumentException("Phòng " + room.getRoomCode() + " không thuộc chi nhánh được phân công.");
            }
            if (room.getStatus() != RoomStatus.ACTIVE) {
                throw new IllegalArgumentException("Phòng " + room.getRoomCode() + " hiện không ở trạng thái hoạt động (ACTIVE).");
            }
            DoctorRoomAssignment reusable = inactiveByRoom.get(rId);
            if (reusable != null) {
                reusable.setStatus(DoctorAssignmentStatus.ACTIVE);
                reusable.setEffectiveFrom(today);
                reusable.setEffectiveTo(null);
                roomAssignmentRepository.save(reusable);
            } else {
                DoctorRoomAssignment created = new DoctorRoomAssignment();
                created.setBranch(branch);
                created.setStaff(staff);
                created.setRoom(room);
                created.setIsPrimary(false);
                created.setEffectiveFrom(today);
                created.setStatus(DoctorAssignmentStatus.ACTIVE);
                roomAssignmentRepository.save(created);
            }
        }

        return getActiveRoomAssignments(branchId, staffId);
    }

    @Transactional
    public List<DoctorServicePermission> syncDoctorServices(Long staffId, Long branchId, Collection<Long> serviceIds) {
        StaffProfile staff = staffProfileRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân sự."));
        requireDoctorStaff(staff);
        Branch branch = null;
        if (branchId != null) {
            branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        }

        Set<Long> targetIds = new LinkedHashSet<>();
        if (serviceIds != null) {
            for (Long id : serviceIds) {
                if (id != null) targetIds.add(id);
            }
        }

        List<DoctorServicePermission> existing = servicePermissionRepository
                .findByStaffStaffIdOrderByPermissionIdAsc(staffId);
        Map<Long, DoctorServicePermission> activeByService = new HashMap<>();
        Map<Long, DoctorServicePermission> inactiveByService = new HashMap<>();

        for (DoctorServicePermission p : existing) {
            Long sId = p.getService().getServiceId();
            if (p.getStatus() == DoctorServicePermissionStatus.ACTIVE) {
                if (!targetIds.contains(sId)) {
                    p.setStatus(DoctorServicePermissionStatus.INACTIVE);
                    servicePermissionRepository.save(p);
                } else {
                    activeByService.putIfAbsent(sId, p);
                }
            } else {
                inactiveByService.putIfAbsent(sId, p);
            }
        }

        LocalDate today = LocalDate.now();
        for (Long sId : targetIds) {
            if (activeByService.containsKey(sId)) {
                continue;
            }
            com.cvdcms.entity.Service service = serviceRepository.findById(sId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ #" + sId + "."));
            if (service.getStatus() != ServiceStatus.ACTIVE) {
                throw new IllegalArgumentException("Dịch vụ " + service.getServiceCode() + " đã ngừng hoạt động, không thể cấp quyền.");
            }
            DoctorServicePermission reusable = inactiveByService.get(sId);
            if (reusable != null) {
                reusable.setStatus(DoctorServicePermissionStatus.ACTIVE);
                reusable.setEffectiveFrom(today);
                reusable.setEffectiveTo(null);
                servicePermissionRepository.save(reusable);
            } else {
                DoctorServicePermission created = new DoctorServicePermission();
                created.setStaff(staff);
                created.setService(service);
                created.setBranch(branch);
                created.setEffectiveFrom(today);
                created.setStatus(DoctorServicePermissionStatus.ACTIVE);
                servicePermissionRepository.save(created);
            }
        }

        return servicePermissionRepository.findByStaffStaffIdAndStatusOrderByPermissionIdAsc(
                staffId, DoctorServicePermissionStatus.ACTIVE);
    }

    @Transactional
    public ServiceResourceRequirement addServiceRequirement(
            Long serviceId,
            ResourceRequirementKind kind,
            RoomType roomType,
            Long roomId,
            EquipmentType equipmentType,
            Long equipmentId,
            ResourceUsagePhase usagePhase,
            boolean isRequired
    ) {
        com.cvdcms.entity.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dịch vụ."));
        if (kind == null) {
            throw new IllegalArgumentException("Vui lòng chọn loại yêu cầu tài nguyên.");
        }

        ServiceResourceRequirement req = new ServiceResourceRequirement();
        req.setService(service);
        req.setResourceKind(kind);
        req.setRoomType(roomType);
        req.setEquipmentType(equipmentType);
        req.setUsagePhase(usagePhase != null ? usagePhase : ResourceUsagePhase.PROCEDURE);
        req.setIsRequired(isRequired);

        if (roomId != null) {
            req.setRoom(roomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng yêu cầu.")));
        }
        if (equipmentId != null) {
            req.setEquipment(equipmentRepository.findById(equipmentId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thiết bị yêu cầu.")));
        }
        return requirementRepository.save(req);
    }

    @Transactional(readOnly = true)
    public List<ServiceResourceRequirement> getServiceRequirements(Long serviceId) {
        return requirementRepository.findByServiceServiceIdOrderByRequirementIdAsc(serviceId);
    }
}
