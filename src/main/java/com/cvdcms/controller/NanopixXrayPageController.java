package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Page Controller phục vụ giao diện Web cho chức năng Quản lý kết quả phim X-quang NanoPix (FT12):
 * - URL: /xray hoặc /doctor/xray
 * - Phân quyền: ADMIN, BAC_SI, LE_TAN
 */
@Controller
@RequiredArgsConstructor
public class NanopixXrayPageController {

    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final BranchRepository branchRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    @GetMapping({"/xray", "/doctor/xray"})
    public String xrayPage() {
        return "redirect:/reception/patients";
    }

    private User getSessionUser(HttpSession session) {
        if (session == null) return null;
        Object obj = session.getAttribute("loggedInUser");
        if (obj instanceof User u) return u;
        obj = session.getAttribute("user");
        if (obj instanceof User u) return u;
        return null;
    }
}
