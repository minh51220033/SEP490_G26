package com.cvdcms.service;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

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
                branchId, staffId, roomId, List.of(DoctorAssignmentStatus.PENDING_REVIEW, DoctorAssignmentStatus.ACTIVE))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Nhân sự đã được phân công phòng " + room.getRoomCode() + " (đang chờ duyệt hoặc đang hoạt động).");
        }

        DoctorRoomAssignment assignment = new DoctorRoomAssignment();
        assignment.setBranch(branch);
        assignment.setStaff(staff);
        assignment.setRoom(room);
        assignment.setIsPrimary(isPrimary);
        assignment.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : LocalDate.now());
        assignment.setEffectiveTo(effectiveTo);
        assignment.setStatus(status != null ? status : DoctorAssignmentStatus.PENDING_REVIEW);
        return roomAssignmentRepository.save(assignment);
    }

    @Transactional
    public DoctorRoomAssignment approveRoomAssignment(Long assignmentId) {
        DoctorRoomAssignment assignment = roomAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân công phòng."));
        requireDoctorStaff(assignment.getStaff());
        assignment.setStatus(DoctorAssignmentStatus.ACTIVE);
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
                staffId, serviceId, List.of(DoctorServicePermissionStatus.PENDING_REVIEW, DoctorServicePermissionStatus.ACTIVE))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Nhân sự đã có quyền thực hiện dịch vụ " + service.getServiceCode() + " (đang chờ duyệt hoặc đang hoạt động).");
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
        permission.setStatus(status != null ? status : DoctorServicePermissionStatus.PENDING_REVIEW);
        permission.setNote(note);
        return servicePermissionRepository.save(permission);
    }

    @Transactional
    public DoctorServicePermission approveServicePermission(Long permissionId) {
        DoctorServicePermission permission = servicePermissionRepository.findById(permissionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phân quyền dịch vụ."));
        requireDoctorStaff(permission.getStaff());
        permission.setStatus(DoctorServicePermissionStatus.ACTIVE);
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
