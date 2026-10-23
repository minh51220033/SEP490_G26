package com.cvdcms.patientportal;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.patient.PatientAuditRepository;
import com.cvdcms.repository.*;
import com.cvdcms.service.EmailService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional
public class PatientTransferService {
    private final PortalAccess access;
    private final PatientTransferRequestRepository requests;
    private final AccountPatientProfileRepository links;
    private final PatientProfileRepository profiles;
    private final UserRepository users;
    private final RoleRepository roles;
    private final EmailService email;
    private final PasswordEncoder encoder;
    private final PatientAuditRepository audit;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;
    private final SecureRandom random = new SecureRandom();

    public PatientTransferService(PortalAccess access, PatientTransferRequestRepository requests,
            AccountPatientProfileRepository links, PatientProfileRepository profiles,
            UserRepository users, RoleRepository roles, EmailService email,
            PasswordEncoder encoder, PatientAuditRepository audit, ObjectMapper mapper,
            EntityManager entityManager) {
        this.access = access; this.requests = requests; this.links = links;
        this.profiles = profiles; this.users = users; this.roles = roles;
        this.email = email; this.encoder = encoder; this.audit = audit;
        this.mapper = mapper; this.entityManager = entityManager;
    }

    public TransferStarted start(HttpSession session, Long profileId, TransferStartForm form) {
        User owner = access.requireUser(session);
        PatientProfile profile = entityManager.find(PatientProfile.class, profileId, LockModeType.PESSIMISTIC_WRITE);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        access.requireProfile(owner, profileId);
        if (Boolean.TRUE.equals(profile.getIsDefault()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ có thể tách hồ sơ người phụ thuộc.");
        String targetEmail = form.targetEmail().trim().toLowerCase(Locale.ROOT);
        if (targetEmail.equalsIgnoreCase(owner.getEmail()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản nhận phải khác tài khoản hiện tại.");
        User target = users.findByEmail(targetEmail).orElse(null);
        if (target != null && !validPatientAccount(target))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản nhận không hợp lệ.");
        PatientTransferRequest prior = requests
                .findFirstByProfile_ProfileIdAndConsumedAtIsNullOrderByRequestIdDesc(profileId).orElse(null);
        if (prior != null && prior.getCreatedAt() != null
                && prior.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(1)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Vui lòng chờ trước khi gửi lại mã.");
        if (prior != null) { prior.setConsumedAt(LocalDateTime.now()); requests.save(prior); }

        String ownerCode = code();
        String targetCode = code();
        PatientTransferRequest request = new PatientTransferRequest();
        request.setProfile(profile); request.setFromAccount(owner);
        request.setTargetEmail(targetEmail); request.setTargetAccount(target);
        request.setOwnerCodeHash(encoder.encode(ownerCode));
        request.setTargetCodeHash(encoder.encode(targetCode));
        request.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        requests.saveAndFlush(request);

        email.sendSimpleMail(owner.getEmail(), "Xác nhận tách hồ sơ bệnh nhân",
                "Mã xác nhận của tài khoản đang quản lý: " + ownerCode + ". Có hiệu lực 10 phút.");
        email.sendSimpleMail(targetEmail, "Xác nhận nhận hồ sơ bệnh nhân",
                "Mã xác nhận của tài khoản nhận: " + targetCode + ". Có hiệu lực 10 phút.");
        return new TransferStarted(request.getRequestId(), targetEmail, target != null);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public TransferCompleted confirm(HttpSession session, Long profileId, Long requestId,
                                     TransferConfirmForm form) {
        User owner = access.requireUser(session);
        // Lock the profile and request so a concurrent confirmation cannot transfer it twice.
        PatientProfile profile = entityManager.find(PatientProfile.class, profileId, LockModeType.PESSIMISTIC_WRITE);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        access.requireProfile(owner, profileId);
        PatientTransferRequest request = requests.lockById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!request.getProfile().getProfileId().equals(profileId)
                || !request.getFromAccount().getUserId().equals(owner.getUserId())
                || request.getConsumedAt() != null || !request.getExpiresAt().isAfter(LocalDateTime.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu đã hết hiệu lực.");
        if (request.getAttemptCount() >= 5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Đã vượt số lần xác minh.");
        request.setAttemptCount(request.getAttemptCount() + 1);
        requests.saveAndFlush(request);
        if (!encoder.matches(form.ownerCode(), request.getOwnerCodeHash())
                || !encoder.matches(form.targetCode(), request.getTargetCodeHash()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã xác minh không đúng.");

        User target = request.getTargetAccount();
        if (target == null) target = createTarget(request, form);
        else target = entityManager.find(User.class, target.getUserId(), LockModeType.PESSIMISTIC_WRITE);
        if (target == null || !validPatientAccount(target)
                || !request.getTargetEmail().equalsIgnoreCase(target.getEmail()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tài khoản nhận không còn hợp lệ.");
        if (target.getUserId().equals(owner.getUserId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT);

        AccountPatientProfile link = links.findByProfile_ProfileId(profileId).orElse(null);
        if (link == null) {
            link = new AccountPatientProfile();
            link.setProfile(profile);
        }
        boolean firstProfile = !profiles.existsByUser_UserIdAndIsDefaultTrue(target.getUserId());
        link.setAccount(target);
        link.setAccessType(firstProfile ? AccountPatientProfile.AccessType.SELF
                : AccountPatientProfile.AccessType.DEPENDENT);
        links.saveAndFlush(link);
        profile.setUser(target);
        profile.setPreviousUser(owner);
        profile.setSeparatedAt(LocalDateTime.now());
        profile.setIsDefault(firstProfile);
        profiles.saveAndFlush(profile);
        request.setConsumedAt(LocalDateTime.now());
        requests.save(request);
        audit(owner, profile, target);
        return new TransferCompleted(profileId, target.getUserId(), request.getTargetEmail());
    }

    @Transactional(readOnly = true)
    public TransferStarted inspect(HttpSession session, Long profileId, Long requestId) {
        User owner = access.requireUser(session);
        access.requireProfile(owner, profileId);
        PatientTransferRequest request = requests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!request.getProfile().getProfileId().equals(profileId)
                || !request.getFromAccount().getUserId().equals(owner.getUserId())
                || request.getConsumedAt() != null || !request.getExpiresAt().isAfter(LocalDateTime.now()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new TransferStarted(requestId, request.getTargetEmail(), request.getTargetAccount() != null);
    }

    private User createTarget(PatientTransferRequest request, TransferConfirmForm form) {
        String username = form.username() == null ? "" : form.username().trim();
        String phone = form.phoneNumber() == null ? "" : form.phoneNumber().trim();
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9._-]{3,49}")
                || !phone.matches("0\\d{9,10}")
                || form.password() == null || form.password().length() < 8
                || users.existsByUsername(username) || users.existsByPhoneNumber(phone)
                || users.existsByEmail(request.getTargetEmail()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin tài khoản mới không hợp lệ hoặc đã tồn tại.");
        Role patientRole = roles.findByRoleName("BENH_NHAN")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Chưa có vai trò bệnh nhân."));
        if (!Boolean.TRUE.equals(patientRole.getIsActive()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vai trò bệnh nhân đã ngừng hoạt động.");
        User target = new User();
        target.setUsername(username); target.setPhoneNumber(phone);
        target.setEmail(request.getTargetEmail());
        target.setPassword(encoder.encode(form.password()));
        target.setUserType(UserType.PATIENT);
        target.setStatus("ACTIVE"); target.setIsActive(true);
        target.getRoles().add(patientRole);
        return users.saveAndFlush(target);
    }

    private String code() { return String.format("%06d", random.nextInt(1_000_000)); }

    private boolean validPatientAccount(User user) {
        return user.getUserType() == UserType.PATIENT
                && Boolean.TRUE.equals(user.getIsActive())
                && "ACTIVE".equalsIgnoreCase(user.getStatus())
                && user.getRoles().stream().anyMatch(role -> Boolean.TRUE.equals(role.getIsActive())
                    && ("BENH_NHAN".equalsIgnoreCase(role.getRoleName())
                        || "ROLE_BENH_NHAN".equalsIgnoreCase(role.getRoleName())));
    }

    private void audit(User owner, PatientProfile profile, User target) {
        SystemAuditLog log = new SystemAuditLog();
        log.setUser(owner); log.setBranch(profile.getPreferredBranch());
        log.setAction("TRANSFER_ACCOUNT"); log.setEntityTable("patient_profiles");
        log.setEntityId(profile.getProfileId());
        try {
            log.setOldValuesJson(mapper.writeValueAsString(Map.of("accountId", owner.getUserId())));
            log.setNewValuesJson(mapper.writeValueAsString(Map.of("accountId", target.getUserId(),
                    "profileId", profile.getProfileId())));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Không thể ghi audit.", ex); }
        audit.save(log);
    }

    public record TransferStarted(Long requestId, String targetEmail, boolean existingAccount) {}
    public record TransferCompleted(Long profileId, Long targetAccountId, String targetEmail) {}
}
