package com.cvdcms.controller;

import com.cvdcms.DTO.BranchAccessResponseDTO;
import com.cvdcms.DTO.BranchOptionDTO;
import com.cvdcms.entity.User;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.BranchService;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/branches")
public class BranchApiController {

    private final BranchService branchService;
    private final BranchAccessService branchAccessService;

    public BranchApiController(
            BranchService branchService,
            BranchAccessService branchAccessService
    ) {
        this.branchService = branchService;
        this.branchAccessService = branchAccessService;
    }

    /**
     * Trả về các chi nhánh tài khoản hiện tại được phép truy cập.
     *
     * ADMIN: được chọn chi nhánh.
     * QUAN_LY_KHO: giao diện không cho chọn chi nhánh.
     */
    @GetMapping("/accessible")
    public ResponseEntity<?> getAccessibleBranches(
            HttpSession session
    ) {
        Object loggedInUser = session.getAttribute("loggedInUser");

        if (!(loggedInUser instanceof User)) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body("Vui lòng đăng nhập lại.");
        }

        User user = (User) loggedInUser;
        Long userId = user.getUserId();

        if (userId == null || userId <= 0) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body("Thông tin tài khoản không hợp lệ.");
        }

        boolean isAdmin = branchAccessService.isAdmin(userId);

        List<BranchOptionDTO> branches = branchService
                .getAccessibleBranches(userId)
                .stream()
                .map(branch -> new BranchOptionDTO(
                        branch.getBranchId(),
                        branch.getBranchCode(),
                        branch.getBranchName()
                ))
                .collect(Collectors.toList());

        BranchAccessResponseDTO response =
                new BranchAccessResponseDTO(isAdmin, branches);

        return ResponseEntity.ok(response);
    }

    /**
     * Trả về danh sách tất cả các chi nhánh đang hoạt động (dùng để chọn Chi nhánh đích khi Điều chuyển kho).
     */
    @GetMapping("/active")
    public ResponseEntity<?> getAllActiveBranches(HttpSession session) {
        Object loggedInUser = session.getAttribute("loggedInUser");
        if (!(loggedInUser instanceof User user) || user.getUserId() == null) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body("Vui lòng đăng nhập lại.");
        }

        List<BranchOptionDTO> branches = branchService
                .getActiveBranches()
                .stream()
                .map(branch -> new BranchOptionDTO(
                        branch.getBranchId(),
                        branch.getBranchCode(),
                        branch.getBranchName()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(branches);
    }
}
