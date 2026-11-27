package com.cvdcms.service;

import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.DoctorRoomAssignment;
import com.cvdcms.entity.DoctorServicePermission;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.ServiceResourceRequirement;
import com.cvdcms.entity.enums.ChairStatus;
import com.cvdcms.entity.enums.DoctorAssignmentStatus;
import com.cvdcms.entity.enums.DoctorServicePermissionStatus;
import com.cvdcms.entity.enums.RoomStatus;
import com.cvdcms.entity.enums.RoomType;
import com.cvdcms.entity.enums.ServiceType;
import com.cvdcms.repository.DoctorRoomAssignmentRepository;
import com.cvdcms.repository.DoctorServicePermissionRepository;
import com.cvdcms.repository.RoomRepository;
import com.cvdcms.repository.ServiceRepository;
import com.cvdcms.repository.ServiceResourceRequirementRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Kiểm tra năng lực chuyên môn của bác sĩ (DoctorServicePermission), phân công phòng (DoctorRoomAssignment)
 * và yêu cầu phòng/thiết bị của dịch vụ (ServiceResourceRequirement) tại thời điểm điều phối & thực hiện lâm sàng.
 */
@org.springframework.stereotype.Service
public class DoctorClinicalCapabilityService {

    @Autowired(required = false)
    private DoctorServicePermissionRepository doctorServicePermissionRepo;

    @Autowired(required = false)
    private DoctorRoomAssignmentRepository doctorRoomAssignmentRepo;

    @Autowired(required = false)
    private ServiceResourceRequirementRepository serviceResourceRequirementRepo;

    @Autowired(required = false)
    private RoomRepository roomRepo;

    @Autowired(required = false)
    private ServiceRepository serviceRepo;

    public record CapabilityCheckResult(
            boolean allowed,
            boolean serviceAllowed,
            boolean roomAllowed,
            String reasonCode,
            String message
    ) {
        public static CapabilityCheckResult ok() {
            return new CapabilityCheckResult(true, true, true, null, null);
        }
    }

    public record EligibleDoctorDTO(
            Long staffId,
            String staffCode,
            String fullName,
            String specialty,
            String positionTitle,
            boolean eligibleForService,
            boolean busy,
            String busyPatientName,
            int waitingCount,
            List<Long> assignedRoomIds,
            List<String> assignedRoomNames,
            boolean suggestedReturn,
            String reason
    ) {
    }

    public record RoomOccupancyDTO(
            Long roomId,
            String roomCode,
            String roomName,
            String roomType,
            String floor,
            boolean occupied,
            String occupiedByPatient,
            int freeChairCount,
            int totalChairCount,
            boolean assignedToSelectedDoctor,
            boolean matchesServiceRequirement
    ) {
    }

    public record HandoverRecordDTO(
            Long fromDoctorId,
            String fromDoctorName,
            Long toDoctorId,
            String toDoctorName,
            Long returnToDoctorId,
            String serviceName,
            String timestamp,
            String note
    ) {
    }

    public record HandoverOptionsDTO(
            Long visitId,
            String visitCode,
            Long currentDoctorId,
            String currentDoctorName,
            Long currentRoomId,
            String currentRoomName,
            Long serviceId,
            String serviceName,
            Long suggestedReturnDoctorId,
            String suggestedReturnDoctorName,
            List<EligibleDoctorDTO> doctors,
            List<RoomOccupancyDTO> rooms,
            List<HandoverRecordDTO> handoverHistory
    ) {
    }

    public record HandoverResultDTO(
            boolean success,
            Long visitId,
            Long targetDoctorId,
            String targetDoctorName,
            Long targetRoomId,
            String targetRoomName,
            boolean targetCalledImmediately,
            String nextOutgoingDisplayNumber,
            String message
    ) {
    }

    /**
     * Kiểm tra quyền dịch vụ chuyên môn của bác sĩ.
     * Quy tắc tương thích ngược: nếu bác sĩ chưa từng được cấu hình dòng phân quyền nào trong DB thì vẫn cho phép.
     */
    public CapabilityCheckResult checkDoctorServicePermission(Long doctorId, Long serviceId) {
        return checkDoctorServicePermission(null, doctorId, serviceId);
    }

