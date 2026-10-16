package com.cvdcms.controller;

import com.cvdcms.DTO.AccountCreateDTO;
import com.cvdcms.DTO.AccountUpdateDTO;
import com.cvdcms.DTO.RoleUpdateDTO;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Role;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.service.AccountService;

import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Set;

import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final RoleRepository roleRepository;

    public AccountController(
            AccountService accountService,
            RoleRepository roleRepository
    ) {
        this.accountService = accountService;
        this.roleRepository = roleRepository;
    }


    // =========================================================
    // 1. DANH SÁCH ACCOUNT
    // =========================================================


    @GetMapping
    public String listAccounts(

            @RequestParam(defaultValue = "")
            String keyword,

            @RequestParam(required = false)
            Integer roleId,

            @RequestParam(required = false)
            Boolean active,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "10")
            int size,

            @RequestParam(defaultValue = "createdDate")
            String sort,

            @RequestParam(defaultValue = "desc")
            String direction,

            HttpSession session,

            Model model
    ) {

        User loggedInUser = (User) session.getAttribute("loggedInUser");

// Chưa đăng nhập thì chuyển về trang đăng nhập.
        if (loggedInUser == null) {
            return "redirect:/login";
        }

// Lấy các vai trò của tài khoản đang đăng nhập.
        Set<Role> userRoles = loggedInUser.getRoles();

// Kiểm tra tài khoản có quyền ADMIN hoặc LE_TAN không.
        boolean hasPermission = userRoles != null
                && userRoles.stream()
                .filter(Objects::nonNull)
                .map(Role::getRoleName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch(roleName ->
                        roleName.equals("ADMIN")
                                || roleName.equals("ROLE_ADMIN")
                                || roleName.equals("LE_TAN")
                                || roleName.equals("ROLE_LE_TAN")
                );

// Không đủ quyền thì trả về lỗi 403.
        if (!hasPermission) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Bạn không có quyền quản lý tài khoản."
            );
        }

        // Không cho page âm
        if (page < 0) {
            page = 0;
        }

        // Giới hạn size
        if (size != 10 &&
                size != 20 &&
                size != 50) {

            size = 10;
        }


        // =====================================================
        // WHITELIST SORT
        // =====================================================

        String sortField;

        switch (sort) {
            case "username":
                sortField = "username";
                break;

            case "email":
                sortField = "email";
                break;

            case "createdDate":
                sortField = "createdDate";
                break;

            case "isActive":
                sortField = "isActive";
                break;

            default:
                sortField = "createdDate";
        }

        Sort.Direction sortDirection =
                "asc".equalsIgnoreCase(direction)
                        ? Sort.Direction.ASC
                        : Sort.Direction.DESC;


        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(sortDirection, sortField)
        );


        // =====================================================
        // SEARCH
        // =====================================================

        Page<User> accountPage =
                accountService.searchAccounts(
                        keyword,
                        roleId,
                        active,
                        pageable
                );


        // =====================================================
        // DATA CHO THYMELEAF
        // =====================================================

        model.addAttribute(
                "accounts",
                accountPage.getContent()
        );

        model.addAttribute(
                "currentPage",
                accountPage.getNumber()
        );

        model.addAttribute(
                "totalPages",
                accountPage.getTotalPages()
        );

        model.addAttribute(
                "totalItems",
                accountPage.getTotalElements()
        );

        model.addAttribute(
                "keyword",
                keyword
        );

        model.addAttribute(
                "selectedRole",
                roleId
        );

        model.addAttribute(
                "selectedActive",
                active
        );

        model.addAttribute(
                "sort",
                sort
        );

        model.addAttribute(
                "direction",
                direction
        );

        model.addAttribute(
                "size",
                size
        );


        // Danh sách role
        model.addAttribute(
                "roles",
                roleRepository.findAll()
        );


        return "account/list";
    }


    // =========================================================
    // 2. FORM CREATE
    // =========================================================

    @GetMapping("/create")
    public String showCreateForm(HttpSession session, Model model) {

        // Lấy user đang đăng nhập để kiểm tra quyền admin
        User currentUser = (User) session.getAttribute("loggedInUser");
        model.addAttribute("isAdmin", isAdmin(currentUser));

        model.addAttribute(
                "accountCreateDTO",
                new AccountCreateDTO()
        );

        model.addAttribute(
                "roles",
                roleRepository.findAll()
        );

        return "account/create";
    }


    // =========================================================
    // 3. CREATE
    // =========================================================
    @PostMapping("/create")
    public String createAccount(
            @ModelAttribute AccountCreateDTO dto,
            HttpSession session,
            Model model) {

        User currentUser =
                (User) session.getAttribute("loggedInUser");

        if (currentUser == null
                || !Boolean.TRUE.equals(currentUser.getIsActive())) {
            return "redirect:/login";
        }

        try {
            // Tìm role được chọn trong form.
            Role selectedRole = roleRepository.findAll().stream()
                    .filter(role -> dto.getRoleId() != null
                            && role.getRoleId() != null
                            && role.getRoleId().longValue()
                            == dto.getRoleId().longValue())
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Role không tồn tại."
                    ));

            // Lễ tân không được tạo tài khoản ADMIN.
            String roleName = selectedRole.getRoleName();

            boolean isAdminRole = roleName != null
                    && (roleName.equalsIgnoreCase("ADMIN")
                    || roleName.equalsIgnoreCase("ROLE_ADMIN"));

            if (isAdminRole && !isAdmin(currentUser)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Lễ tân không được phép tạo tài khoản ADMIN."
                );
            }

            accountService.createAccount(dto);

            return "redirect:/accounts?success=created";

        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("accountCreateDTO", dto);
            model.addAttribute("roles", roleRepository.findAll());
            model.addAttribute("isAdmin", isAdmin(currentUser)); // Thêm dòng này

            return "account/create";
        }
    }


    // =========================================================
    // 4. DETAIL
    // =========================================================

    @GetMapping("/{id}")
    public String detailAccount(

            @PathVariable("id")
            Long id,

            Model model
    ) {

        User user =
                accountService.getAccountById(id);

        model.addAttribute(
                "account",
                user
        );

        model.addAttribute(
                "roles",
                roleRepository.findAll()
        );

        return "account/detail";
    }


    // =========================================================
    // 5. FORM EDIT
    // =========================================================

    @GetMapping("/{id}/edit")
    public String showEditForm(

            @PathVariable("id")
            Long id,

            Model model
    ) {

        User user =
                accountService.getAccountById(id);

        AccountUpdateDTO dto =
                new AccountUpdateDTO();


        dto.setEmail(user.getEmail());
        dto.setPhoneNumber(user.getPhoneNumber());

        model.addAttribute(
                "account",
                user
        );

        model.addAttribute(
                "accountUpdateDTO",
                dto
        );

        return "account/edit";
    }



    // =========================================================
    // 6. UPDATE
    // =========================================================

    @PostMapping("/{id}/edit")
    public String updateAccount(

            @PathVariable("id")
            Long id,

            @ModelAttribute AccountUpdateDTO dto,

            Model model
    ) {

        try {

            accountService.updateAccount(
                    id,
                    dto
            );

            return "redirect:/accounts/"
                    + id
                    + "?success=updated";

        } catch (RuntimeException e) {

            model.addAttribute(
                    "error",
                    e.getMessage()
            );

            model.addAttribute(
                    "account",
                    accountService.getAccountById(id)
            );

            model.addAttribute(
                    "accountUpdateDTO",
                    dto
            );

            return "account/edit";
        }
    }


    // =========================================================
    // 7. CHANGE ROLE
    // =========================================================

    @PostMapping("/{id}/role")
    public String changeRole(
            @PathVariable Long id,
            @ModelAttribute RoleUpdateDTO dto,
            HttpSession session) {

        User currentUser =
                (User) session.getAttribute("loggedInUser");

        if (currentUser == null
                || !Boolean.TRUE.equals(currentUser.getIsActive())) {
            return "redirect:/login";
        }

        Role selectedRole = roleRepository.findAll().stream()
                .filter(role -> dto.getRoleId() != null
                        && role.getRoleId() != null
                        && role.getRoleId().longValue()
                        == dto.getRoleId().longValue())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Role không tồn tại."
                ));

        String roleName = selectedRole.getRoleName();

        boolean isAdminRole = roleName != null
                && (roleName.equalsIgnoreCase("ADMIN")
                || roleName.equalsIgnoreCase("ROLE_ADMIN"));

        if (isAdminRole && !isAdmin(currentUser)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Lễ tân không được phép cấp quyền ADMIN."
            );
        }

        accountService.changeRole(id, dto.getRoleId());

        return "redirect:/accounts";
    }


    // =========================================================
    // 8. LOCK / UNLOCK
    // =========================================================

    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(

            @PathVariable("id")
            Long id
    ) {

        accountService.toggleStatus(id);

        return "redirect:/accounts/"
                + id
                + "?success=status";
    }


    /**
     * Kiểm tra tài khoản hiện tại có quyền ADMIN hay không.
     */
    private boolean isAdmin(User user) {
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            return false;
        }

        if (user.getRoles() == null) {
            return false;
        }

        return user.getRoles().stream()
                .filter(Objects::nonNull)
                .map(Role::getRoleName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(name ->
                        name.equalsIgnoreCase("ADMIN")
                                || name.equalsIgnoreCase("ROLE_ADMIN"));
    }

    /**
     * Chỉ cho phép ADMIN thực hiện thao tác quản trị.
     */
    private void requireAdmin(HttpSession session) {
        User currentUser = (User) session.getAttribute("loggedInUser");

        if (!isAdmin(currentUser)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không có quyền cấp role ADMIN."
            );
        }
    }



}