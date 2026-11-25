package com.cvdcms.service;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.ClinicEquipment;
import com.cvdcms.entity.EquipmentTransferLog;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.EquipmentAllocationMode;
import com.cvdcms.entity.enums.EquipmentTransferType;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.ClinicEquipmentRepository;
import com.cvdcms.repository.EquipmentTransferLogRepository;
import com.cvdcms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class EquipmentTransferService {

    private final ClinicEquipmentRepository equipmentRepository;
    private final EquipmentTransferLogRepository transferLogRepository;
    private final BranchRepository branchRepository;
    private final RoomRepository roomRepository;

    @Transactional(readOnly = true)
    public List<EquipmentTransferLog> getTransferHistory(Long equipmentId) {
        return transferLogRepository.findByEquipmentEquipmentIdOrderByTransferredAtDesc(equipmentId);
    }

    @Transactional
    public EquipmentTransferLog transferEquipment(
            Long equipmentId,
            Long targetBranchId,
            Long targetRoomId,
            String reason,
            Long actorStaffId
    ) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Lý do điều chuyển thiết bị không được để trống.");
        }
        if (actorStaffId == null) {
            throw new IllegalArgumentException("Thiếu thông tin người thực hiện điều chuyển.");
        }
        ClinicEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thiết bị."));
        Branch targetBranch = branchRepository.findById(targetBranchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh đích."));

        Long oldBranchId = equipment.getBranch().getBranchId();
        Long oldRoomId = equipment.getFixedRoom() != null ? equipment.getFixedRoom().getRoomId() : null;

        Room targetRoom = null;
        if (targetRoomId != null) {
            targetRoom = roomRepository.findById(targetRoomId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng đích."));
            if (!Objects.equals(targetRoom.getBranch().getBranchId(), targetBranchId)) {
                throw new IllegalArgumentException("Phòng đích không thuộc chi nhánh đích.");
            }
        }
        if (equipment.getAllocationMode() == EquipmentAllocationMode.FIXED_ROOM && targetRoom == null) {
            throw new IllegalArgumentException("Thiết bị cố định (FIXED_ROOM) bắt buộc chọn phòng đích khi điều chuyển.");
        }
        if (equipment.getAllocationMode() == EquipmentAllocationMode.BRANCH_SHARED && targetRoom != null) {
            throw new IllegalArgumentException("Thiết bị dùng chung toàn chi nhánh không gắn cố định vào 1 phòng.");
        }

        boolean branchChanged = !Objects.equals(oldBranchId, targetBranchId);
        if (branchChanged && equipmentRepository.existsByBranchBranchIdAndEquipmentCodeAndEquipmentIdNot(
                targetBranchId, equipment.getEquipmentCode(), equipmentId)) {
            throw new IllegalArgumentException("Mã thiết bị '" + equipment.getEquipmentCode() + "' đã tồn tại ở chi nhánh đích.");
        }

        LocalDateTime now = LocalDateTime.now();
        EquipmentTransferLog log = new EquipmentTransferLog();
        log.setEquipment(equipment);
        log.setTransferType(branchChanged ? EquipmentTransferType.BRANCH_TRANSFER : EquipmentTransferType.ROOM_REASSIGNMENT);
        log.setFromBranchId(oldBranchId);
        log.setToBranchId(targetBranchId);
        log.setFromRoomId(oldRoomId);
        log.setToRoomId(targetRoom != null ? targetRoom.getRoomId() : null);
        log.setReason(reason.trim());
        log.setTransferredBy(actorStaffId);
        log.setTransferredAt(now);
        log.setEffectiveFrom(now);

        equipment.setBranch(targetBranch);
        equipment.setFixedRoom(targetRoom);
        equipment.setCurrentRoom(targetRoom);
        equipmentRepository.save(equipment);
        return transferLogRepository.save(log);
    }
}
