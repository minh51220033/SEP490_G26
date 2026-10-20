package com.cvdcms.controller;

import com.cvdcms.DTO.BranchConfigDTO;
import com.cvdcms.DTO.DentalChairDTO;
import com.cvdcms.DTO.RoomDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.ChairStatus;
import com.cvdcms.entity.enums.RoomStatus;
import com.cvdcms.entity.enums.RoomType;
import com.cvdcms.service.BranchService;
import com.cvdcms.service.DentalChairService;
import com.cvdcms.service.RoomService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.cvdcms.entity.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// FT40 - Quản lý Chi nhánh, Phòng khám & Ghế nha (SCR-40.1, 40.2, 40.3).
@Controller
@RequestMapping("/admin/branches")
public class BranchController {

    private final BranchService branchService;
    private final RoomService roomService;
    private final DentalChairService chairService;

    public BranchController(
            BranchService branchService,
            RoomService roomService,
            DentalChairService chairService) {
        this.branchService = branchService;
        this.roomService = roomService;
        this.chairService = chairService;
    }

    // Đưa thông tin người dùng từ session vào model để layout chung (sidebar, topbar) hiển thị đúng.
    private void addLayoutAttributes(HttpSession session, Model model) {
        model.addAttribute("roleNames", session.getAttribute("roleNames"));

        Object user = session.getAttribute("loggedInUser");
        if (user instanceof User u) {
            model.addAttribute("displayName", u.getUsername());
        }
    }

    // GET /admin/branches - SCR-40.1: danh sách chi nhánh kèm số phòng / số ghế.
    @GetMapping
    public String listBranches(Model model, HttpSession session) {
        addLayoutAttributes(session, model);

        List<Branch> branches = branchService.getAllBranches();
        Map<Long, Integer> roomCounts = new HashMap<>();
        Map<Long, Integer> chairCounts = new HashMap<>();
        for (Branch b : branches) {
            roomCounts.put(b.getBranchId(), roomService.getRoomsByBranch(b.getBranchId()).size());
            chairCounts.put(b.getBranchId(), chairService.getChairsByBranch(b.getBranchId()).size());
        }

        model.addAttribute("branches", branches);
        model.addAttribute("roomCounts", roomCounts);
        model.addAttribute("chairCounts", chairCounts);

        // Tạo đối tượng rỗng để Thymeleaf liên kết dữ liệu biểu mẫu.
        model.addAttribute("branchForm", new Branch());

        return "branch/list";
    }

    // POST /admin/branches - Thêm chi nhánh mới.
    @PostMapping
    public String createBranch(
            @ModelAttribute("branchForm") Branch branch,
            RedirectAttributes redirectAttributes) {

        try {
            branchService.createBranch(branch);
            redirectAttributes.addFlashAttribute("successMessage", "Thêm chi nhánh thành công.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/branches";
    }

    // GET /admin/branches/{id} - SCR-40.2 & 40.3: chi tiết chi nhánh, sơ đồ phòng - ghế, cấu hình.
    @GetMapping("/{id}")
    public String branchDetail(
            @PathVariable Long id,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Branch branch;
        try {
            branch = branchService.getBranchById(id);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/branches";
        }

        addLayoutAttributes(session, model);

        List<Room> rooms = roomService.getRoomsByBranch(id);
        List<DentalChair> chairs = chairService.getChairsByBranch(id);

        // Gom ghế theo phòng; ghế chưa gán phòng để riêng.
        Map<Long, List<DentalChair>> chairsByRoom = new LinkedHashMap<>();
        for (Room room : rooms) {
            chairsByRoom.put(room.getRoomId(), new ArrayList<>());
        }
        List<DentalChair> unassignedChairs = new ArrayList<>();
        for (DentalChair chair : chairs) {
            if (chair.getRoom() != null && chairsByRoom.containsKey(chair.getRoom().getRoomId())) {
                chairsByRoom.get(chair.getRoom().getRoomId()).add(chair);
            } else {
                unassignedChairs.add(chair);
            }
        }

        BranchConfigDTO config = new BranchConfigDTO();
        config.setAllowedIpRange(branch.getAllowedIpRange());
        config.setTvConfigJson(branch.getTvConfigJson());
        config.setOpeningTime(branch.getOpeningTime());
        config.setClosingTime(branch.getClosingTime());
        config.setWorkingDays(branch.getWorkingDays());

        model.addAttribute("branch", branch);
        model.addAttribute("rooms", rooms);
        model.addAttribute("chairsByRoom", chairsByRoom);
        model.addAttribute("unassignedChairs", unassignedChairs);
        model.addAttribute("totalChairs", chairs.size());
        model.addAttribute("availableChairs", chairService.countByStatus(id, ChairStatus.AVAILABLE));
        model.addAttribute("inUseChairs", chairService.countByStatus(id, ChairStatus.IN_USE));
        model.addAttribute("cleaningChairs", chairService.countByStatus(id, ChairStatus.CLEANING));
        model.addAttribute("maintenanceChairs", chairService.countByStatus(id, ChairStatus.MAINTENANCE));
        model.addAttribute("config", config);
        model.addAttribute("roomTypes", RoomType.values());
        model.addAttribute("roomStatuses", RoomStatus.values());
        model.addAttribute("chairStatuses", ChairStatus.values());

        return "branch/detail";
    }

    // POST /admin/branches/{id}/update - Cập nhật thông tin cơ bản.
    @PostMapping("/{id}/update")
    public String updateBranch(
            @PathVariable Long id,
            @ModelAttribute Branch branch,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã cập nhật thông tin chi nhánh.",
                () -> branchService.updateBranch(id, branch));
    }

    // POST /admin/branches/{id}/config - Cấu hình IP Wifi, TV, giờ làm việc.
    @PostMapping("/{id}/config")
    public String updateConfig(
            @PathVariable Long id,
            @ModelAttribute BranchConfigDTO config,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã lưu cấu hình chi nhánh.",
                () -> branchService.updateBranchConfig(id, config));
    }

