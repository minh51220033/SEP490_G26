package com.cvdcms.service;

import com.cvdcms.DTO.DentalChairDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.ChairStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.DentalChairRepository;
import com.cvdcms.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

// Nghiệp vụ quản lý Ghế nha khoa (dental_chairs) - FT40.
// Các phân hệ khác (lễ tân xếp hàng, bác sĩ mở ca) dùng getAvailableChairs / updateChairStatus.
@Service
public class DentalChairService {

    private final DentalChairRepository chairRepository;
    private final BranchRepository branchRepository;
    private final RoomRepository roomRepository;

    public DentalChairService(
            DentalChairRepository chairRepository,
            BranchRepository branchRepository,
            RoomRepository roomRepository) {
        this.chairRepository = chairRepository;
        this.branchRepository = branchRepository;
        this.roomRepository = roomRepository;
    }

    @Transactional(readOnly = true)
    public List<DentalChair> getChairsByBranch(Long branchId) {
        return chairRepository.findByBranchBranchIdOrderByChairCodeAsc(branchId);
    }

    @Transactional(readOnly = true)
    public List<DentalChair> getChairsByRoom(Long roomId) {
        return chairRepository.findByRoomRoomIdOrderByChairCodeAsc(roomId);
    }

    @Transactional(readOnly = true)
    public List<DentalChair> getAvailableChairs(Long branchId) {
        return chairRepository.findByBranchBranchIdAndStatusOrderByChairCodeAsc(branchId, ChairStatus.AVAILABLE);
    }

    @Transactional(readOnly = true)
    public long countByStatus(Long branchId, ChairStatus status) {
        return chairRepository.countByBranchBranchIdAndStatus(branchId, status);
    }

    @Transactional
    public DentalChair createChair(Long branchId, DentalChairDTO dto) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));

        String code = normalizeCode(dto.getChairCode(), "Mã ghế");
        String name = requireText(dto.getChairName(), "Tên ghế");

        if (chairRepository.existsByBranchBranchIdAndChairCode(branchId, code)) {
            throw new IllegalArgumentException("Mã ghế '" + code + "' đã tồn tại trong chi nhánh này.");
        }

        DentalChair chair = new DentalChair();
        chair.setBranch(branch);
        chair.setRoom(resolveRoom(branchId, dto.getRoomId()));
        chair.setChairCode(code);
        chair.setChairName(name);
        chair.setStatus(dto.getStatus() != null ? dto.getStatus() : ChairStatus.AVAILABLE);
        return chairRepository.save(chair);
    }

    @Transactional
    public DentalChair updateChair(Long branchId, Long chairId, DentalChairDTO dto) {
        DentalChair chair = getChairOfBranch(branchId, chairId);

        String code = normalizeCode(dto.getChairCode(), "Mã ghế");
        String name = requireText(dto.getChairName(), "Tên ghế");

        if (chairRepository.existsByBranchBranchIdAndChairCodeAndChairIdNot(branchId, code, chairId)) {
            throw new IllegalArgumentException("Mã ghế '" + code + "' đã được dùng bởi ghế khác.");
        }

        chair.setChairCode(code);
        chair.setChairName(name);
        chair.setRoom(resolveRoom(branchId, dto.getRoomId()));
        if (dto.getStatus() != null) {
            chair.setStatus(dto.getStatus());
        }
        return chairRepository.save(chair);
    }

    @Transactional
    public DentalChair updateChairStatus(Long branchId, Long chairId, ChairStatus status) {
        DentalChair chair = getChairOfBranch(branchId, chairId);
        chair.setStatus(status);
        return chairRepository.save(chair);
    }

    @Transactional
    public void deleteChair(Long branchId, Long chairId) {
        DentalChair chair = getChairOfBranch(branchId, chairId);
        if (chair.getStatus() == ChairStatus.IN_USE) {
            throw new IllegalArgumentException("Không thể xóa ghế đang có ca khám (IN_USE).");
        }
        chairRepository.delete(chair);
    }

    private Room resolveRoom(Long branchId, Long roomId) {
        if (roomId == null) {
            return null;
        }
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng."));
        if (!room.getBranch().getBranchId().equals(branchId)) {
            throw new IllegalArgumentException("Phòng không thuộc chi nhánh này.");
        }
        return room;
    }

    private DentalChair getChairOfBranch(Long branchId, Long chairId) {
        DentalChair chair = chairRepository.findById(chairId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ghế nha."));
        if (!chair.getBranch().getBranchId().equals(branchId)) {
            throw new IllegalArgumentException("Ghế không thuộc chi nhánh này.");
        }
        return chair;
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
        if (t.length() > 50) {
            throw new IllegalArgumentException(label + " không được vượt quá 50 ký tự.");
        }
        return t;
    }
}