    public CapabilityCheckResult checkDoctorServicePermission(Long branchId, Long doctorId, Long serviceId) {
        if (doctorId == null || serviceId == null || doctorServicePermissionRepo == null) {
            return CapabilityCheckResult.ok();
        }
        List<DoctorServicePermission> allPerms = doctorServicePermissionRepo
                .findByStaffStaffIdOrderByPermissionIdAsc(doctorId);
        if (allPerms == null || allPerms.isEmpty()) {
            return CapabilityCheckResult.ok();
        }
        LocalDate today = LocalDate.now();
        boolean permitted = allPerms.stream()
                .anyMatch(p -> p.getStatus() == DoctorServicePermissionStatus.ACTIVE
                        && p.getService() != null
                        && Objects.equals(serviceId, p.getService().getServiceId())
                        && (branchId == null || p.getBranch() == null || Objects.equals(branchId, p.getBranch().getBranchId()))
                        && isWithinEffectiveDates(p.getEffectiveFrom(), p.getEffectiveTo(), today));
        if (permitted) {
            return CapabilityCheckResult.ok();
        }
        return new CapabilityCheckResult(
                false,
                false,
                true,
                "SERVICE_PERMISSION_DENIED",
                "Bác sĩ hiện tại chưa được phân quyền chuyên môn cho dịch vụ này (hoặc quyền đã hết hiệu lực). Vui lòng chuyển BS chuyên môn."
        );
    }

    private boolean isWithinEffectiveDates(LocalDate from, LocalDate to, LocalDate today) {
        if (from != null && from.isAfter(today)) {
            return false;
        }
        if (to != null && to.isBefore(today)) {
            return false;
        }
        return true;
    }

    /**
     * Kiểm tra đồng thời quyền dịch vụ, phân công phòng của bác sĩ và yêu cầu phòng/thiết bị của dịch vụ.
     */
    public CapabilityCheckResult checkDoctorAndRoomCapability(Long branchId, Long doctorId, Long visitRoomId, Long serviceId) {
        CapabilityCheckResult svcCheck = checkDoctorServicePermission(branchId, doctorId, serviceId);
        if (!svcCheck.allowed()) {
            return svcCheck;
        }

        LocalDate today = LocalDate.now();

        // 1. Kiểm tra bác sĩ có được phân công tại phòng này không (nếu bác sĩ đã có cấu hình phòng ở chi nhánh)
        if (visitRoomId != null && branchId != null && doctorId != null && doctorRoomAssignmentRepo != null) {
            List<DoctorRoomAssignment> allRoomAssignments = doctorRoomAssignmentRepo
                    .findByBranchBranchIdAndStaffStaffIdOrderByAssignmentIdAsc(branchId, doctorId);
            if (allRoomAssignments != null && !allRoomAssignments.isEmpty()) {
                boolean roomAssigned = allRoomAssignments.stream()
                        .anyMatch(a -> a.getStatus() == DoctorAssignmentStatus.ACTIVE
                                && a.getRoom() != null
                                && Objects.equals(visitRoomId, a.getRoom().getRoomId())
                                && isWithinEffectiveDates(a.getEffectiveFrom(), a.getEffectiveTo(), today));
                if (!roomAssigned) {
                    return new CapabilityCheckResult(
                            false,
                            true,
                            false,
                            "DOCTOR_ROOM_DENIED",
                            "Bác sĩ chưa được phân công phụ trách phòng khám hiện tại. Vui lòng chuyển sang phòng/bác sĩ phù hợp."
                    );
                }
            }
        }

        // 2. Kiểm tra phòng hiện tại có đáp ứng yêu cầu phòng chuyên dụng của dịch vụ không
        if (serviceId != null && serviceResourceRequirementRepo != null && roomRepo != null) {
            List<ServiceResourceRequirement> reqs = serviceResourceRequirementRepo
                    .findByServiceServiceIdOrderByRequirementIdAsc(serviceId);
            if (reqs != null && !reqs.isEmpty()) {
                List<ServiceResourceRequirement> roomReqs = reqs.stream()
                        .filter(r -> Boolean.TRUE.equals(r.getIsRequired())
                                && (r.getRoom() != null || r.getRoomType() != null))
                        .toList();
                if (!roomReqs.isEmpty()) {
                    Room currentRoom = visitRoomId != null ? roomRepo.findById(visitRoomId).orElse(null) : null;
                    if (currentRoom == null) {
                        return new CapabilityCheckResult(
                                false,
                                true,
                                false,
                                "ROOM_REQUIREMENT_MISMATCH",
                                "Dịch vụ này yêu cầu phòng/thiết bị chuyên dụng, nhưng ca khám chưa được phân phòng phù hợp."
                        );
                    }
                    boolean matchesAnyRoomReq = roomReqs.stream().anyMatch(r -> matchesRoomRequirement(currentRoom, r));
                    if (!matchesAnyRoomReq) {
                        return new CapabilityCheckResult(
                                false,
                                true,
                                false,
                                "ROOM_REQUIREMENT_MISMATCH",
                                "Phòng khám hiện tại (" + currentRoom.getRoomName()
                                        + ") chưa đáp ứng yêu cầu phòng/thiết bị cho dịch vụ này. Vui lòng chuyển BS / phòng chuyên môn."
                        );
                    }
                }
            }
        }

        return CapabilityCheckResult.ok();
    }

