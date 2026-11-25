package com.cvdcms.controller;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.DentalChairRepository;
import com.cvdcms.repository.RoomRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.*;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * API quản trị Master Data nguồn lực Phase 1 (CSRF-protected dưới /admin/api/resources/**).
 */
@RestController
@RequestMapping("/admin/api/resources")
@RequiredArgsConstructor
public class AdminResourceMasterRestController {

    private final BranchTransitionRuleService transitionRuleService;
    private final ClinicEquipmentService equipmentService;
    private final EquipmentTransferService transferService;
    private final ResourceAssignmentAdminService assignmentAdminService;
    private final BranchService branchService;
    private final UserRepository userRepository;

    @Autowired(required = false)
    private RoomRepository roomRepository;

    @Autowired(required = false)
    private ServiceRepository serviceRepository;

    @Autowired(required = false)
    private DentalChairRepository dentalChairRepository;

    @Autowired(required = false)
    private StaffProfileRepository staffProfileRepository;

    // -------------------------------------------------------------------------
    // 1. Quy tắc di chuyển liên phòng / liên tầng (branch_room_transition_rules)
    // -------------------------------------------------------------------------

    @GetMapping("/branches/{branchId}/transition-rules")
    public List<Map<String, Object>> listTransitionRules(
            @PathVariable Long branchId, HttpSession session, Authentication auth) {
        requireBranchAccess(branchId, session, auth);
        return transitionRuleService.getActiveRules(branchId).stream()
                .map(r -> Map.<String, Object>of(
                        "ruleId", r.getRuleId(),
                        "ruleScope", r.getRuleScope().name(),
                        "fromRoomId", r.getFromRoom() != null ? r.getFromRoom().getRoomId() : "",
                        "toRoomId", r.getToRoom() != null ? r.getToRoom().getRoomId() : "",
                        "fromFloorLabel", r.getFromFloorLabel() != null ? r.getFromFloorLabel() : "",
                        "toFloorLabel", r.getToFloorLabel() != null ? r.getToFloorLabel() : "",
                        "travelBufferMinutes", r.getTravelBufferMinutes()
                )).toList();
    }

    @PostMapping("/branches/{branchId}/transition-rules")
    public ResponseEntity<Map<String, Object>> createTransitionRule(
            @PathVariable Long branchId,
            @RequestParam TransitionRuleScope ruleScope,
            @RequestParam(required = false) Long fromRoomId,
            @RequestParam(required = false) Long toRoomId,
            @RequestParam(required = false) String fromFloorLabel,
            @RequestParam(required = false) String toFloorLabel,
            @RequestParam Integer travelBufferMinutes,
            HttpSession session,
            Authentication auth) {
        requireBranchAccess(branchId, session, auth);
        BranchRoomTransitionRule created = transitionRuleService.createRule(
                branchId, ruleScope, fromRoomId, toRoomId, fromFloorLabel, toFloorLabel, travelBufferMinutes);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "ruleId", created.getRuleId(),
                "ruleScope", created.getRuleScope().name(),
                "travelBufferMinutes", created.getTravelBufferMinutes()
        ));
    }

    @DeleteMapping("/branches/{branchId}/transition-rules/{ruleId}")
    public ResponseEntity<Void> deactivateTransitionRule(
            @PathVariable Long branchId, @PathVariable Long ruleId, HttpSession session, Authentication auth) {
        requireBranchAccess(branchId, session, auth);
        transitionRuleService.deactivateRule(branchId, ruleId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // 2. Thiết bị theo chi nhánh & Điều chuyển (clinic_equipments, equipment_transfer_logs)
    // -------------------------------------------------------------------------

    @GetMapping("/branches/{branchId}/equipments")
    public List<Map<String, Object>> listEquipments(
            @PathVariable Long branchId, HttpSession session, Authentication auth) {
        requireBranchAccess(branchId, session, auth);
        return equipmentService.getEquipmentsByBranch(branchId).stream()
                .map(e -> Map.<String, Object>of(
                        "equipmentId", e.getEquipmentId(),
                        "equipmentCode", e.getEquipmentCode(),
                        "equipmentName", e.getEquipmentName(),
                        "equipmentType", e.getEquipmentType().name(),
                        "allocationMode", e.getAllocationMode().name(),
                        "fixedRoomId", e.getFixedRoom() != null ? e.getFixedRoom().getRoomId() : "",
                        "serial", e.getDeviceSerialNumber() != null ? e.getDeviceSerialNumber() : "",
                        "status", e.getStatus().name()
                )).toList();
    }

    @PostMapping("/branches/{branchId}/equipments")
    public ResponseEntity<Map<String, Object>> createEquipment(
            @PathVariable Long branchId,
            @RequestParam String equipmentCode,
            @RequestParam String equipmentName,
            @RequestParam EquipmentType equipmentType,
            @RequestParam EquipmentAllocationMode allocationMode,
            @RequestParam(required = false) Long fixedRoomId,
            @RequestParam(required = false) String deviceSerialNumber,
            @RequestParam(required = false) EquipmentStatus status,
            HttpSession session,
            Authentication auth) {
        requireBranchAccess(branchId, session, auth);
        ClinicEquipment eq = equipmentService.createEquipment(
                branchId, equipmentCode, equipmentName, equipmentType, allocationMode, fixedRoomId, deviceSerialNumber, status);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "equipmentId", eq.getEquipmentId(),
                "equipmentCode", eq.getEquipmentCode(),
                "status", eq.getStatus().name()
        ));
    }

    @PostMapping("/branches/{branchId}/equipments/{equipmentId}/verify")
    public Map<String, Object> verifyEquipment(
            @PathVariable Long branchId,
            @PathVariable Long equipmentId,
            @RequestParam(required = false) String deviceSerialNumber,
            HttpSession session,
            Authentication auth) {
        requireBranchAccess(branchId, session, auth);
        ClinicEquipment eq = equipmentService.verifyAndActivate(branchId, equipmentId, deviceSerialNumber);
        return Map.of("equipmentId", eq.getEquipmentId(), "status", eq.getStatus().name());
    }

    @PostMapping("/equipments/{equipmentId}/transfer")
    public Map<String, Object> transferEquipment(
            @PathVariable Long equipmentId,
            @RequestParam Long targetBranchId,
            @RequestParam(required = false) Long targetRoomId,
            @RequestParam String reason,
            HttpSession session,
            Authentication auth) {
        User actor = requireAdminOrBranchUser(session, auth);
        requireBranchAccess(targetBranchId, session, auth);
        EquipmentTransferLog log = transferService.transferEquipment(
                equipmentId, targetBranchId, targetRoomId, reason, actor.getUserId());
        return Map.of(
                "transferId", log.getTransferId(),
                "transferType", log.getTransferType().name(),
                "toBranchId", log.getToBranchId()
        );
    }

    // -------------------------------------------------------------------------
    // 3. Phân công Bác sĩ - Phòng & Bác sĩ - Dịch vụ & Yêu cầu Dịch vụ
    // -------------------------------------------------------------------------

    @GetMapping("/branches/{branchId}/rooms")
    public List<Map<String, Object>> listBranchRooms(
            @PathVariable Long branchId, HttpSession session, Authentication auth) {
        requireBranchAccess(branchId, session, auth);
        if (roomRepository == null) {
            return List.of();
        }
        Map<Long, Long> chairCounts = new java.util.HashMap<>();
        if (dentalChairRepository != null) {
            for (DentalChair ch : dentalChairRepository.findByBranchBranchIdOrderByChairCodeAsc(branchId)) {
                if (ch.getRoom() != null && ch.getRoom().getRoomId() != null
                        && ch.getStatus() != ChairStatus.MAINTENANCE && ch.getStatus() != ChairStatus.INACTIVE) {
                    chairCounts.merge(ch.getRoom().getRoomId(), 1L, Long::sum);
                }
            }
        }
        return roomRepository.findByBranchBranchIdAndStatusOrderByRoomCodeAsc(branchId, RoomStatus.ACTIVE).stream()
                .map(r -> Map.<String, Object>of(
                        "roomId", r.getRoomId(),
                        "roomCode", r.getRoomCode(),
                        "roomName", r.getRoomName(),
                        "roomType", r.getRoomType() != null ? r.getRoomType().name() : "",
                        "status", r.getStatus().name(),
                        "chairCount", chairCounts.getOrDefault(r.getRoomId(), 0L)
                )).toList();
    }

    @GetMapping("/services")
    public List<Map<String, Object>> listActiveServices(HttpSession session, Authentication auth) {
        requireAdminOrBranchUser(session, auth);
        if (serviceRepository == null) {
            return List.of();
        }
        return serviceRepository.findByStatusAndCategory_IsActiveTrueOrderByServiceNameAsc(ServiceStatus.ACTIVE).stream()
                .map(s -> Map.<String, Object>of(
                        "serviceId", s.getServiceId(),
                        "serviceCode", s.getServiceCode(),
                        "serviceName", s.getServiceName(),
                        "durationMinutes", AppointmentSlotService.duration(s),
                        "status", s.getStatus().name()
                )).toList();
    }

    @GetMapping("/branches/{branchId}/doctors/{staffId}/rooms")
    public List<Map<String, Object>> listDoctorRooms(
            @PathVariable Long branchId, @PathVariable Long staffId, HttpSession session, Authentication auth) {
        requireAdminOrSelfDoctor(staffId, session, auth);
        requireBranchAccess(branchId, session, auth);
        return assignmentAdminService.getAllRoomAssignments(branchId, staffId).stream()
                .map(a -> Map.<String, Object>of(
                        "assignmentId", a.getAssignmentId(),
                        "roomId", a.getRoom().getRoomId(),
                        "roomCode", a.getRoom().getRoomCode(),
                        "roomName", a.getRoom().getRoomName(),
                        "isPrimary", Boolean.TRUE.equals(a.getIsPrimary()),
                        "status", a.getStatus().name()
                )).toList();
    }

    @PostMapping("/branches/{branchId}/doctors/{staffId}/rooms")
    public ResponseEntity<Map<String, Object>> assignDoctorRoom(
            @PathVariable Long branchId,
            @PathVariable Long staffId,
            @RequestParam Long roomId,
            @RequestParam(defaultValue = "false") boolean isPrimary,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveTo,
            @RequestParam(required = false) DoctorAssignmentStatus status,
            HttpSession session,
            Authentication auth) {
        requireAdminRole(session, auth);
        requireBranchAccess(branchId, session, auth);
        DoctorRoomAssignment saved = assignmentAdminService.assignDoctorRoom(
                branchId, staffId, roomId, isPrimary, effectiveFrom, effectiveTo, status);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "assignmentId", saved.getAssignmentId(),
                "status", saved.getStatus().name()
        ));
    }

    @PostMapping("/doctors/room-assignments/{assignmentId}/approve")
    public Map<String, Object> approveRoomAssignment(
            @PathVariable Long assignmentId, HttpSession session, Authentication auth) {
        requireAdminRole(session, auth);
        DoctorRoomAssignment approved = assignmentAdminService.approveRoomAssignment(assignmentId);
        return Map.of("assignmentId", approved.getAssignmentId(), "status", approved.getStatus().name());
    }

    @DeleteMapping("/doctors/room-assignments/{assignmentId}")
    public Map<String, Object> revokeRoomAssignment(
            @PathVariable Long assignmentId, HttpSession session, Authentication auth) {
        requireAdminRole(session, auth);
        DoctorRoomAssignment revoked = assignmentAdminService.revokeRoomAssignment(assignmentId);
        return Map.of("assignmentId", revoked.getAssignmentId(), "status", revoked.getStatus().name());
    }

    @GetMapping("/doctors/{staffId}/services")
    public List<Map<String, Object>> listDoctorServices(
            @PathVariable Long staffId, HttpSession session, Authentication auth) {
        requireAdminOrSelfDoctor(staffId, session, auth);
        return assignmentAdminService.getAllServicePermissionsByStaff(staffId).stream()
                .map(p -> Map.<String, Object>of(
                        "permissionId", p.getPermissionId(),
                        "serviceId", p.getService().getServiceId(),
                        "serviceCode", p.getService().getServiceCode(),
                        "serviceName", p.getService().getServiceName(),
                        "branchId", p.getBranch() != null ? p.getBranch().getBranchId() : "",
                        "status", p.getStatus().name()
                )).toList();
    }

    @PostMapping("/doctors/{staffId}/services")
    public ResponseEntity<Map<String, Object>> grantDoctorServicePermission(
            @PathVariable Long staffId,
            @RequestParam Long serviceId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveTo,
            @RequestParam(required = false) DoctorServicePermissionStatus status,
            @RequestParam(required = false) String note,
            HttpSession session,
            Authentication auth) {
        requireAdminRole(session, auth);
        if (branchId != null) {
            requireBranchAccess(branchId, session, auth);
        }
        DoctorServicePermission saved = assignmentAdminService.grantDoctorServicePermission(
                staffId, serviceId, branchId, effectiveFrom, effectiveTo, status, note);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "permissionId", saved.getPermissionId(),
                "status", saved.getStatus().name()
        ));
    }

    @PostMapping("/doctors/service-permissions/{permissionId}/approve")
    public Map<String, Object> approveServicePermission(
            @PathVariable Long permissionId, HttpSession session, Authentication auth) {
        requireAdminRole(session, auth);
        DoctorServicePermission approved = assignmentAdminService.approveServicePermission(permissionId);
        return Map.of("permissionId", approved.getPermissionId(), "status", approved.getStatus().name());
    }

    @DeleteMapping("/doctors/service-permissions/{permissionId}")
    public Map<String, Object> revokeServicePermission(
            @PathVariable Long permissionId, HttpSession session, Authentication auth) {
        requireAdminRole(session, auth);
        DoctorServicePermission revoked = assignmentAdminService.revokeServicePermission(permissionId);
        return Map.of("permissionId", revoked.getPermissionId(), "status", revoked.getStatus().name());
    }

    @GetMapping("/services/{serviceId}/requirements")
    public List<Map<String, Object>> listServiceRequirements(
            @PathVariable Long serviceId, HttpSession session, Authentication auth) {
        requireAdminOrBranchUser(session, auth);
        return assignmentAdminService.getServiceRequirements(serviceId).stream()
                .map(r -> Map.<String, Object>of(
                        "requirementId", r.getRequirementId(),
                        "resourceKind", r.getResourceKind().name(),
                        "roomType", r.getRoomType() != null ? r.getRoomType().name() : "",
                        "roomName", r.getRoom() != null ? r.getRoom().getRoomName() : "",
                        "equipmentType", r.getEquipmentType() != null ? r.getEquipmentType().name() : "",
                        "equipmentName", r.getEquipment() != null ? r.getEquipment().getEquipmentName() : "",
                        "usagePhase", r.getUsagePhase().name(),
                        "isRequired", Boolean.TRUE.equals(r.getIsRequired())
                )).toList();
    }

    @PostMapping("/services/{serviceId}/requirements")
    public ResponseEntity<Map<String, Object>> addServiceRequirement(
            @PathVariable Long serviceId,
            @RequestParam ResourceRequirementKind kind,
            @RequestParam(required = false) RoomType roomType,
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) EquipmentType equipmentType,
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) ResourceUsagePhase usagePhase,
            @RequestParam(defaultValue = "true") boolean isRequired,
            HttpSession session,
            Authentication auth) {
        requireAdminRole(session, auth);
        ServiceResourceRequirement req = assignmentAdminService.addServiceRequirement(
                serviceId, kind, roomType, roomId, equipmentType, equipmentId, usagePhase, isRequired);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "requirementId", req.getRequirementId(),
                "resourceKind", req.getResourceKind().name()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Helpers RBAC & Branch Scope
    // -------------------------------------------------------------------------

    private User requireAdminOrBranchUser(HttpSession session, Authentication auth) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User sessionUser) {
            return sessionUser;
        }
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            return userRepository.findByUsername(auth.getName())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập."));
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập.");
    }

    private void requireAdminRole(HttpSession session, Authentication auth) {
        User user = requireAdminOrBranchUser(session, auth);
        if (!userRepository.hasActiveAdminRole(user.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Quản trị viên hệ thống (ADMIN) mới có quyền thực hiện.");
        }
    }

    private void requireAdminOrSelfDoctor(Long staffId, HttpSession session, Authentication auth) {
        User user = requireAdminOrBranchUser(session, auth);
        if (userRepository.hasActiveAdminRole(user.getUserId())) {
            return;
        }
        boolean isSelf = staffProfileRepository != null && staffProfileRepository.findById(staffId)
                .map(s -> s.getUser() != null && java.util.Objects.equals(s.getUser().getUserId(), user.getUserId()))
                .orElse(false);
        if (isSelf && assignmentAdminService.isDoctorStaff(staffId)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Chỉ Quản trị viên hoặc chính Bác sĩ sở hữu hồ sơ mới có quyền xem phân công này.");
    }

    private void requireBranchAccess(Long branchId, HttpSession session, Authentication auth) {
        User user = requireAdminOrBranchUser(session, auth);
        if (!branchService.canUserAccessBranch(user.getUserId(), branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập chi nhánh #" + branchId);
        }
    }
}
