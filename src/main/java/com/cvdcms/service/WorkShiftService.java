package com.cvdcms.service;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.WorkShift;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.WorkShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

// FT33 - Cấu hình ca làm việc (Work Shifts) - SCR-33.0
@Service
public class WorkShiftService {

    private final WorkShiftRepository workShiftRepository;
    private final BranchRepository branchRepository;

    public WorkShiftService(WorkShiftRepository workShiftRepository,
                            BranchRepository branchRepository) {
        this.workShiftRepository = workShiftRepository;
        this.branchRepository = branchRepository;
    }

    // Lấy toàn bộ ca của 1 chi nhánh (kể cả đang tắt) để hiển thị bảng quản lý
    @Transactional
    public List<WorkShift> getShiftsByBranch(Long branchId) {
        Branch branch = findBranch(branchId);
        if (workShiftRepository.countByBranchBranchId(branchId) == 0) {
            createDefault(branch);
        }
        return workShiftRepository.findAll().stream()
                .filter(s -> s.getBranch() != null && branchId.equals(s.getBranch().getBranchId()))
                .sorted(Comparator.comparing(WorkShift::getStartTime))
                .collect(Collectors.toList());
    }

    // Tạo ca mới
    @Transactional
    public WorkShift create(Long branchId, String shiftCode, String shiftName, LocalTime startTime, LocalTime endTime) {
        Branch branch = findBranch(branchId);
        String finalCode = shiftCode.trim().toUpperCase();
        boolean exists = workShiftRepository.findAll().stream()
                .anyMatch(s -> s.getBranch() != null && branchId.equals(s.getBranch().getBranchId())
                        && finalCode.equalsIgnoreCase(s.getShiftCode()));
        if (exists) {
            throw new IllegalArgumentException("Mã ca '" + finalCode + "' đã tồn tại trong chi nhánh này.");
        }
        validateTime(startTime, endTime);
        WorkShift shift = new WorkShift();
        shift.setBranch(branch);
        shift.setShiftCode(finalCode);
        shift.setShiftName(shiftName.trim());
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        shift.setIsActive(true);
        return workShiftRepository.save(shift);
    }

    // Cập nhật ca
    @Transactional
    public WorkShift update(Long shiftId, String shiftCode, String shiftName, LocalTime startTime, LocalTime endTime) {
        WorkShift shift = findShift(shiftId);
        Long branchId = shift.getBranch() != null ? shift.getBranch().getBranchId() : null;
        String finalCode = shiftCode.trim().toUpperCase();
        boolean exists = workShiftRepository.findAll().stream()
                .anyMatch(s -> s.getBranch() != null && s.getBranch().getBranchId().equals(branchId)
                        && !s.getShiftId().equals(shiftId)
                        && finalCode.equalsIgnoreCase(s.getShiftCode()));
        if (exists) {
            throw new IllegalArgumentException("Mã ca '" + finalCode + "' đã được dùng bởi ca khác trong chi nhánh này.");
        }
        validateTime(startTime, endTime);
        shift.setShiftCode(finalCode);
        shift.setShiftName(shiftName.trim());
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        return workShiftRepository.save(shift);
    }

    // Bật / Tắt trạng thái ca
    @Transactional
    public WorkShift toggleActive(Long shiftId) {
        WorkShift shift = findShift(shiftId);
        shift.setIsActive(!Boolean.TRUE.equals(shift.getIsActive()));
        return workShiftRepository.save(shift);
    }

    // Xóa ca
    @Transactional
    public void delete(Long shiftId) {
        WorkShift shift = findShift(shiftId);
        workShiftRepository.delete(shift);
    }

    // ===== PRIVATE HELPERS =====

    private WorkShift findShift(Long shiftId) {
        return workShiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca làm việc #" + shiftId));
    }

    private Branch findBranch(Long branchId) {
        return branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh #" + branchId));
    }

    private void validateTime(LocalTime start, LocalTime end) {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Giờ kết thúc phải sau giờ bắt đầu.");
        }
    }

    private void createDefault(Branch branch) {
        createShift(branch, "SANG",  "Ca sáng (08:00 - 12:00)",  LocalTime.of(8, 0),  LocalTime.of(12, 0));
        createShift(branch, "CHIEU", "Ca chiều (13:30 - 17:30)", LocalTime.of(13, 30), LocalTime.of(17, 30));
        createShift(branch, "TOI",   "Ca tối (17:30 - 20:30)",   LocalTime.of(17, 30), LocalTime.of(20, 30));
    }

    private void createShift(Branch branch, String code, String name, LocalTime start, LocalTime end) {
        WorkShift s = new WorkShift();
        s.setBranch(branch);
        s.setShiftCode(code);
        s.setShiftName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setIsActive(true);
        workShiftRepository.save(s);
    }
}
