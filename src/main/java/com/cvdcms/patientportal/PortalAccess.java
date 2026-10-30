package com.cvdcms.patientportal;

import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PortalAccess {
    private final UserRepository users;
    private final PatientProfileRepository profiles;
    private final AccountPatientProfileRepository links;

    public PortalAccess(UserRepository users, PatientProfileRepository profiles,
                        AccountPatientProfileRepository links) {
        this.users = users;
        this.profiles = profiles;
        this.links = links;
    }

    @Transactional(readOnly = true)
    public User requireUser(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User sessionUser) || sessionUser.getUserId() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        User user = users.findById(sessionUser.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || user.getUserType() != UserType.PATIENT
                || user.getRoles().stream().noneMatch(this::isPatientRole))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return user;
    }

    private boolean isPatientRole(Role role) {
        return role != null && Boolean.TRUE.equals(role.getIsActive()) && role.getRoleName() != null
                && ("BENH_NHAN".equalsIgnoreCase(role.getRoleName())
                    || "ROLE_BENH_NHAN".equalsIgnoreCase(role.getRoleName()));
    }

    public PatientProfile requireProfile(User user, Long profileId) {
        PatientProfile profile = profiles.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        AccountPatientProfile link = links.findByProfile_ProfileId(profileId).orElse(null);
        Long managerId = link == null
                ? profile.getUser() == null ? null : profile.getUser().getUserId()
                : link.getAccount().getUserId();
        if (!user.getUserId().equals(managerId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return profile;
    }
}
