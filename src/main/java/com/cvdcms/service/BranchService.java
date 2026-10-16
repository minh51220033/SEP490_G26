
package com.cvdcms.service;

import com.cvdcms.entity.Branch;
import com.cvdcms.repository.BranchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class BranchService {

    private final BranchRepository branchRepository;

    // Constructor injection: Spring tự truyền Repository vào Service.
    public BranchService(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
    }

    // Lấy toàn bộ chi nhánh để hiển thị ở trang quản lý.
    @Transactional(readOnly = true)
    public List<Branch> getAllBranches() {
        return branchRepository.findAllByOrderByBranchNameAsc();
    }

    // Chỉ lấy chi nhánh đang hoạt động.
    // Sau này dùng danh sách này khi người dùng đặt lịch.
    @Transactional(readOnly = true)
    public List<Branch> getActiveBranches() {
        return branchRepository.findByIsActiveTrueOrderByBranchNameAsc();
    }

    // Thêm chi nhánh mới sau khi kiểm tra dữ liệu đầu vào.
    @Transactional
    public void createBranch(Branch branch) {
        // Chuẩn hóa mã chi nhánh để tránh các trường hợp
        // "CN01", "cn01" hoặc " CN01 " bị coi là mã khác nhau.
        String code = branch.getBranchCode();

        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Mã chi nhánh không được để trống.");
        }

        code = code.trim().toUpperCase(Locale.ROOT);
        branch.setBranchCode(code);

        // Kiểm tra tên chi nhánh.
        if (branch.getBranchName() == null
                || branch.getBranchName().isBlank()) {
            throw new IllegalArgumentException("Tên chi nhánh không được để trống.");
        }

        branch.setBranchName(branch.getBranchName().trim());

        // Kiểm tra địa chỉ chi nhánh.
        if (branch.getAddress() == null || branch.getAddress().isBlank()) {
            throw new IllegalArgumentException("Địa chỉ chi nhánh không được để trống.");
        }

        branch.setAddress(branch.getAddress().trim());

        // Không cho phép tạo hai chi nhánh có cùng mã.
        if (branchRepository.existsByBranchCode(code)) {
            throw new IllegalArgumentException("Mã chi nhánh đã tồn tại.");
        }

        // Chi nhánh mới mặc định hoạt động.
        branch.setIsActive(true);

        branchRepository.save(branch);
    }

    // Ngừng hoạt động chi nhánh thay vì xóa vật lý.
    // Cách này tránh làm mất dữ liệu tham chiếu từ hồ sơ hoặc lịch hẹn.
    @Transactional
    public void deactivateBranch(Long branchId) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Không tìm thấy chi nhánh."));

        branch.setIsActive(false);
        branchRepository.save(branch);
    }

    // Kích hoạt lại một chi nhánh đã ngừng hoạt động.
    @Transactional
    public void activateBranch(Long branchId) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Không tìm thấy chi nhánh."));

        branch.setIsActive(true);
        branchRepository.save(branch);
    }
}
