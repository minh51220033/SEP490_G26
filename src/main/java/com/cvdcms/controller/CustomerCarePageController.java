package com.cvdcms.controller;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.CustomerCareService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controller MVC điều hướng giao diện chính cho phân hệ Quản lý Chăm sóc khách hàng (CSKH - FT44 / UC-26).
 *
 * <p><b>Chức năng:</b> Tiếp nhận yêu cầu truy cập trang danh sách/bảng điều khiển CSKH, xác thực phiên đăng nhập,
 * kiểm tra vai trò hợp lệ (chỉ cho phép {@code ADMIN}, {@code LE_TAN}, {@code BAC_SI}; các vai trò khác trả về 403 FORBIDDEN)
 * và nạp thông tin phân quyền + tên hiển thị lên Model cho giao diện Thymeleaf {@code customer-care/list.html}.</p>
 *
 * <p><b>Được gọi từ:</b> Menu Sidebar (mục Chăm sóc khách hàng) hoặc điều hướng trực tiếp qua các URL
 * {@code /customer-care}, {@code /customer-care/tasks}, {@code /cskh}.</p>
 */
@Controller
public class CustomerCarePageController {

    private final CustomerCareService customerCareService;
    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;

    /**
     * Khởi tạo Controller với các thành phần nghiệp vụ và repository cần thiết.
     *
     * @param customerCareService    Service nghiệp vụ CSKH (dùng để kiểm tra vai trò truy cập)
     * @param userRepository         Repository truy vấn thông tin tài khoản và danh sách vai trò
     * @param staffProfileRepository Repository truy vấn hồ sơ nhân viên để lấy họ tên hiển thị
     */
    public CustomerCarePageController(
            CustomerCareService customerCareService,
            UserRepository userRepository,
            StaffProfileRepository staffProfileRepository) {
        this.customerCareService = customerCareService;
        this.userRepository = userRepository;
        this.staffProfileRepository = staffProfileRepository;
    }

    /**
     * Mở trang Quản lý Chăm sóc khách hàng.
     *
     * <p><b>Đầu vào:</b> GET {@code /customer-care}, {@code /customer-care/tasks}, hoặc {@code /cskh};
     * phiên đăng nhập {@code session} chứa thuộc tính {@code loggedInUser}.</p>
     * <p><b>Xử lý thực tế:</b>
     * <ul>
     *   <li>Kiểm tra người dùng đã đăng nhập chưa; nếu chưa thì chuyển hướng về {@code /login}.</li>
     *   <li>Gọi {@link CustomerCareService#requireCareRole(Long)} để xác định vai trò CSKH hợp lệ;
     *       nếu không đủ quyền thì trả mã lỗi HTTP (403) qua {@code response.sendError}.</li>
     *   <li>Tổng hợp danh sách {@code roleNames} (in hoa) và {@code displayName} (ưu tiên họ tên trong {@code StaffProfile})
     *       để sidebar và giao diện hiển thị đúng quyền hạn.</li>
     * </ul></p>
     * <p><b>Kết quả trả về:</b> Tên view {@code customer-care/list}, hoặc {@code redirect:/login}, hoặc {@code null} khi trả lỗi HTTP.</p>
     */
    @GetMapping({"/customer-care", "/customer-care/tasks", "/cskh"})
    public String customerCarePage(
            HttpSession session,
            HttpServletResponse response,
            Model model) throws IOException {

        Object loggedIn = session != null ? session.getAttribute("loggedInUser") : null;
        if (!(loggedIn instanceof User sessionUser) || sessionUser.getUserId() == null) {
            return "redirect:/login";
        }

        // Kiểm tra vai trò truy cập (chỉ ADMIN, LE_TAN, BAC_SI được phép truy cập)
        String careRole;
        try {
            careRole = customerCareService.requireCareRole(sessionUser.getUserId());
        } catch (ResponseStatusException ex) {
            response.sendError(
                    ex.getStatusCode().value(),
                    ex.getReason() != null ? ex.getReason() : "Bạn không có quyền truy cập trang Quản lý Chăm sóc khách hàng."
            );
            return null;
        }

        // Đưa thông tin roleNames và displayName vào Model để Sidebar hiển thị đúng phân quyền
        User dbUser = userRepository.findById(sessionUser.getUserId()).orElse(sessionUser);
        List<String> roleNames = new ArrayList<>();
        if (dbUser.getRoles() != null) {
            for (Role r : dbUser.getRoles()) {
                if (r.getRoleName() != null) {
                    roleNames.add(r.getRoleName().toUpperCase());
                }
            }
        }
        if (!roleNames.contains(careRole)) {
            roleNames.add(careRole);
        }

        String displayName = dbUser.getUsername();
        Optional<StaffProfile> staffOpt = staffProfileRepository.findByUser_UserId(dbUser.getUserId());
        if (staffOpt.isPresent() && staffOpt.get().getFullName() != null && !staffOpt.get().getFullName().isBlank()) {
            displayName = staffOpt.get().getFullName();
        }

        model.addAttribute("roleNames", roleNames);
        model.addAttribute("displayName", displayName);
        model.addAttribute("careRole", careRole);
        return "customer-care/list";
    }
}
