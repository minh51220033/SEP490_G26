
package com.cvdcms.service;

import com.cvdcms.DTO.BranchConfigDTO;
import com.cvdcms.entity.Branch;
import tools.jackson.databind.ObjectMapper;
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
        if (!code.matches("^[A-Z0-9_\\-]{2,30}$")) {
            throw new IllegalArgumentException("Mã chi nhánh chỉ được chứa chữ cái không dấu, số, dấu gạch ngang/dưới (2-30 ký tự).");
        }
        branch.setBranchCode(code);

        // Kiểm tra tên chi nhánh.
        if (branch.getBranchName() == null
                || branch.getBranchName().isBlank()) {
            throw new IllegalArgumentException("Tên chi nhánh không được để trống.");
        }
        String name = branch.getBranchName().trim();
        if (name.length() > 150) {
            throw new IllegalArgumentException("Tên chi nhánh tối đa 150 ký tự.");
        }
        branch.setBranchName(name);

        // Kiểm tra địa chỉ chi nhánh.
        if (branch.getAddress() == null || branch.getAddress().isBlank()) {
            throw new IllegalArgumentException("Địa chỉ chi nhánh không được để trống.");
        }
        String address = branch.getAddress().trim();
        if (address.length() > 255) {
            throw new IllegalArgumentException("Địa chỉ chi nhánh tối đa 255 ký tự.");
        }
        branch.setAddress(address);

        // Kiểm tra số điện thoại và email
        branch.setPhone(validatePhone(branch.getPhone()));
        branch.setEmail(validateEmail(branch.getEmail()));

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
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));

        branch.setIsActive(false);
        branchRepository.save(branch);
    }

    // Kích hoạt lại một chi nhánh đã ngừng hoạt động.
    @Transactional
    public void activateBranch(Long branchId) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));

        branch.setIsActive(true);
        branchRepository.save(branch);
    }

    // Lấy chi nhánh theo ID cho trang chi tiết.
    @Transactional(readOnly = true)
    public Branch getBranchById(Long branchId) {
        return branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
    }

    // Cập nhật thông tin cơ bản (mã chi nhánh giữ nguyên).
    @Transactional
    public void updateBranch(Long branchId, Branch data) {
        Branch branch = getBranchById(branchId);

        if (data.getBranchName() == null || data.getBranchName().isBlank()) {
            throw new IllegalArgumentException("Tên chi nhánh không được để trống.");
        }
        String name = data.getBranchName().trim();
        if (name.length() > 150) {
            throw new IllegalArgumentException("Tên chi nhánh tối đa 150 ký tự.");
        }

        if (data.getAddress() == null || data.getAddress().isBlank()) {
            throw new IllegalArgumentException("Địa chỉ chi nhánh không được để trống.");
        }
        String address = data.getAddress().trim();
        if (address.length() > 255) {
            throw new IllegalArgumentException("Địa chỉ chi nhánh tối đa 255 ký tự.");
        }

        branch.setBranchName(name);
        branch.setAddress(address);
        branch.setPhone(validatePhone(data.getPhone()));
        branch.setEmail(validateEmail(data.getEmail()));
        branchRepository.save(branch);
    }

    // Cấu hình dải IP Wifi chấm công (SCR-40.3), giờ mở cửa và ngày làm việc.
    @Transactional
    public void updateBranchConfig(Long branchId, BranchConfigDTO config) {
        Branch branch = getBranchById(branchId);

        if (config.getOpeningTime() != null && config.getClosingTime() != null
                && !config.getOpeningTime().isBefore(config.getClosingTime())) {
            throw new IllegalArgumentException("Giờ mở cửa phải trước giờ đóng cửa.");
        }

        String tvJson = blankToNull(config.getTvConfigJson());
        if (tvJson != null) {
            try {
                new ObjectMapper().readTree(tvJson);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Cấu hình TV phải là JSON hợp lệ.");
            }
        }

        branch.setAllowedIpRange(blankToNull(config.getAllowedIpRange()));
        branch.setTvConfigJson(tvJson);
        if (config.getOpeningTime() != null) {
            branch.setOpeningTime(config.getOpeningTime());
        }
        if (config.getClosingTime() != null) {
            branch.setClosingTime(config.getClosingTime());
        }
        if (config.getWorkingDays() != null && !config.getWorkingDays().isBlank()) {
            branch.setWorkingDays(config.getWorkingDays().trim());
        }
        branchRepository.save(branch);
    }

    private String validatePhone(String phone) {
        String p = blankToNull(phone);
        if (p == null) {
            return null;
        }
        // Cho phép định dạng SĐT Việt Nam: 09x, 03x, 05x, 07x, 08x (10 số) hoặc có tiền tố +84
        if (!p.matches("^(\\+84|0)[0-9]{9,10}$")) {
            throw new IllegalArgumentException("Số điện thoại không đúng định dạng (ví dụ: 0901234567 hoặc +84901234567).");
        }
        return p;
    }

    private String validateEmail(String email) {
        String e = blankToNull(email);
        if (e == null) {
            return null;
        }
        if (!e.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new IllegalArgumentException("Địa chỉ email không hợp lệ (ví dụ: contact@dentalclinic.com).");
        }
        if (e.length() > 100) {
            throw new IllegalArgumentException("Email không được vượt quá 100 ký tự.");
        }
        return e.toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
