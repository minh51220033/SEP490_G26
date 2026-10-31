package com.cvdcms.controller;

import com.cvdcms.DTO.MemberCardDTO;
import com.cvdcms.DTO.PointAdjustmentDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PointTransaction;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.LoyaltyService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;
import java.util.List;

@Controller
@RequestMapping("/reception/memberships")
public class ReceptionMembershipController {

    private final LoyaltyService loyaltyService;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final com.cvdcms.repository.PatientProfileRepository patientProfileRepository;

    public ReceptionMembershipController(
            LoyaltyService loyaltyService,
            BranchRepository branchRepository,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            com.cvdcms.repository.PatientProfileRepository patientProfileRepository
    ) {
        this.loyaltyService = loyaltyService;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.patientProfileRepository = patientProfileRepository;
    }

    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("activeMenu", "reception-memberships");
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        }
    }

    private User getAuthenticatedStaff(HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để tiếp tục.");
        }
        boolean isAuthorized = (u.getUserId() != null && (
                userRepository.hasActiveAdminRole(u.getUserId())
                || userRepository.hasActivePermission(u.getUserId(), "PATIENT_PROFILE_MANAGE")
                || userRepository.hasActivePermission(u.getUserId(), "CUSTOMER_CARE_MANAGE")
        ));
        if (!isAuthorized) {
            Object roleNames = session.getAttribute("roleNames");
            isAuthorized = roleNames instanceof Collection<?> c
                    && c.stream().anyMatch(r -> {
                String s = String.valueOf(r).toUpperCase();
                return s.contains("ADMIN");
            });
        }
        if (!isAuthorized) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập thông tin thẻ thành viên.");
        }
        return u;
    }

    private List<Branch> resolveAllowedBranches(Long userId) {
        boolean isAdmin = userRepository.hasActiveAdminRole(userId);
        if (isAdmin) {
            return branchRepository.findAll();
        }
        List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
        if (branchIds == null || branchIds.isEmpty()) {
            return branchRepository.findAll();
        }
        return branchRepository.findAllById(branchIds);
    }

    @GetMapping
    public String searchMemberships(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long branchId,
            Model model,
            HttpSession session
    ) {
        User user;
        try {
            user = getAuthenticatedStaff(session);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return "redirect:/login";
            }
            throw e;
        }

        addLayoutAttributes(session, model);

        boolean isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        List<Branch> allowedBranches = resolveAllowedBranches(user.getUserId());
        List<Long> allowedBranchIds = allowedBranches.stream().map(Branch::getBranchId).toList();

        Long effectiveBranchId = branchId;
        if (!isAdmin) {
            if (allowedBranchIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản nhân viên chưa được phân công chi nhánh hợp lệ.");
            }
            if (effectiveBranchId == null) {
                // Không cho người dùng bỏ branchId để xem mọi chi nhánh ngoài quyền -> mặc định về chi nhánh phân công
                effectiveBranchId = allowedBranchIds.get(0);
            } else if (!allowedBranchIds.contains(effectiveBranchId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + effectiveBranchId);
            }
        }

        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("keyword", keyword);
        model.addAttribute("isAdmin", isAdmin);

        List<MemberCardDTO> cards = loyaltyService.searchMemberCards(keyword, effectiveBranchId);
        model.addAttribute("memberCards", cards);

        return "reception/memberships/search";
    }

    @GetMapping("/{profileId}")
    public String viewMemberDetail(
            @PathVariable Long profileId,
            Model model,
            HttpSession session
    ) {
        User user;
        try {
            user = getAuthenticatedStaff(session);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return "redirect:/login";
            }
            throw e;
        }

        addLayoutAttributes(session, model);

        boolean isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        if (!isAdmin) {
            List<Long> allowedBranchIds = resolveAllowedBranches(user.getUserId()).stream().map(Branch::getBranchId).toList();
            com.cvdcms.entity.PatientProfile patient = patientProfileRepository.findById(profileId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân ID: " + profileId));
            Long preferredBranchId = patient.getPreferredBranch() != null ? patient.getPreferredBranch().getBranchId() : null;
            if (preferredBranchId == null || !allowedBranchIds.contains(preferredBranchId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem thông tin thẻ thành viên của bệnh nhân thuộc chi nhánh khác.");
            }
        }

        MemberCardDTO card = loyaltyService.getMemberCard(profileId);
        List<PointTransaction> history = loyaltyService.getPointHistory(profileId);

        model.addAttribute("card", card);
        model.addAttribute("pointHistory", history);
        model.addAttribute("adjustForm", new PointAdjustmentDTO());
        model.addAttribute("isAdmin", isAdmin);

        return "reception/memberships/detail";
    }

    @PostMapping("/{profileId}/adjust-points")
    public String adjustPoints(
            @PathVariable Long profileId,
            @Valid @ModelAttribute("adjustForm") PointAdjustmentDTO dto,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        User user;
        try {
            user = getAuthenticatedStaff(session);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return "redirect:/login";
            }
            throw e;
        }

        // FT19: Chỉ Admin mới có quyền điều chỉnh điểm tích lũy
        boolean isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        if (!isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Theo quy định FT19, chỉ Quản trị viên (Admin) mới có quyền điều chỉnh điểm tích lũy của bệnh nhân.");
        }

        dto.setProfileId(profileId);

        if (bindingResult.hasErrors()) {
            String errorMsg = bindingResult.getFieldErrors().stream()
                    .map(e -> e.getDefaultMessage())
                    .findFirst().orElse("Dữ liệu điều chỉnh điểm không hợp lệ.");
            redirectAttributes.addFlashAttribute("errorMessage", errorMsg);
            return "redirect:/reception/memberships/" + profileId;
        }

        try {
            loyaltyService.adjustPoints(dto, user.getUserId());
            String text = dto.getPoints() > 0 ? "Cộng " + dto.getPoints() : "Trừ " + Math.abs(dto.getPoints());
            redirectAttributes.addFlashAttribute("successMessage", text + " điểm thành công. Lý do: " + dto.getReason());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }

        return "redirect:/reception/memberships/" + profileId;
    }
}
