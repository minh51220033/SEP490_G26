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

        User loggedInUser =
                (User) session.getAttribute("loggedInUser");

        if (loggedInUser == null) {
            return "redirect:/login";
        }

        if (loggedInUser.getRole() == null) {
            return "error/403";
        }

        String roleName =
                loggedInUser.getRole().getRoleName();

        if (!"ADMIN".equals(roleName)
                && !"LE_TAN".equals(roleName)) {

            return "error/403";

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

            case "fullName":
                sortField = "fullName";
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
    public String showCreateForm(Model model) {

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

            Model model
    ) {

        try {

            accountService.createAccount(dto);

            return "redirect:/accounts?success=created";

        } catch (RuntimeException e) {

            model.addAttribute(
                    "error",
                    e.getMessage()
            );

            model.addAttribute(
                    "accountCreateDTO",
                    dto
            );

            model.addAttribute(
                    "roles",
                    roleRepository.findAll()
            );

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

        dto.setFullName(user.getFullName());
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

            @PathVariable("id")
            Long id,

            @ModelAttribute RoleUpdateDTO dto
    ) {

        accountService.changeRole(
                id,
                dto.getRoleId()
        );

        return "redirect:/accounts/"
                + id
                + "?success=role";
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
}