    /**
     * Trả về null nếu bác sĩ và dịch vụ đều không có giới hạn phòng nào;
     * trả về Set (có thể rỗng nếu giao nhau bằng rỗng) khi có ràng buộc phòng cụ thể.
     */
    public Set<Long> resolveRestrictedRoomIds(Long branchId, Long doctorId, Long serviceId) {
        if (branchId == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        Set<Long> doctorRooms = null;
        if (doctorId != null && doctorRoomAssignmentRepo != null) {
            List<DoctorRoomAssignment> allAssigns = doctorRoomAssignmentRepo
                    .findByBranchBranchIdAndStaffStaffIdOrderByAssignmentIdAsc(branchId, doctorId);
            if (allAssigns != null && !allAssigns.isEmpty()) {
                doctorRooms = new LinkedHashSet<>();
                for (DoctorRoomAssignment a : allAssigns) {
                    if (a.getStatus() == DoctorAssignmentStatus.ACTIVE
                            && a.getRoom() != null
                            && a.getRoom().getRoomId() != null
                            && isWithinEffectiveDates(a.getEffectiveFrom(), a.getEffectiveTo(), today)) {
                        doctorRooms.add(a.getRoom().getRoomId());
                    }
                }
            }
        }

        Set<Long> serviceRooms = null;
        if (serviceId != null && serviceResourceRequirementRepo != null && roomRepo != null) {
            List<ServiceResourceRequirement> roomReqs = serviceResourceRequirementRepo
                    .findByServiceServiceIdOrderByRequirementIdAsc(serviceId).stream()
                    .filter(r -> Boolean.TRUE.equals(r.getIsRequired()) && (r.getRoom() != null || r.getRoomType() != null))
                    .toList();
            if (!roomReqs.isEmpty()) {
                serviceRooms = new LinkedHashSet<>();
                for (Room r : roomRepo.findByBranchBranchIdAndStatusOrderByRoomCodeAsc(branchId, RoomStatus.ACTIVE)) {
                    if (roomReqs.stream().anyMatch(req -> matchesRoomRequirement(r, req))) {
                        serviceRooms.add(r.getRoomId());
                    }
                }
            }
        }

        if (doctorRooms == null && serviceRooms == null) {
            return null;
        }
        if (doctorRooms != null && serviceRooms != null) {
            Set<Long> intersection = new LinkedHashSet<>(doctorRooms);
            intersection.retainAll(serviceRooms);
            return intersection;
        }
        return doctorRooms != null ? doctorRooms : serviceRooms;
    }

    public Set<Long> resolveAllowedRoomIdsForDoctor(Long branchId, Long doctorId, Long serviceId) {
        Set<Long> restricted = resolveRestrictedRoomIds(branchId, doctorId, serviceId);
        return restricted != null ? restricted : Collections.emptySet();
    }

    public boolean doesRoomMatchServiceRequirement(Room room, Long serviceId) {
        if (room == null || serviceId == null || serviceResourceRequirementRepo == null) {
            return true;
        }
        List<ServiceResourceRequirement> roomReqs = serviceResourceRequirementRepo
                .findByServiceServiceIdOrderByRequirementIdAsc(serviceId).stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsRequired()) && (r.getRoom() != null || r.getRoomType() != null))
                .toList();
        if (roomReqs.isEmpty()) {
            return true;
        }
        return roomReqs.stream().anyMatch(req -> matchesRoomRequirement(room, req));
    }

    public boolean isParaclinicalOrLabService(Long serviceId) {
        if (serviceId == null || serviceRepo == null) {
            return false;
        }
        Service svc = serviceRepo.findById(serviceId).orElse(null);
        return svc != null && (Boolean.TRUE.equals(svc.getIsParaclinical())
                || svc.getServiceType() == ServiceType.XRAY
                || svc.getServiceType() == ServiceType.LAB);
    }

    public String roomTypeLabel(RoomType roomType) {
        if (roomType == null) {
            return "Phòng khám";
        }
        return switch (roomType) {
            case EXAM -> "Khám & Tư vấn";
            case TREATMENT -> "Điều trị";
            case XRAY -> "Chẩn đoán hình ảnh";
            case LAB -> "Xét nghiệm / Labo";
            case OTHER -> "Phòng chức năng khác";
        };
    }

    private boolean matchesRoomRequirement(Room room, ServiceResourceRequirement req) {
        if (room == null || req == null) {
            return false;
        }
        if (req.getRoom() != null && !Objects.equals(room.getRoomId(), req.getRoom().getRoomId())) {
            return false;
        }
        if (req.getRoomType() != null && room.getRoomType() != req.getRoomType()) {
            return false;
        }
        return true;
    }

    public boolean isChairOperational(DentalChair chair) {
        return chair != null
                && chair.getRoom() != null
                && chair.getRoom().getStatus() == RoomStatus.ACTIVE
                && chair.getStatus() == ChairStatus.AVAILABLE;
    }
}