    // POST /admin/branches/{id}/deactivate - Ngừng hoạt động, không xóa bản ghi.
    @PostMapping("/{id}/deactivate")
    public String deactivateBranch(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            branchService.deactivateBranch(id);
            redirectAttributes.addFlashAttribute("successMessage", "Đã ngừng hoạt động chi nhánh.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/branches";
    }

    // POST /admin/branches/{id}/activate - Kích hoạt lại chi nhánh.
    @PostMapping("/{id}/activate")
    public String activateBranch(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            branchService.activateBranch(id);
            redirectAttributes.addFlashAttribute("successMessage", "Đã kích hoạt chi nhánh.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/branches";
    }

    // ===================== PHÒNG KHÁM =====================

    @PostMapping("/{id}/rooms")
    public String createRoom(
            @PathVariable Long id,
            @ModelAttribute RoomDTO room,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã thêm phòng khám.",
                () -> roomService.createRoom(id, room));
    }

    @PostMapping("/{id}/rooms/{roomId}/update")
    public String updateRoom(
            @PathVariable Long id,
            @PathVariable Long roomId,
            @ModelAttribute RoomDTO room,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã cập nhật phòng khám.",
                () -> roomService.updateRoom(id, roomId, room));
    }

    @PostMapping("/{id}/rooms/{roomId}/delete")
    public String deleteRoom(
            @PathVariable Long id,
            @PathVariable Long roomId,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã xóa phòng khám.",
                () -> roomService.deleteRoom(id, roomId));
    }

    // ===================== GHẾ NHA =====================

    @PostMapping("/{id}/chairs")
    public String createChair(
            @PathVariable Long id,
            @ModelAttribute DentalChairDTO chair,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã thêm ghế nha.",
                () -> chairService.createChair(id, chair));
    }

    @PostMapping("/{id}/chairs/{chairId}/update")
    public String updateChair(
            @PathVariable Long id,
            @PathVariable Long chairId,
            @ModelAttribute DentalChairDTO chair,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã cập nhật ghế nha.",
                () -> chairService.updateChair(id, chairId, chair));
    }

    // Đổi nhanh trạng thái ghế: AVAILABLE / IN_USE / CLEANING / MAINTENANCE.
    @PostMapping("/{id}/chairs/{chairId}/status")
    public String updateChairStatus(
            @PathVariable Long id,
            @PathVariable Long chairId,
            @RequestParam ChairStatus status,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã đổi trạng thái ghế.",
                () -> chairService.updateChairStatus(id, chairId, status));
    }

    @PostMapping("/{id}/chairs/{chairId}/delete")
    public String deleteChair(
            @PathVariable Long id,
            @PathVariable Long chairId,
            RedirectAttributes redirectAttributes) {

        return handle(id, redirectAttributes, "Đã xóa ghế nha.",
                () -> chairService.deleteChair(id, chairId));
    }

    // Chạy thao tác, gắn thông báo thành công/lỗi rồi quay lại trang chi tiết chi nhánh.
    private String handle(Long branchId, RedirectAttributes redirectAttributes,
                          String successMessage, Runnable action) {
        try {
            action.run();
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/branches/" + branchId;
    }
}
