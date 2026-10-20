package com.cvdcms.service;

import com.cvdcms.DTO.RoomDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.RoomStatus;
import com.cvdcms.entity.enums.RoomType;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.DentalChairRepository;
import com.cvdcms.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

// Nghiệp vụ quản lý Phòng khám (rooms) theo từng chi nhánh - FT40.
@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final BranchRepository branchRepository;
    private final DentalChairRepository dentalChairRepository;

    public RoomService(
            RoomRepository roomRepository,
            BranchRepository branchRepository,
            DentalChairRepository dentalChairRepository) {
        this.roomRepository = roomRepository;
        this.branchRepository = branchRepository;
        this.dentalChairRepository = dentalChairRepository;
    }

    @Transactional(readOnly = true)
    public List<Room> getRoomsByBranch(Long branchId) {
        return roomRepository.findByBranchBranchIdOrderByRoomCodeAsc(branchId);
    }

    // Dùng cho các phân hệ khác (lễ tân, bác sĩ) khi cần chọn phòng đang hoạt động.
    @Transactional(readOnly = true)
    public List<Room> getActiveRoomsByBranch(Long branchId) {
        return roomRepository.findByBranchBranchIdAndStatusOrderByRoomCodeAsc(branchId, RoomStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Room getRoomById(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng."));
    }

    @Transactional
    public Room createRoom(Long branchId, RoomDTO dto) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));

        String code = normalizeCode(dto.getRoomCode(), "Mã phòng");
        String name = requireText(dto.getRoomName(), "Tên phòng");

        if (roomRepository.existsByBranchBranchIdAndRoomCode(branchId, code)) {
            throw new IllegalArgumentException("Mã phòng '" + code + "' đã tồn tại trong chi nhánh này.");
        }

        Room room = new Room();
        room.setBranch(branch);
        room.setRoomCode(code);
        room.setRoomName(name);
        room.setRoomType(dto.getRoomType() != null ? dto.getRoomType() : RoomType.TREATMENT);
        room.setStatus(dto.getStatus() != null ? dto.getStatus() : RoomStatus.ACTIVE);
        return roomRepository.save(room);
    }

    @Transactional
    public Room updateRoom(Long branchId, Long roomId, RoomDTO dto) {
        Room room = getRoomOfBranch(branchId, roomId);

        String code = normalizeCode(dto.getRoomCode(), "Mã phòng");
        String name = requireText(dto.getRoomName(), "Tên phòng");

        if (roomRepository.existsByBranchBranchIdAndRoomCodeAndRoomIdNot(branchId, code, roomId)) {
            throw new IllegalArgumentException("Mã phòng '" + code + "' đã được dùng bởi phòng khác.");
        }

        room.setRoomCode(code);
        room.setRoomName(name);
        if (dto.getRoomType() != null) {
            room.setRoomType(dto.getRoomType());
        }
        if (dto.getStatus() != null) {
            room.setStatus(dto.getStatus());
        }
        return roomRepository.save(room);
    }

    // Chỉ cho xóa phòng khi không còn ghế nha gắn với phòng.
    @Transactional
    public void deleteRoom(Long branchId, Long roomId) {
        Room room = getRoomOfBranch(branchId, roomId);

        long chairCount = dentalChairRepository.countByRoomRoomId(roomId);
        if (chairCount > 0) {
            throw new IllegalArgumentException(
                    "Phòng đang có " + chairCount + " ghế nha. Hãy chuyển hoặc xóa ghế trước khi xóa phòng.");
        }
        roomRepository.delete(room);
    }

    private Room getRoomOfBranch(Long branchId, Long roomId) {
        Room room = getRoomById(roomId);
        if (!room.getBranch().getBranchId().equals(branchId)) {
            throw new IllegalArgumentException("Phòng không thuộc chi nhánh này.");
        }
        return room;
    }

    private String normalizeCode(String value, String label) {
        String code = requireText(value, label).toUpperCase(Locale.ROOT);
        if (!code.matches("^[A-Z0-9_\\-]{1,20}$")) {
            throw new IllegalArgumentException(label + " chỉ được chứa chữ cái không dấu, số, dấu gạch ngang/dưới (1-20 ký tự).");
        }
        return code;
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " không được để trống.");
        }
        String t = value.trim();
        if (t.length() > 100) {
            throw new IllegalArgumentException(label + " không được vượt quá 100 ký tự.");
        }
        return t;
    }
}
