package com.cvdcms.service;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.ClinicEquipment;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.EquipmentAllocationMode;
import com.cvdcms.entity.enums.EquipmentStatus;
import com.cvdcms.entity.enums.EquipmentType;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.ClinicEquipmentRepository;
import com.cvdcms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ClinicEquipmentService {

    private final ClinicEquipmentRepository equipmentRepository;
    private final BranchRepository branchRepository;
    private final RoomRepository roomRepository;

    @Transactional(readOnly = true)
    public List<ClinicEquipment> getEquipmentsByBranch(Long branchId) {
        return equipmentRepository.findByBranchBranchIdOrderByEquipmentCodeAsc(branchId);
    }

    /**
     * Chỉ trả các thiết bị đã xác nhận ACTIVE thuộc chi nhánh (loại bỏ PENDING_VERIFICATION, MAINTENANCE, INACTIVE).
     */
    @Transactional(readOnly = true)
    public List<ClinicEquipment> getActiveEquipmentsByBranch(Long branchId) {
        return equipmentRepository.findByBranchBranchIdAndStatusOrderByEquipmentCodeAsc(branchId, EquipmentStatus.ACTIVE);
    }

    @Transactional
    public ClinicEquipment createEquipment(
            Long branchId,
            String equipmentCode,
            String equipmentName,
            EquipmentType equipmentType,
            EquipmentAllocationMode allocationMode,
            Long fixedRoomId,
            String deviceSerialNumber,
            EquipmentStatus initialStatus
    ) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        String code = normalizeCode(equipmentCode);
        if (equipmentName == null || equipmentName.isBlank()) {
            throw new IllegalArgumentException("Tên thiết bị không được để trống.");
        }
        if (equipmentRepository.existsByBranchBranchIdAndEquipmentCode(branchId, code)) {
            throw new IllegalArgumentException("Mã thiết bị '" + code + "' đã tồn tại trong chi nhánh.");
        }
        String serial = normalizeSerial(deviceSerialNumber);
        if (serial != null && equipmentRepository.existsByDeviceSerialNumber(serial)) {
            throw new IllegalArgumentException("Số serial '" + serial + "' đã tồn tại trên hệ thống.");
        }

        EquipmentAllocationMode mode = allocationMode != null ? allocationMode : EquipmentAllocationMode.FIXED_ROOM;
        Room fixedRoom = resolveFixedRoom(branchId, mode, fixedRoomId);

        ClinicEquipment equipment = new ClinicEquipment();
        equipment.setBranch(branch);
        equipment.setEquipmentCode(code);
        equipment.setEquipmentName(equipmentName.trim());
        equipment.setEquipmentType(equipmentType != null ? equipmentType : EquipmentType.OTHER);
        equipment.setAllocationMode(mode);
        equipment.setFixedRoom(fixedRoom);
        equipment.setCurrentRoom(fixedRoom);
        equipment.setDeviceSerialNumber(serial);
        equipment.setStatus(initialStatus != null ? initialStatus : EquipmentStatus.PENDING_VERIFICATION);
        return equipmentRepository.save(equipment);
    }

    @Transactional
    public ClinicEquipment verifyAndActivate(Long branchId, Long equipmentId, String confirmedSerial) {
        ClinicEquipment equipment = requireEquipmentOfBranch(branchId, equipmentId);
        String serial = normalizeSerial(confirmedSerial != null ? confirmedSerial : equipment.getDeviceSerialNumber());
        if (serial != null && equipmentRepository.existsByDeviceSerialNumberAndEquipmentIdNot(serial, equipmentId)) {
            throw new IllegalArgumentException("Số serial '" + serial + "' đã được dùng cho thiết bị khác.");
        }
        if (equipment.getAllocationMode() == EquipmentAllocationMode.FIXED_ROOM && equipment.getFixedRoom() == null) {
            throw new IllegalArgumentException("Thiết bị cố định phải gắn với 1 phòng hợp lệ trước khi kích hoạt.");
        }
        equipment.setDeviceSerialNumber(serial);
        equipment.setStatus(EquipmentStatus.ACTIVE);
        return equipmentRepository.save(equipment);
    }

    @Transactional
    public ClinicEquipment updateStatus(Long branchId, Long equipmentId, EquipmentStatus newStatus) {
        ClinicEquipment equipment = requireEquipmentOfBranch(branchId, equipmentId);
        if (newStatus == null) {
            throw new IllegalArgumentException("Trạng thái thiết bị không được để trống.");
        }
        equipment.setStatus(newStatus);
        return equipmentRepository.save(equipment);
    }

    public ClinicEquipment requireEquipmentOfBranch(Long branchId, Long equipmentId) {
        ClinicEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thiết bị."));
        if (!Objects.equals(equipment.getBranch().getBranchId(), branchId)) {
            throw new IllegalArgumentException("Thiết bị không thuộc chi nhánh này.");
        }
        return equipment;
    }

    private Room resolveFixedRoom(Long branchId, EquipmentAllocationMode mode, Long fixedRoomId) {
        if (mode == EquipmentAllocationMode.BRANCH_SHARED) {
            if (fixedRoomId != null) {
                throw new IllegalArgumentException("Thiết bị dùng chung toàn chi nhánh không được gán cố định 1 phòng.");
            }
            return null;
        }
        if (fixedRoomId == null) {
            throw new IllegalArgumentException("Thiết bị cố định (FIXED_ROOM) bắt buộc chọn phòng gắn cố định.");
        }
        Room room = roomRepository.findById(fixedRoomId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng gắn thiết bị."));
        if (!Objects.equals(room.getBranch().getBranchId(), branchId)) {
            throw new IllegalArgumentException("Phòng gắn thiết bị không thuộc chi nhánh này.");
        }
        return room;
    }

    private String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Mã thiết bị không được để trống.");
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("^[A-Z0-9_\\-]{2,30}$")) {
            throw new IllegalArgumentException("Mã thiết bị chỉ gồm 2–30 ký tự chữ không dấu, số, gạch ngang hoặc gạch dưới.");
        }
        return code;
    }

    private String normalizeSerial(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw.trim().toUpperCase(Locale.ROOT);
    }
}
