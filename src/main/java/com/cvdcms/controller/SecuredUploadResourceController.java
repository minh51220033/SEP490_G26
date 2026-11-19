package com.cvdcms.controller;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.ClinicalPhoto;
import com.cvdcms.entity.NanopixXraySyncLog;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.ClinicalPhotoRepository;
import com.cvdcms.repository.NanopixXraySyncLogRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller kiểm soát truy cập và phục vụ an toàn các tệp tin y tế / tài chính nhạy cảm.
 * Ngăn chặn tuyệt đối việc truy cập ẩn danh hoặc sai phân quyền vai trò / cơ sở vào phim X-quang,
 * ảnh lâm sàng, hóa đơn, chữ ký điện tử.
 */
@Slf4j
@RestController
@RequestMapping("/uploads")
public class SecuredUploadResourceController {

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "nanopix", "clinical_photos", "invoices", "signatures", "labo"
    );

    private static final Set<String> XRAY_AUTHORIZED_STAFF_ROLES = Set.of(
            "ADMIN", "ROLE_ADMIN",
            "BAC_SI", "ROLE_BAC_SI",
            "LE_TAN", "ROLE_LE_TAN",
            "KY_THUAT_VIEN", "ROLE_KY_THUAT_VIEN",
            "DIEU_DUONG", "ROLE_DIEU_DUONG"
    );

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    @Autowired(required = false)
    private UserRepository userRepository;

    @Autowired(required = false)
    private NanopixXraySyncLogRepository nanopixRepository;

    @Autowired(required = false)
    private ClinicalPhotoRepository clinicalPhotoRepository;

    @Autowired(required = false)
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public SecuredUploadResourceController() {
    }

    public SecuredUploadResourceController(UserRepository userRepository, NanopixXraySyncLogRepository nanopixRepository) {
        this.userRepository = userRepository;
        this.nanopixRepository = nanopixRepository;
    }

    @GetMapping("/{category:nanopix|clinical_photos|invoices|signatures|labo}/{fileName:.+}")
    public ResponseEntity<Resource> getSecuredFile(
            @PathVariable("category") String category,
            @PathVariable("fileName") String fileName,
            HttpSession session
    ) {
        // 1. Chỉ phục vụ các danh mục được bảo vệ (các danh mục công khai avatars, news, services do WebMvc handler phục vụ)
        if (!ALLOWED_CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thư mục lưu trữ yêu cầu.");
        }

        // 2. Kiểm tra xác thực phiên đăng nhập (Authentication Check)
        User currentUser = null;
        if (session != null) {
            Object loggedIn = session.getAttribute("loggedInUser");
            if (loggedIn instanceof User u && u.getUserId() != null) {
                currentUser = u;
            } else {
                Object userObj = session.getAttribute("user");
                if (userObj instanceof User u && u.getUserId() != null) {
                    currentUser = u;
                }
            }
        }

        if (currentUser == null) {
            log.warn("Từ chối truy cập ẩn danh vào tệp tin y tế/tài chính nhạy cảm: /uploads/{}/{}", category, fileName);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập hệ thống để xem tài liệu này.");
        }

        // 3. Phòng chống Path Traversal Attack
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\") || fileName.contains("%")) {
            log.warn("Phát hiện nguy cơ Path Traversal từ người dùng {}: {}", currentUser.getUserId(), fileName);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên tệp tin không hợp lệ.");
        }

        // 4. Kiểm tra phân quyền theo vai trò và cơ sở
        authorizeCategoryAccess(category, currentUser, fileName);

        // 5. Định vị tệp trên đĩa cứng & kiểm tra symbolic link traversal
        Path baseDir = Paths.get(uploadDir, category).toAbsolutePath().normalize();
        Path filePath = baseDir.resolve(fileName).normalize();

        if (!filePath.startsWith(baseDir) || !Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp tin.");
        }

        try {
            Path realBaseDir = baseDir.toRealPath();
            Path realFilePath = filePath.toRealPath();
            if (!realFilePath.startsWith(realBaseDir)) {
                log.warn("Phát hiện truy cập ngoài thư mục cơ sở thông qua symbolic link: {}", filePath);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên tệp tin không hợp lệ.");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp tin.");
        }

        // 6. Xác định Content-Type & trả về Resource an toàn
        try {
            Resource resource = new UrlResource(filePath.toUri());
            String mimeType = Files.probeContentType(filePath);
            if (mimeType == null) {
                mimeType = MediaTypeFactory.getMediaType(fileName)
                        .map(MediaType::toString)
                        .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filePath.getFileName().toString() + "\"")
                    .body(resource);
        } catch (IOException e) {
            log.error("Lỗi khi đọc tệp tin: {}", filePath, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể đọc tệp tin.");
        }
    }

    private void authorizeCategoryAccess(String category, User currentUser, String fileName) {
        if ("nanopix".equals(category)) {
            authorizeNanopixFileAccess(currentUser, fileName);
            return;
        }
        if ("clinical_photos".equals(category)) {
            authorizeClinicalPhotoAccess(currentUser, fileName);
            return;
        }

        User effectiveUser = currentUser;
        if (userRepository != null && currentUser.getUserId() != null) {
            effectiveUser = userRepository.findById(currentUser.getUserId()).orElse(currentUser);
        }

        Set<String> roleNames = extractRoleNames(effectiveUser);

        boolean isAdmin = roleNames.contains("ADMIN") || roleNames.contains("ROLE_ADMIN");
        if (isAdmin) {
            return; // Admin có toàn quyền quản trị
        }

        boolean isPatient = roleNames.contains("BENH_NHAN") || roleNames.contains("ROLE_BENH_NHAN");
        boolean isAuthorizedStaff = (!isPatient && !roleNames.isEmpty())
                || roleNames.stream().anyMatch(XRAY_AUTHORIZED_STAFF_ROLES::contains)
                || roleNames.contains("KE_TOAN") || roleNames.contains("ROLE_KE_TOAN")
                || roleNames.contains("QUAN_LY_KHO") || roleNames.contains("ROLE_QUAN_LY_KHO");

        if ("signatures".equals(category) || "labo".equals(category)) {
            if (!isAuthorizedStaff) {
                log.warn("Từ chối người dùng không phải nhân viên phòng khám (User {}) xem tài liệu nội bộ {}/{}",
                        effectiveUser.getUserId(), category, fileName);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập tài liệu nội bộ này.");
            }
        } else if ("invoices".equals(category)) {
            if (!isAuthorizedStaff && !isPatient) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem hóa đơn tài chính.");
            }
        }
    }

    private void authorizeClinicalPhotoAccess(User sessionUser, String fileName) {
        User effectiveUser = sessionUser;
        if (userRepository != null && sessionUser.getUserId() != null) {
            effectiveUser = userRepository.findById(sessionUser.getUserId()).orElse(sessionUser);
        }

        Set<String> roleNames = extractRoleNames(effectiveUser);
        boolean isAdmin = roleNames.contains("ADMIN") || roleNames.contains("ROLE_ADMIN");
        if (isAdmin) {
            return;
        }

        boolean isPatient = roleNames.contains("BENH_NHAN") || roleNames.contains("ROLE_BENH_NHAN");
        boolean isClinicalOrReceptionStaff = roleNames.stream().anyMatch(XRAY_AUTHORIZED_STAFF_ROLES::contains)
                || (!isPatient && userRepository != null && effectiveUser.getUserId() != null
                && (userRepository.hasActivePermission(effectiveUser.getUserId(), "CLINICAL_PHOTOS")
                || userRepository.hasActivePermission(effectiveUser.getUserId(), "MEDICAL_RECORD_VIEW")
                || userRepository.hasActivePermission(effectiveUser.getUserId(), "PATIENT_PROFILE_MANAGE")));

        if (!isClinicalOrReceptionStaff && !isPatient) {
            log.warn("Từ chối vai trò {} (User {}) truy cập ảnh lâm sàng: {}", roleNames, effectiveUser.getUserId(), fileName);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem ảnh lâm sàng.");
        }

        String imageUrl = "/uploads/clinical_photos/" + fileName;
        ClinicalPhoto photo = findClinicalPhotoByImageUrl(imageUrl);

        if (isPatient && !isClinicalOrReceptionStaff) {
            if (photo == null || photo.getPatientProfile() == null || photo.getPatientProfile().getUser() == null
                    || !effectiveUser.getUserId().equals(photo.getPatientProfile().getUser().getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Bệnh nhân chỉ được xem ảnh lâm sàng thuộc hồ sơ cá nhân của mình.");
            }
            return;
        }

        // Kiểm tra giới hạn cơ sở (Branch isolation) đối với nhân sự không phải Admin
        Set<Long> userBranchIds = resolveUserBranchIds(effectiveUser);
        if (photo != null) {
            Branch photoBranch = (photo.getVisit() != null && photo.getVisit().getBranch() != null)
                    ? photo.getVisit().getBranch()
                    : (photo.getPatientProfile() != null ? photo.getPatientProfile().getPreferredBranch() : null);
            if (photoBranch != null && photoBranch.getBranchId() != null
                    && !userBranchIds.contains(photoBranch.getBranchId())) {
                log.warn("Từ chối nhân sự cơ sở {} (User {}) xem ảnh lâm sàng thuộc cơ sở {}: {}",
                        userBranchIds, effectiveUser.getUserId(), photoBranch.getBranchId(), fileName);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Bạn không có quyền xem ảnh lâm sàng thuộc cơ sở khác.");
            }
        } else if (clinicalPhotoRepository != null || entityManager != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Không thể xác minh quyền truy cập ảnh lâm sàng này.");
        }
    }

    private ClinicalPhoto findClinicalPhotoByImageUrl(String imageUrl) {
        if (entityManager != null) {
            List<ClinicalPhoto> results = entityManager.createQuery(
                            "SELECT p FROM ClinicalPhoto p " +
                                    "LEFT JOIN FETCH p.patientProfile pp " +
                                    "LEFT JOIN FETCH pp.user " +
                                    "LEFT JOIN FETCH pp.preferredBranch " +
                                    "LEFT JOIN FETCH p.visit v " +
                                    "LEFT JOIN FETCH v.branch " +
                                    "WHERE p.imageUrl = :imageUrl " +
                                    "ORDER BY p.capturedAt DESC", ClinicalPhoto.class)
                    .setParameter("imageUrl", imageUrl)
                    .setMaxResults(1)
                    .getResultList();
            if (!results.isEmpty()) {
                return results.get(0);
            }
        }
        if (clinicalPhotoRepository != null) {
            return clinicalPhotoRepository.findAll().stream()
                    .filter(p -> imageUrl.equals(p.getImageUrl()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private Set<String> extractRoleNames(User user) {
        return user != null && user.getRoles() != null
                ? user.getRoles().stream()
                    .filter(r -> r != null && r.getRoleName() != null)
                    .map(r -> r.getRoleName().trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet())
                : Set.of();
    }

    private Set<Long> resolveUserBranchIds(User effectiveUser) {
        Set<Long> ids = new HashSet<>();
        if (effectiveUser == null) {
            return ids;
        }
        if (effectiveUser.getBranch() != null && effectiveUser.getBranch().getBranchId() != null) {
            ids.add(effectiveUser.getBranch().getBranchId());
        }
        if (staffBranchAssignmentRepository != null && effectiveUser.getUserId() != null) {
            List<Long> assigned = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(
                    effectiveUser.getUserId(), AssignmentStatus.ACTIVE);
            if (assigned != null) {
                for (Long id : assigned) {
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    private void authorizeNanopixFileAccess(User sessionUser, String fileName) {
        User effectiveUser = sessionUser;
        if (userRepository != null && sessionUser.getUserId() != null) {
            effectiveUser = userRepository.findById(sessionUser.getUserId()).orElse(sessionUser);
        }

        Set<String> roleNames = extractRoleNames(effectiveUser);

        boolean isAdmin = roleNames.contains("ADMIN") || roleNames.contains("ROLE_ADMIN");
        boolean isPatient = roleNames.contains("BENH_NHAN") || roleNames.contains("ROLE_BENH_NHAN");
        boolean isAuthorizedStaff = (!isPatient && !roleNames.isEmpty())
                || roleNames.stream().anyMatch(XRAY_AUTHORIZED_STAFF_ROLES::contains);

        if (!isAuthorizedStaff && !isPatient && !roleNames.isEmpty()) {
            log.warn("Từ chối vai trò {} (User {}) truy cập phim X-quang: {}", roleNames, effectiveUser.getUserId(), fileName);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vai trò của bạn không có quyền xem phim X-quang.");
        }

        String imageUrl = "/uploads/nanopix/" + fileName;
        NanopixXraySyncLog syncLog = nanopixRepository != null
                ? nanopixRepository.findFirstByImageUrlOrderByCapturedAtDesc(imageUrl).orElse(null)
                : null;

        if (isPatient && !isAuthorizedStaff) {
            if (syncLog == null || syncLog.getMatchedProfile() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập phim X-quang này.");
            }
            PatientProfile matchedProfile = syncLog.getMatchedProfile();
            if (matchedProfile.getUser() == null || ! effectiveUser.getUserId().equals(matchedProfile.getUser().getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bệnh nhân chỉ được xem phim X-quang thuộc hồ sơ cá nhân của mình.");
            }
            return;
        }

        // Kiểm tra giới hạn cơ sở (Branch isolation) đối với nhân sự không phải Admin
        if (!isAdmin && syncLog != null) {
            Branch xrayBranch = syncLog.getBranch() != null
                    ? syncLog.getBranch()
                    : (syncLog.getMatchedVisit() != null ? syncLog.getMatchedVisit().getBranch() : null);
            Set<Long> userBranchIds = resolveUserBranchIds(effectiveUser);
            if (xrayBranch != null && xrayBranch.getBranchId() != null
                    && !userBranchIds.contains(xrayBranch.getBranchId())) {
                log.warn("Từ chối nhân sự cơ sở {} (User {}) xem phim X-quang thuộc cơ sở {}: {}",
                        userBranchIds, effectiveUser.getUserId(), xrayBranch.getBranchId(), fileName);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem phim X-quang thuộc cơ sở khác.");
            }
        }
    }
}
