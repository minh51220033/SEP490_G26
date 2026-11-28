package com.cvdcms.security;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.CustomerCareTask;
import com.cvdcms.entity.LaboOrder;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.SystemAuditLog;
import com.cvdcms.entity.TreatmentPlan;
import com.cvdcms.entity.TreatmentPlanInstallment;
import com.cvdcms.entity.TreatmentPlanItem;
import com.cvdcms.entity.TreatmentPlanStage;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.UserType;
import com.cvdcms.repository.AppointmentRepository;
import com.cvdcms.repository.ClinicalPhotoRepository;
import com.cvdcms.repository.CustomerCareTaskRepository;
import com.cvdcms.repository.DoctorCoverGrantRepository;
import com.cvdcms.repository.ExaminationDiagnosisRepository;
import com.cvdcms.repository.LaboOrderRepository;
import com.cvdcms.repository.ParaclinicalOrderItemRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.PerformedServiceRepository;
import com.cvdcms.repository.PrescriptionRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.SystemAuditLogRepository;
import com.cvdcms.repository.TreatmentPlanInstallmentRepository;
import com.cvdcms.repository.TreatmentPlanItemRepository;
import com.cvdcms.repository.TreatmentPlanRepository;
import com.cvdcms.repository.TreatmentPlanStageRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.VisitRepository;
import com.cvdcms.repository.WorkflowExecutionRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [Phân hệ]: Phân quyền khám chữa bệnh, kiểm soát phạm vi chi nhánh & quy tắc làm thêm dịch vụ trong ca (Chương 7)
 * [Vai trò]: Component bảo mật tập trung xác thực quyền đọc/ghi của bác sĩ/nhân sự theo chi nhánh, phân công bác sĩ chính, ủy quyền khám thay (cover grant), trạng thái phiếu hàng chờ và chính sách làm thêm dịch vụ/combo trong cùng một ca khám.
 */
@Component
@RequiredArgsConstructor
public class DoctorVisitAccess {
    private final VisitRepository visits;
    private final StaffProfileRepository staff;
    private final UserRepository users;
    private final ExaminationDiagnosisRepository diagnoses;
    private final PrescriptionRepository prescriptions;
    private final PerformedServiceRepository performedServices;
    private final ParaclinicalOrderItemRepository paraclinicalItems;
    private final DoctorCoverGrantRepository coverGrants;
    private final ClinicalPhotoRepository photos;
    private final WorkflowExecutionRepository workflowExecutions;
    private final SystemAuditLogRepository audits;

    @Autowired(required = false)
    private TreatmentPlanRepository treatmentPlans;

    @Autowired(required = false)
    private TreatmentPlanStageRepository treatmentPlanStages;

    @Autowired(required = false)
    private TreatmentPlanItemRepository treatmentPlanItems;

    @Autowired(required = false)
    private LaboOrderRepository laboOrders;

    @Autowired(required = false)
    private AppointmentRepository appointments;

    @Autowired(required = false)
    private CustomerCareTaskRepository careTasks;

    @Autowired(required = false)
    private PatientProfileRepository patients;

    @Autowired(required = false)
    private StaffBranchAssignmentRepository branchAssignments;

    @Autowired(required = false)
    private com.cvdcms.service.DoctorClinicalCapabilityService capabilityService;

    @Autowired(required = false)
    private TreatmentPlanInstallmentRepository treatmentPlanInstallments;

    @Autowired(required = false)
    private com.cvdcms.repository.ClinicalQueueTicketRepository queueTickets;

    @Autowired(required = false)
    private com.cvdcms.repository.ServiceRepository serviceRepository;

    @Autowired(required = false)
    private com.cvdcms.repository.ServiceComboRepository serviceComboRepository;

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một tiến trình quy trình điều trị theo `executionId`.
     * [Mục đích]: Ủy quyền cho hàm nạp chồng `requireWorkflowExecution(session, executionId, null)` khi không truyền `activeVisitId`.
     */
    public void requireWorkflowExecution(HttpSession session, Long executionId) {
        requireWorkflowExecution(session, executionId, null);
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác tiến trình quy trình điều trị có hỗ trợ ngữ cảnh lượt khám tiếp nối (`activeVisitId`).
     * [Mục đích]: Nếu truyền `activeVisitId` thì xác thực ca đang mở khám qua `requireActiveClinicalExam` và đối chiếu khớp hồ sơ bệnh nhân; ngược lại kiểm tra quyền ghi trên ca gốc tạo tiến trình.
     */
    public void requireWorkflowExecution(HttpSession session, Long executionId, Long activeVisitId) {
        var execution = workflowExecutions.findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (activeVisitId != null) {
            Visit activeVisit = requireActiveClinicalExam(session, activeVisitId);
            if (execution.getPatientProfile() != null && activeVisit.getPatientProfile() != null
                    && !execution.getPatientProfile().getProfileId().equals(activeVisit.getPatientProfile().getProfileId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Quy trình không thuộc hồ sơ bệnh nhân của lượt khám này.");
            }
            return;
        }
        require(session, execution.getVisit().getVisitId());
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác lâm sàng của bác sĩ trên bệnh nhân dựa vào lượt khám gần nhất.
     * [Mục đích]: Tìm lượt khám mới nhất của `profileId` (ném 404 nếu chưa có) và gọi `require` để xác thực quyền phụ trách/khám thay.
     */
    public void requirePatient(HttpSession session, Long profileId) {
        var latest = visits.findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt khám của bệnh nhân."));
        require(session, latest.getVisitId());
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một ảnh chụp lâm sàng theo `photoId`.
     * [Mục đích]: Tải bản ghi `ClinicalPhoto`, yêu cầu ảnh phải gắn ca khám và kiểm tra quyền bác sĩ trên ca khám tương ứng qua `require`.
     */
    public void requirePhoto(HttpSession session, Long photoId) {
        var photo = photos.findById(photoId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (photo.getVisit() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ảnh chưa gắn ca khám.");
        require(session, photo.getVisit().getVisitId());
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một đơn thuốc theo ID.
     * [Mục đích]: Tra cứu đơn thuốc và kiểm tra quyền ghi của bác sĩ trên lượt khám gắn với đơn thuốc đó qua `require`.
     */
    public void requirePrescription(HttpSession session, Long id) {
        var item = prescriptions.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        require(session, item.getVisit().getVisitId());
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một dịch vụ đã thực hiện (`PerformedService`) theo ID.
     * [Mục đích]: Tải bản ghi `PerformedService` và xác thực quyền ghi trên lượt khám sở hữu dịch vụ qua `require`.
     */
    public void requirePerformedService(HttpSession session, Long id) {
        var item = performedServices.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        require(session, item.getVisit().getVisitId());
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một mục chỉ định cận lâm sàng theo ID.
     * [Mục đích]: Tải `ParaclinicalOrderItem` và đối chiếu quyền ghi trên lượt khám của phiếu chỉ định cha qua `require`.
     */
    public void requireParaclinicalItem(HttpSession session, Long id) {
        var item = paraclinicalItems.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        require(session, item.getOrder().getVisit().getVisitId());
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một bản ghi chẩn đoán theo `diagnosisId`.
     * [Mục đích]: Tải `ExaminationDiagnosis` và xác thực quyền ghi của bác sĩ trên ca khám gắn chẩn đoán qua `require`.
     */
    public void requireDiagnosis(HttpSession session, Long diagnosisId) {
        var diagnosis = diagnoses.findById(diagnosisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chẩn đoán."));
        require(session, diagnosis.getVisit().getVisitId());
    }

    /**
     * [Chức năng]: Xác thực quyền ghi lâm sàng của bác sĩ hiện tại đối với một ca khám (`visitId`).
     * [Mục đích]: Kiểm tra đăng nhập, bỏ qua nếu là Admin; với nhân sự khác kiểm tra đúng chi nhánh, là bác sĩ phụ trách chính hoặc có `DoctorCoverGrant` còn hiệu lực, đồng thời ghi `SystemAuditLog` (`COVERING_DOCTOR_WRITE`) khi bác sĩ khám thay gửi request ghi khác GET.
     */
    public void require(HttpSession session, Long visitId) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        var visit = visits.findById(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca khám."));
        if (users.hasActiveAdminRole(user.getUserId())) return;
        Long visitBranchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
        if (visitBranchId != null && !isBranchAllowed(user, visitBranchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập ca khám của chi nhánh khác.");
        }
        var doctor = staff.findByUserUserId(user.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không có hồ sơ bác sĩ."));
        boolean primary = visit.getPrimaryDoctor() != null &&
                doctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId());
        if (!primary && !coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                        visitId, doctor.getStaffId(), LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ca khám không được phân công cho bác sĩ hiện tại.");
        }
        if (!primary) {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes web &&
                    !"GET".equalsIgnoreCase(web.getRequest().getMethod())) {
                SystemAuditLog log = new SystemAuditLog();
                log.setUser(user); log.setBranch(visit.getBranch());
                log.setEntityTable("visits"); log.setEntityId(visitId);
                log.setAction("COVERING_DOCTOR_WRITE");
                try { log.setNewValuesJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        java.util.Map.of("method", web.getRequest().getMethod(), "uri", web.getRequest().getRequestURI()))); }
                catch (Exception ex) { throw new IllegalStateException("Không thể ghi nhật ký bác sĩ khám thay.", ex); }
                audits.save(log);
            }
        }
    }

    /**
     * [Chức năng]: Xác thực lượt khám đã được hệ thống gọi vào bàn khám và đang mở lâm sàng hợp lệ.
     * [Mục đích]: Gọi `require` kiểm tra quyền bác sĩ, chặn ca `CANCELLED` và chặn các ca có phiếu hàng chờ `EXAM` còn ở trạng thái `WAITING`/`PAUSED`/`CALLED` hoặc trạng thái `WAITING`/`WAITING_RESULT` khi chưa bấm "Vào khám".
     */
    public Visit requireActiveClinicalExam(HttpSession session, Long visitId) {
        require(session, visitId);
        Visit visit = visits.findById(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca khám."));
        if (visit.getStatus() == com.cvdcms.entity.enums.VisitStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ca khám đã bị hủy, không thể thao tác.");
        }

        boolean hasActiveDeskTicket = false;
        if (queueTickets != null) {
            var activeTicketOpt = queueTickets.findByVisitIdAndTypeOrderByDesc(visitId, com.cvdcms.entity.enums.QueueType.EXAM)
                    .stream()
                    .filter(t -> t.getStatus() != com.cvdcms.entity.enums.QueueStatus.CANCELLED)
                    .findFirst();
            if (activeTicketOpt.isPresent()) {
                var qStatus = activeTicketOpt.get().getStatus();
                if (qStatus == com.cvdcms.entity.enums.QueueStatus.WAITING
                        || qStatus == com.cvdcms.entity.enums.QueueStatus.PAUSED
                        || qStatus == com.cvdcms.entity.enums.QueueStatus.CALLED) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Lượt khám chưa bắt đầu phục vụ trên hàng chờ. Vui lòng dùng nút 'Vào khám' cho đúng lượt Đang gọi.");
                }
                hasActiveDeskTicket = qStatus == com.cvdcms.entity.enums.QueueStatus.IN_SERVICE;
            }
        }

        // Nếu ticket đang IN_SERVICE tại ghế bác sĩ thì không bị khóa bởi trạng thái trung gian WAITING_PARACLINICAL / WAITING_RESULT
        if (!hasActiveDeskTicket && (visit.getStatus() == com.cvdcms.entity.enums.VisitStatus.WAITING
                || visit.getStatus() == com.cvdcms.entity.enums.VisitStatus.WAITING_RESULT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Bệnh nhân còn đang chờ khám hoặc chưa bấm nút 'Vào khám'. Chỉ được mở và ghi điều trị cho ca hệ thống đã gọi vào khám.");
        }
        return visit;
    }

    /**
     * [Chức năng]: Lấy và xác thực người dùng đang đăng nhập từ `HttpSession`.
     * [Mục đích]: Trả về thực thể `User` từ thuộc tính `loggedInUser`; ném HTTP 401 nếu phiên rỗng hoặc chưa đăng nhập.
     */
    public User requireAuthenticatedUser(HttpSession session) {
        Object principal = session == null ? null : session.getAttribute("loggedInUser");
        if (!(principal instanceof User user) || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        return user;
    }

    /**
     * [Chức năng]: Lấy `userId` của người dùng đang đăng nhập hoặc ném lỗi 401.
     * [Mục đích]: Rút gọn thao tác trích mã tài khoản sau khi xác thực phiên qua `requireAuthenticatedUser`.
     */
    public Long resolveUserIdOrThrow(HttpSession session) {
        return requireAuthenticatedUser(session).getUserId();
    }

    /**
     * [Chức năng]: Xác định `staffId` hồ sơ bác sĩ tương ứng với tài khoản đang đăng nhập.
     * [Mục đích]: Tra cứu `StaffProfile` theo `userId` của người gọi; trả về `null` nếu tài khoản không gắn hồ sơ nhân viên (ví dụ Admin thuần).
     */
    public Long resolveActingDoctorStaffId(HttpSession session) {
        User user = requireAuthenticatedUser(session);
        return staff.findByUserUserId(user.getUserId())
                .map(StaffProfile::getStaffId)
                .orElse(null);
    }

    /**
     * [Chức năng]: Kiểm tra tài khoản có vai trò Quản trị viên (Admin) đang hoạt động hay không.
     * [Mục đích]: Ủy quyền cho `UserRepository.hasActiveAdminRole` để cấp quyền vượt giới hạn chi nhánh/bác sĩ chính khi cần.
     */
    public boolean isAdmin(User user) {
        return user != null && user.getUserId() != null && users.hasActiveAdminRole(user.getUserId());
    }

    /**
     * [Chức năng]: Tập hợp danh sách `branchId` mà người dùng được phép truy cập.
     * [Mục đích]: Trả về `null` nếu là Admin (toàn hệ thống); với tài khoản khác gộp các chi nhánh phân công `ACTIVE` trong `staff_branch_assignments` và chi nhánh gốc của `User`.
     */
    public Set<Long> resolveAccessibleBranchIds(User user) {
        if (user == null || user.getUserId() == null) {
            return Set.of();
        }
        if (isAdmin(user)) {
            return null;
        }
        Set<Long> branchIds = new LinkedHashSet<>();
        if (branchAssignments != null) {
            List<Long> assigned = branchAssignments.findActiveBranchIdsByUserId(user.getUserId(), AssignmentStatus.ACTIVE);
            if (assigned != null) {
                for (Long id : assigned) {
                    if (id != null) {
                        branchIds.add(id);
                    }
                }
            }
        }
        users.findById(user.getUserId()).ifPresentOrElse(fresh -> {
            if (fresh.getBranch() != null && fresh.getBranch().getBranchId() != null) {
                branchIds.add(fresh.getBranch().getBranchId());
            }
        }, () -> {
            try {
                if (user.getBranch() != null && user.getBranch().getBranchId() != null) {
                    branchIds.add(user.getBranch().getBranchId());
                }
            } catch (Exception ignored) {
                // Bo qua proxy detached khi test
            }
        });
        return Set.copyOf(branchIds);
    }

    /**
     * [Chức năng]: Kiểm tra người dùng có quyền truy cập vào một chi nhánh cụ thể hay không.
     * [Mục đích]: Dựa trên `resolveAccessibleBranchIds`; luôn cho phép Admin (`allowed == null`) và đối chiếu `branchId` thuộc tập chi nhánh hợp lệ.
     */
    public boolean isBranchAllowed(User user, Long branchId) {
        Set<Long> allowed = resolveAccessibleBranchIds(user);
        if (allowed == null) {
            return true;
        }
        return branchId != null && allowed.contains(branchId);
    }

    /**
     * [Chức năng]: Bắt buộc tài khoản trong phiên có quyền truy cập chi nhánh `branchId`.
     * [Mục đích]: Xác thực người dùng và ném HTTP 403 nếu cố truy cập dữ liệu của chi nhánh khác.
     */
    public void requireBranch(HttpSession session, Long branchId) {
        User user = requireAuthenticatedUser(session);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập dữ liệu của chi nhánh khác.");
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền xem (chỉ đọc) thông tin một lượt khám theo `visitId`.
     * [Mục đích]: Cho phép Admin, bác sĩ phụ trách chính, bác sĩ có ủy quyền khám thay còn hiệu lực, hoặc nhân sự thuộc chi nhánh của ca khám; từ chối bằng HTTP 403 nếu trái phạm vi.
     */
    public void requireVisitRead(HttpSession session, Long visitId) {
        User user = requireAuthenticatedUser(session);
        Visit visit = visits.findById(visitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca khám."));
        if (isAdmin(user)) {
            return;
        }
        StaffProfile doctor = staff.findByUserUserId(user.getUserId()).orElse(null);
        boolean primary = doctor != null && visit.getPrimaryDoctor() != null
                && doctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId());
        if (primary || (doctor != null && coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                visitId, doctor.getStaffId(), LocalDateTime.now()))) {
            return;
        }
        Long visitBranchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
        if (!isBranchAllowed(user, visitBranchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền xem thông tin ca khám này.");
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền xem hồ sơ bệnh nhân theo `profileId`.
     * [Mục đích]: Cho phép Admin hoặc nhân sự có chi nhánh khớp chi nhánh ưu tiên của bệnh nhân hay ít nhất một lượt khám của bệnh nhân thuộc chi nhánh được phép.
     */
    public void requirePatientRead(HttpSession session, Long profileId) {
        User user = requireAuthenticatedUser(session);
        if (patients == null) {
            return;
        }
        PatientProfile patient = patients.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ bệnh nhân."));
        if (isAdmin(user)) {
            return;
        }
        Set<Long> allowed = resolveAccessibleBranchIds(user);
        Long preferredBranchId = patient.getPreferredBranch() != null ? patient.getPreferredBranch().getBranchId() : null;
        if (preferredBranchId != null && allowed != null && allowed.contains(preferredBranchId)) {
            return;
        }
        boolean hasVisitInAllowedBranch = allowed != null && !allowed.isEmpty()
                && visits.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(profileId).stream()
                        .anyMatch(v -> v.getBranch() != null && allowed.contains(v.getBranch().getBranchId()));
        if (hasVisitInAllowedBranch) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập hồ sơ bệnh nhân của chi nhánh khác.");
    }

    /**
     * [Chức năng]: Kiểm tra quyền ghi/mở tác nghiệp trên hồ sơ bệnh nhân theo `profileId`.
     * [Mục đích]: Tái sử dụng quy tắc xác thực chi nhánh bệnh nhân từ `requirePatientRead`.
     */
    public void requirePatientWrite(HttpSession session, Long profileId) {
        requirePatientRead(session, profileId);
    }

    /**
     * [Chức năng]: Kiểm tra quyền xem một Kế hoạch điều trị theo `planId`.
     * [Mục đích]: Cho phép Admin, nhân sự thuộc chi nhánh của kế hoạch, hoặc nhân sự có quyền đọc hồ sơ bệnh nhân sở hữu kế hoạch đó.
     */
    public void requireTreatmentPlanRead(HttpSession session, Long planId) {
        User user = requireAuthenticatedUser(session);
        if (treatmentPlans == null) {
            return;
        }
        TreatmentPlan plan = treatmentPlans.findByIdWithDetails(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch điều trị."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = resolveTreatmentPlanBranchId(plan);
        if (isBranchAllowed(user, branchId)) {
            return;
        }
        if (plan.getPatientProfile() != null) {
            requirePatientRead(session, plan.getPatientProfile().getProfileId());
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền xem kế hoạch điều trị của chi nhánh khác.");
    }

    /**
     * [Chức năng]: Kiểm tra quyền chỉnh sửa nội dung chuyên môn của một Kế hoạch điều trị theo `planId`.
     * [Mục đích]: Cho phép Admin; với nhân sự khác yêu cầu thuộc chi nhánh kế hoạch và nếu là tài khoản bác sĩ thì phải đúng bác sĩ phụ trách phác đồ.
     */
    public void requireTreatmentPlanWrite(HttpSession session, Long planId) {
        User user = requireAuthenticatedUser(session);
        if (treatmentPlans == null) {
            return;
        }
        TreatmentPlan plan = treatmentPlans.findByIdWithDetails(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch điều trị."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = resolveTreatmentPlanBranchId(plan);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền chỉnh sửa kế hoạch điều trị của chi nhánh khác.");
        }
        StaffProfile callerDoctor = staff.findByUserUserId(user.getUserId()).orElse(null);
        if (callerDoctor != null && plan.getDoctor() != null
                && !callerDoctor.getStaffId().equals(plan.getDoctor().getStaffId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ bác sĩ phụ trách phác đồ mới có quyền cập nhật.");
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác tài chính (chia đợt, thu tiền trả góp) trên một Kế hoạch điều trị.
     * [Mục đích]: Cho phép Admin, Kế toán/Thu ngân có quyền tài chính (`hasFinanceAccess`) hoặc Bác sĩ phụ trách phác đồ trong cùng chi nhánh, đồng thời trả về `User` thực hiện.
     */
    public User requireTreatmentPlanFinanceWrite(HttpSession session, Long planId) {
        User user = requireAuthenticatedUser(session);
        if (treatmentPlans == null || isAdmin(user)) {
            return user;
        }
        TreatmentPlan plan = treatmentPlans.findByIdWithDetails(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy kế hoạch điều trị."));
        Long branchId = resolveTreatmentPlanBranchId(plan);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền thu tiền / chỉnh sửa kế hoạch điều trị của chi nhánh khác.");
        }
        if (hasFinanceAccess(user)) {
            return user;
        }
        StaffProfile callerDoctor = staff.findByUserUserId(user.getUserId()).orElse(null);
        if (callerDoctor != null && plan.getDoctor() != null
                && callerDoctor.getStaffId().equals(plan.getDoctor().getStaffId())) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Thu ngân/Kế toán hoặc Bác sĩ phụ trách mới được thao tác thu tiền phác đồ.");
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng có quyền tài chính (`PAYMENT_COLLECT`, `INVOICE_CREATE`, `FINANCE_DASHBOARD`) hoặc vai trò `KE_TOAN` đang hoạt động hay không.
     */
    private boolean hasFinanceAccess(User user) {
        if (user == null || user.getUserId() == null) {
            return false;
        }
        if (users.hasActivePermission(user.getUserId(), "PAYMENT_COLLECT")
                || users.hasActivePermission(user.getUserId(), "INVOICE_CREATE")
                || users.hasActivePermission(user.getUserId(), "FINANCE_DASHBOARD")) {
            return true;
        }
        if (user.getRoles() != null) {
            return user.getRoles().stream().anyMatch(r -> Boolean.TRUE.equals(r.getIsActive())
                    && r.getRoleName() != null
                    && ("KE_TOAN".equalsIgnoreCase(r.getRoleName()) || "ROLE_KE_TOAN".equalsIgnoreCase(r.getRoleName())));
        }
        return false;
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một giai đoạn của Kế hoạch điều trị (`stageId`).
     * [Mục đích]: Tải `TreatmentPlanStage` và kiểm tra quyền ghi trên kế hoạch điều trị cha qua `requireTreatmentPlanWrite`.
     */
    public void requireTreatmentStage(HttpSession session, Long stageId) {
        if (treatmentPlanStages == null) {
            requireAuthenticatedUser(session);
            return;
        }
        TreatmentPlanStage stage = treatmentPlanStages.findById(stageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giai đoạn điều trị."));
        if (stage.getPlan() != null) {
            requireTreatmentPlanWrite(session, stage.getPlan().getPlanId());
        } else {
            requireAuthenticatedUser(session);
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một hạng mục thủ thuật của Kế hoạch điều trị (`itemId`).
     * [Mục đích]: Tải `TreatmentPlanItem`, xác định kế hoạch cha (trực tiếp hoặc qua giai đoạn) và gọi `requireTreatmentPlanWrite`.
     */
    public void requireTreatmentPlanItem(HttpSession session, Long itemId) {
        if (treatmentPlanItems == null) {
            requireAuthenticatedUser(session);
            return;
        }
        TreatmentPlanItem item = treatmentPlanItems.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bước thủ thuật."));
        Long planId = item.getPlan() != null
                ? item.getPlan().getPlanId()
                : (item.getStage() != null && item.getStage().getPlan() != null ? item.getStage().getPlan().getPlanId() : null);
        if (planId != null) {
            requireTreatmentPlanWrite(session, planId);
        } else {
            requireAuthenticatedUser(session);
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền đọc hoặc ghi trên một phiếu đặt xưởng Labo (`laboId`).
     * [Mục đích]: Kiểm tra đúng chi nhánh của phiếu Labo; nếu `writeOperation=true` và có gắn ca khám thì yêu cầu bác sĩ là người phụ trách chính hoặc có quyền khám thay còn hiệu lực.
     */
    public void requireLaboOrder(HttpSession session, Long laboId, boolean writeOperation) {
        User user = requireAuthenticatedUser(session);
        if (laboOrders == null) {
            return;
        }
        LaboOrder order = laboOrders.findById(laboId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu đặt xưởng."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = order.getBranch() != null
                ? order.getBranch().getBranchId()
                : (order.getVisit() != null && order.getVisit().getBranch() != null ? order.getVisit().getBranch().getBranchId() : null);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập phiếu labo của chi nhánh khác.");
        }
        if (writeOperation && order.getVisit() != null) {
            StaffProfile callerDoctor = staff.findByUserUserId(user.getUserId()).orElse(null);
            if (callerDoctor != null && order.getVisit().getPrimaryDoctor() != null
                    && !callerDoctor.getStaffId().equals(order.getVisit().getPrimaryDoctor().getStaffId())
                    && !coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                            order.getVisit().getVisitId(), callerDoctor.getStaffId(), LocalDateTime.now())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền sửa phiếu labo thuộc ca khám của bác sĩ khác.");
            }
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền truy cập lịch hẹn tái khám theo `appointmentId`.
     * [Mục đích]: Xác thực người dùng thuộc chi nhánh của lịch hẹn (hoặc là Admin); ném HTTP 403 nếu khác chi nhánh.
     */
    public void requireReExamination(HttpSession session, Long appointmentId) {
        User user = requireAuthenticatedUser(session);
        if (appointments == null) {
            return;
        }
        Appointment appointment = appointments.findById(appointmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch tái khám."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = appointment.getBranch() != null ? appointment.getBranch().getBranchId() : null;
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập lịch hẹn của chi nhánh khác.");
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền truy cập tác vụ chăm sóc khách hàng (`CustomerCareTask`) theo `taskId`.
     * [Mục đích]: Xác định chi nhánh của tác vụ (hoặc chi nhánh ưu tiên bệnh nhân) và đối chiếu quyền chi nhánh qua `isBranchAllowed`.
     */
    public void requireCareTask(HttpSession session, Long taskId) {
        User user = requireAuthenticatedUser(session);
        if (careTasks == null) {
            return;
        }
        CustomerCareTask task = careTasks.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tác vụ chăm sóc."));
        if (isAdmin(user)) {
            return;
        }
        Long branchId = task.getBranch() != null
                ? task.getBranch().getBranchId()
                : (task.getPatientProfile() != null && task.getPatientProfile().getPreferredBranch() != null
                        ? task.getPatientProfile().getPreferredBranch().getBranchId() : null);
        if (!isBranchAllowed(user, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền truy cập tác vụ CSKH của chi nhánh khác.");
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền xem (chỉ đọc) lịch sử và thông tin của một tiến trình quy trình (`executionId`).
     * [Mục đích]: Tải `WorkflowExecution` và kiểm tra quyền đọc lượt khám tương ứng thông qua `requireVisitRead`.
     */
    public void requireWorkflowExecutionRead(HttpSession session, Long executionId) {
        if (workflowExecutions == null) {
            requireAuthenticatedUser(session);
            return;
        }
        var execution = workflowExecutions.findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tiến trình quy trình."));
        if (execution.getVisit() != null) {
            requireVisitRead(session, execution.getVisit().getVisitId());
        } else {
            requireAuthenticatedUser(session);
        }
    }

    /**
     * [Chức năng]: Kiểm tra quyền thao tác trên một đợt thanh toán trả góp (`installmentId`) của kế hoạch điều trị.
     * [Mục đích]: Cho phép Admin, nhân sự Tài chính/Lễ tân trong đúng chi nhánh của kế hoạch, hoặc bác sĩ có quyền ghi kế hoạch qua `requireTreatmentPlanWrite`.
     */
    public void requireTreatmentInstallment(HttpSession session, Long installmentId) {
        if (treatmentPlanInstallments == null) {
            requireAuthenticatedUser(session);
            return;
        }
        TreatmentPlanInstallment installment = treatmentPlanInstallments.findById(installmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đợt thanh toán."));
        if (installment.getPlan() != null) {
            User user = requireAuthenticatedUser(session);
            if (isAdmin(user)) {
                return;
            }
            Long branchId = resolveTreatmentPlanBranchId(installment.getPlan());
            if (!isBranchAllowed(user, branchId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền thao tác đợt thanh toán của chi nhánh khác.");
            }
            boolean isReceptionOrFinance = hasFinanceAccess(user)
                    || users.hasActivePermission(user.getUserId(), "RECEPTION_CHECKIN")
                    || users.hasActivePermission(user.getUserId(), "APPOINTMENT_MANAGE");
            if (isReceptionOrFinance) {
                return;
            }
            requireTreatmentPlanWrite(session, installment.getPlan().getPlanId());
        } else {
            requireAuthenticatedUser(session);
        }
    }

    /**
     * [Hàm hỗ trợ]: Lấy `branchId` của kế hoạch điều trị (ưu tiên chi nhánh gắn trực tiếp, dự phòng chi nhánh ưu tiên của bệnh nhân).
     */
    private Long resolveTreatmentPlanBranchId(TreatmentPlan plan) {
        if (plan.getBranch() != null) {
            return plan.getBranch().getBranchId();
        }
        if (plan.getPatientProfile() != null && plan.getPatientProfile().getPreferredBranch() != null) {
            return plan.getPatientProfile().getPreferredBranch().getBranchId();
        }
        return null;
    }

    /**
     * [Chức năng]: Xác định hồ sơ bác sĩ thực hiện (`StaffProfile`) hợp lệ cho thủ thuật trong một ca khám.
     * [Mục đích]: Ưu tiên bác sĩ được chọn (`requestedDoctorId`) nếu đang `ACTIVE` và hợp lệ với ca/chi nhánh; nếu bỏ trống thì dùng bác sĩ đang đăng nhập (không phải Admin) hoặc bác sĩ chính của ca khám, báo lỗi rõ nếu chưa phân công.
     */
    public StaffProfile resolveActingDoctor(HttpSession session, Visit visit, Long requestedDoctorId) {
        if (visit == null) {
            throw new IllegalArgumentException("Không tìm thấy ca khám phù hợp.");
        }
        Long visitBranchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
        if (requestedDoctorId != null) {
            StaffProfile chosen = staff.findById(requestedDoctorId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bác sĩ thực hiện được chọn."));
            if (chosen.getStatus() != StaffStatus.ACTIVE) {
                throw new IllegalArgumentException("Bác sĩ được chọn hiện không ở trạng thái hoạt động.");
            }
            if (!isDoctorValidForVisit(chosen, visit, visitBranchId)) {
                throw new IllegalArgumentException("Bác sĩ được chọn không thuộc chi nhánh của ca khám này.");
            }
            return chosen;
        }

        User currentUser = (session != null && session.getAttribute("loggedInUser") instanceof User u) ? u : null;
        if (currentUser != null && !isAdmin(currentUser)) {
            StaffProfile callerDoctor = staff.findByUserUserId(currentUser.getUserId()).orElse(null);
            if (callerDoctor != null) {
                return callerDoctor;
            }
        }

        if (visit.getPrimaryDoctor() != null) {
            return visit.getPrimaryDoctor();
        }
        throw new IllegalStateException("Ca khám chưa được phân công bác sĩ chính. Vui lòng phân công bác sĩ tại màn Điều phối trước khi thực hiện thủ thuật.");
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra một bác sĩ có hợp lệ với ca khám (là bác sĩ chính, bác sĩ khám thay còn hiệu lực hoặc thuộc chi nhánh của ca khám) hay không.
     */
    private boolean isDoctorValidForVisit(StaffProfile doctor, Visit visit, Long visitBranchId) {
        if (visit.getPrimaryDoctor() != null && doctor.getStaffId().equals(visit.getPrimaryDoctor().getStaffId())) {
            return true;
        }
        if (coverGrants != null && coverGrants.existsByVisitVisitIdAndCoveringDoctorStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                visit.getVisitId(), doctor.getStaffId(), LocalDateTime.now())) {
            return true;
        }
        if (visitBranchId == null) {
            return true;
        }
        boolean hasBranchMetadata = false;
        if (doctor.getUser() != null) {
            Set<Long> allowed = resolveAccessibleBranchIds(doctor.getUser());
            if (allowed == null) {
                return true;
            }
            if (!allowed.isEmpty()) {
                hasBranchMetadata = true;
                if (allowed.contains(visitBranchId)) {
                    return true;
                }
            }
        }
        if (branchAssignments != null) {
            List<Long> staffBranches = branchAssignments.findActiveBranchIdsByStaffId(doctor.getStaffId(), AssignmentStatus.ACTIVE);
            if (staffBranches != null && !staffBranches.isEmpty()) {
                hasBranchMetadata = true;
                if (staffBranches.contains(visitBranchId)) {
                    return true;
                }
            }
        }
        return !hasBranchMetadata;
    }

    /**
     * [Chức năng]: Trích thông tin tóm tắt dịch vụ bệnh nhân đã đăng ký từ lịch hẹn gốc của ca khám.
     * [Mục đích]: Tra cứu `Appointment` gắn với `visit.appointmentId` để trả về `Map` chứa mã, tên, giá, đơn vị, cờ `requiresTooth`/`isParaclinical` dùng cho bàn khám, KHDT và kiểm tra làm thêm dịch vụ.
     */
    public java.util.Map<String, Object> resolveBookedServiceSummary(Visit visit) {
        if (visit == null || visit.getAppointmentId() == null || appointments == null) {
            return null;
        }
        Appointment appt = appointments.findByIdWithService(visit.getAppointmentId()).orElse(null);
        if (appt == null || appt.getService() == null) {
            return null;
        }
        com.cvdcms.entity.Service srv = appt.getService();
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("serviceId", srv.getServiceId());
        map.put("serviceCode", srv.getServiceCode());
        map.put("serviceName", srv.getServiceName());
        map.put("basePrice", srv.getBasePrice());
        map.put("unit", srv.getUnit());
        map.put("requiresTooth", srv.getRequiresTooth());
        map.put("isParaclinical", srv.getIsParaclinical());
        map.put("appointmentCode", appt.getAppointmentCode());
        return map;
    }

    /**
     * [Chức năng]: Đếm số bệnh nhân khác đang ở trạng thái `WAITING` trên hàng chờ khám của bác sĩ phụ trách ca trong ngày.
     * [Mục đích]: Phục vụ hiển thị áp lực hàng chờ tại bàn khám và quyết định áp dụng ngưỡng thời gian khi bác sĩ muốn làm thêm dịch vụ/combo trong cùng ca.
     */
    public int countWaitingPatientsForVisitDoctor(Visit visit) {
        if (visit == null || queueTickets == null || visit.getBranch() == null || visit.getPrimaryDoctor() == null) {
            return 0;
        }
        var tickets = queueTickets.findForDoctorOnDay(
                visit.getBranch().getBranchId(),
                java.time.LocalDate.now(),
                com.cvdcms.entity.enums.QueueType.EXAM,
                visit.getPrimaryDoctor().getStaffId());
        if (tickets == null || tickets.isEmpty()) {
            return 0;
        }
        return (int) tickets.stream()
                .filter(t -> t.getStatus() == com.cvdcms.entity.enums.QueueStatus.WAITING
                        && t.getVisit() != null
                        && !visit.getVisitId().equals(t.getVisit().getVisitId()))
                .count();
    }

    /**
     * [Chức năng]: Kiểm tra Kế hoạch điều trị mới/cập nhật phải bao gồm dịch vụ bệnh nhân đã đặt lịch hẹn gốc.
     * [Mục đích]: Nếu dịch vụ đặt lịch chưa tồn tại trong các KHDT đang mở của ca khám và cũng thiếu trong `TreatmentPlanRequestDTO`, ném `IllegalArgumentException` tránh bỏ sót dịch vụ đã hẹn.
     */
    public void assertTreatmentPlanIncludesBookedService(Long visitId, com.cvdcms.DTO.TreatmentPlanRequestDTO request) {
        if (visitId == null || request == null || visits == null || appointments == null) {
            return;
        }
        Visit visit = visits.findById(visitId).orElse(null);
        var booked = resolveBookedServiceSummary(visit);
        if (booked == null || booked.get("serviceId") == null) {
            return;
        }
        Long bookedServiceId = ((Number) booked.get("serviceId")).longValue();

        if (treatmentPlans != null && visit != null && visit.getPatientProfile() != null) {
            var existingPlans = treatmentPlans.findByProfileIdWithDetails(visit.getPatientProfile().getProfileId());
            boolean alreadyCovered = existingPlans != null && existingPlans.stream()
                    .filter(tp -> tp.getStatus() != com.cvdcms.entity.enums.TreatmentPlanStatus.CANCELLED)
                    .filter(tp -> tp.getVisit() == null || visitId.equals(tp.getVisit().getVisitId()))
                    .flatMap(tp -> tp.getStages() == null ? java.util.stream.Stream.empty() : tp.getStages().stream())
                    .flatMap(st -> st.getItems() == null ? java.util.stream.Stream.empty() : st.getItems().stream())
                    .anyMatch(it -> it.getService() != null && bookedServiceId.equals(it.getService().getServiceId()));
            if (alreadyCovered) {
                return;
            }
        }

        boolean includedInRequest = request.getStages() != null && request.getStages().stream()
                .filter(st -> st.getItems() != null)
                .flatMap(st -> st.getItems().stream())
                .anyMatch(it -> it.getServiceId() != null && bookedServiceId.equals(it.getServiceId()));

        if (!includedInRequest) {
            throw new IllegalArgumentException(
                    "Kế hoạch điều trị bắt buộc phải giữ dịch vụ bệnh nhân đã đăng ký từ lịch hẹn: " + booked.get("serviceName"));
        }
    }

    private static final int MAX_ADDON_SERVICE_MINUTES = 45;
    private static final int MAX_ADDON_TOTAL_MINUTES = 60;
    private static final String CONFIRM_PREFIX = "PATIENT_CONFIRM_REQUIRED:";

    private record EngagedService(Long serviceId, int minutes, LocalDateTime at) {}

    /**
     * [Hàm hỗ trợ]: Lấy thời lượng ước tính (phút) của một dịch vụ từ `estimatedDurationMinutes`/`procedureDurationMinutes`, mặc định 30 phút.
     */
    private int durationOf(com.cvdcms.entity.Service service) {
        if (service == null) {
            return 30;
        }
        if (service.getEstimatedDurationMinutes() != null && service.getEstimatedDurationMinutes() > 0) {
            return service.getEstimatedDurationMinutes();
        }
        if (service.getProcedureDurationMinutes() != null && service.getProcedureDurationMinutes() > 0) {
            return service.getProcedureDurationMinutes();
        }
        return 30;
    }

    /**
     * [Hàm hỗ trợ]: Trích `serviceId` gắn với mẫu quy trình của một `WorkflowExecution`.
     */
    private Long serviceIdOfExecution(com.cvdcms.entity.WorkflowExecution ex) {
        if (ex == null || ex.getVersion() == null || ex.getVersion().getTemplate() == null
                || ex.getVersion().getTemplate().getService() == null) {
            return null;
        }
        return ex.getVersion().getTemplate().getService().getServiceId();
    }

    /**
     * [Hàm hỗ trợ]: Tập hợp danh sách dịch vụ lâm sàng đã thực hiện hoặc đã khởi chạy quy trình trong lượt khám `visitId`, sắp theo thời điểm bắt đầu.
     */
    private List<EngagedService> resolveEngagedServices(Long visitId) {
        java.util.Map<Long, EngagedService> byService = new java.util.LinkedHashMap<>();
        if (visitId != null && performedServices != null) {
            var psList = performedServices.findByVisitIdWithDetails(visitId);
            if (psList != null) {
                for (var ps : psList) {
                    if (ps == null || ps.getStatus() == com.cvdcms.entity.enums.PerformedServiceStatus.CANCELLED
                            || ps.getService() == null || Boolean.TRUE.equals(ps.getService().getIsParaclinical())) {
                        continue;
                    }
                    Long sid = ps.getService().getServiceId();
                    if (sid == null) {
                        continue;
                    }
                    int mins = durationOf(ps.getService());
                    LocalDateTime at = ps.getCompletedAt();
                    EngagedService prev = byService.get(sid);
                    if (prev == null || (at != null && (prev.at() == null || at.isBefore(prev.at())))) {
                        byService.put(sid, new EngagedService(sid, mins, at));
                    }
                }
            }
        }
        if (visitId != null && workflowExecutions != null) {
            var execList = workflowExecutions.findByVisit_VisitId(visitId);
            if (execList != null) {
                for (var ex : execList) {
                    if (ex == null || ex.getStatus() == com.cvdcms.entity.enums.WorkflowExecutionStatus.CANCELLED) {
                        continue;
                    }
                    com.cvdcms.entity.Service srv = (ex.getVersion() != null && ex.getVersion().getTemplate() != null)
                            ? ex.getVersion().getTemplate().getService()
                            : null;
                    if (srv == null || srv.getServiceId() == null || Boolean.TRUE.equals(srv.getIsParaclinical())) {
                        continue;
                    }
                    Long sid = srv.getServiceId();
                    int mins = durationOf(srv);
                    LocalDateTime at = ex.getStartedAt();
                    EngagedService prev = byService.get(sid);
                    if (prev == null || (at != null && (prev.at() == null || at.isBefore(prev.at())))) {
                        byService.put(sid, new EngagedService(sid, mins, at));
                    }
                }
            }
        }
        java.util.List<EngagedService> list = new java.util.ArrayList<>(byService.values());
        list.sort(java.util.Comparator.comparing(EngagedService::at,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return list;
    }

    /**
     * [Hàm hỗ trợ]: Tính tổng số phút đã dùng cho các dịch vụ làm thêm trong ca (loại trừ dịch vụ đã đặt lịch hoặc dịch vụ đầu tiên của ca).
     */
    private int addOnMinutesUsed(List<EngagedService> engaged, Long bookedId) {
        if (engaged == null || engaged.isEmpty()) {
            return 0;
        }
        int sum = 0;
        if (bookedId != null) {
            for (EngagedService e : engaged) {
                if (!bookedId.equals(e.serviceId())) {
                    sum += e.minutes();
                }
            }
        } else {
            for (int i = 1; i < engaged.size(); i++) {
                sum += engaged.get(i).minutes();
            }
        }
        return sum;
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra bệnh nhân có tiến trình quy trình đang mở (`IN_PROGRESS`/`PAUSED`) của cùng dịch vụ kế thừa từ buổi khám trước hay không.
     */
    private boolean hasOpenExecutionFromPreviousVisit(Visit visit, Long serviceId) {
        if (visit == null || visit.getPatientProfile() == null || visit.getPatientProfile().getProfileId() == null
                || serviceId == null || workflowExecutions == null) {
            return false;
        }
        Long currentVisitId = visit.getVisitId();
        var patientExecs = workflowExecutions.findByPatientProfile_ProfileIdOrderByStartedAtDesc(
                visit.getPatientProfile().getProfileId());
        if (patientExecs == null) {
            return false;
        }
        for (var ex : patientExecs) {
            if (ex == null) {
                continue;
            }
            if (ex.getStatus() != com.cvdcms.entity.enums.WorkflowExecutionStatus.IN_PROGRESS
                    && ex.getStatus() != com.cvdcms.entity.enums.WorkflowExecutionStatus.PAUSED) {
                continue;
            }
            Long exVisitId = ex.getVisit() != null ? ex.getVisit().getVisitId() : null;
            if (exVisitId != null && currentVisitId != null && currentVisitId.equals(exVisitId)) {
                continue;
            }
            if (serviceId.equals(serviceIdOfExecution(ex))) {
                return true;
            }
        }
        return false;
    }

    /**
     * [Hàm hỗ trợ]: Đánh giá một dịch vụ có được phép làm gộp trong cùng ca khám không (`null` nếu làm ngay, thông báo chặn nếu vượt ngưỡng 45p/60p hoặc `allowSameVisit=false`, hoặc chuỗi có tiền tố `PATIENT_CONFIRM_REQUIRED:` khi cần bệnh nhân xác nhận).
     */
    private String evaluateSameVisitService(com.cvdcms.entity.Service target, int targetMinutes,
            List<EngagedService> engaged, Long bookedId, int waiting) {
        if (target == null || Boolean.TRUE.equals(target.getIsParaclinical())) {
            return null;
        }
        Long targetId = target.getServiceId();
        if (targetId != null && bookedId != null && bookedId.equals(targetId)) {
            return null;
        }
        if (targetId != null && engaged != null && engaged.stream().anyMatch(e -> targetId.equals(e.serviceId()))) {
            return null;
        }
        if (bookedId == null && (engaged == null || engaged.isEmpty())) {
            return null;
        }
        String srvName = target.getServiceName() != null ? target.getServiceName() : "Dịch vụ";
        if (Boolean.FALSE.equals(target.getAllowSameVisit())) {
            return "Dịch vụ \"" + srvName + "\" được cấu hình không làm gộp trong cùng ca khám. Vui lòng lưu trong Kế hoạch điều trị để hẹn buổi sau!";
        }
        if (waiting > 0) {
            if (targetMinutes > MAX_ADDON_SERVICE_MINUTES) {
                return "Đang có " + waiting + " bệnh nhân chờ. Dịch vụ làm thêm \"" + srvName + "\" cần ~"
                        + targetMinutes + " phút (vượt ngưỡng " + MAX_ADDON_SERVICE_MINUTES
                        + " phút/dịch vụ). Vui lòng lưu trong Kế hoạch điều trị để hẹn buổi sau!";
            }
            int used = addOnMinutesUsed(engaged, bookedId);
            if (used + targetMinutes > MAX_ADDON_TOTAL_MINUTES) {
                return "Đang có " + waiting + " bệnh nhân chờ. Tổng thời gian làm thêm trong ca sẽ là "
                        + (used + targetMinutes) + " phút (vượt ngưỡng " + MAX_ADDON_TOTAL_MINUTES
                        + " phút). Vui lòng lưu trong Kế hoạch điều trị để hẹn buổi sau!";
            }
        }
        return CONFIRM_PREFIX + " Dịch vụ làm thêm trong ca: \"" + srvName + "\" (~" + targetMinutes + " phút), "
                + (waiting > 0 ? ("đang có " + waiting + " bệnh nhân chờ") : "hàng đợi đang trống")
                + ". Vui lòng xác nhận bệnh nhân đồng ý làm luôn trong ca này!";
    }

    /**
     * [Chức năng]: Kiểm tra chính sách cho phép thực hiện thêm một dịch vụ hoặc hạng mục KHDT ngay trong ca khám hiện tại.
     * [Mục đích]: Miễn kiểm tra với dịch vụ đã đặt lịch, KHDT đã chấp thuận/đang thực hiện hoặc tiến trình tiếp nối từ buổi trước; ngược lại đánh giá ngưỡng thời gian theo số bệnh nhân chờ và ném `IllegalStateException` (có hoặc không có tiền tố yêu cầu xác nhận bệnh nhân).
     */
    public void assertVisitServiceExecutionAllowed(Long visitId, Long targetServiceId, Long planItemId, boolean patientConfirmed) {
        if (visitId == null || visits == null) {
            return;
        }
        Visit visit = visits.findById(visitId).orElse(null);
        if (visit == null) {
            return;
        }

        var booked = resolveBookedServiceSummary(visit);
        Long bookedServiceId = (booked != null && booked.get("serviceId") instanceof Number bNum)
                ? bNum.longValue()
                : null;

        com.cvdcms.entity.Service targetService = null;
        if (planItemId != null && treatmentPlanItems != null) {
            if (workflowExecutions != null) {
                boolean hasItemExec = workflowExecutions.findByPlanItem_PlanItemId(planItemId).stream()
                        .anyMatch(ex -> ex.getStatus() != com.cvdcms.entity.enums.WorkflowExecutionStatus.CANCELLED);
                if (hasItemExec) {
                    return;
                }
            }
            var it = treatmentPlanItems.findById(planItemId).orElse(null);
            if (it != null) {
                if (it.getService() != null) {
                    targetService = it.getService();
                    if (targetServiceId == null) {
                        targetServiceId = targetService.getServiceId();
                    }
                }
                if (it.getStatus() == com.cvdcms.entity.enums.TreatmentPlanItemStatus.IN_PROGRESS) {
                    return;
                }
                var stage = it.getStage();
                if (stage != null) {
                    if (stage.getStageId() != null && workflowExecutions != null) {
                        final Long resolvedSid = targetServiceId;
                        boolean stageHasOpenExec = workflowExecutions.findByPlanStage_StageId(stage.getStageId()).stream()
                                .anyMatch(ex -> (ex.getStatus() == com.cvdcms.entity.enums.WorkflowExecutionStatus.IN_PROGRESS
                                        || ex.getStatus() == com.cvdcms.entity.enums.WorkflowExecutionStatus.PAUSED)
                                        && (resolvedSid == null || resolvedSid.equals(serviceIdOfExecution(ex))));
                        if (stageHasOpenExec) {
                            return;
                        }
                    }
                    if (stage.getStatus() == com.cvdcms.entity.enums.TreatmentPlanStageStatus.IN_PROGRESS) {
                        return;
                    }
                    if (bookedServiceId != null && stage.getItems() != null && stage.getItems().stream()
                            .anyMatch(sib -> sib.getService() != null && bookedServiceId.equals(sib.getService().getServiceId()))) {
                        return;
                    }
                }
                var plan = it.getPlan();
                if (plan != null) {
                    if (plan.getStatus() == com.cvdcms.entity.enums.TreatmentPlanStatus.ACCEPTED
                            || plan.getStatus() == com.cvdcms.entity.enums.TreatmentPlanStatus.IN_PROGRESS) {
                        return;
                    }
                    if (plan.getCreatedAt() != null && visit.getCheckInAt() != null
                            && plan.getCreatedAt().isBefore(visit.getCheckInAt())) {
                        return;
                    }
                }
            }
        }
        if (targetService == null && targetServiceId != null && serviceRepository != null) {
            targetService = serviceRepository.findById(targetServiceId).orElse(null);
        }
        if (targetService == null) {
            return;
        }
        if (hasOpenExecutionFromPreviousVisit(visit, targetService.getServiceId())) {
            return;
        }

        List<EngagedService> engaged = resolveEngagedServices(visitId);
        int waiting = countWaitingPatientsForVisitDoctor(visit);
        String verdict = evaluateSameVisitService(targetService, durationOf(targetService), engaged, bookedServiceId, waiting);
        if (verdict == null) {
            return;
        }
        if (verdict.startsWith(CONFIRM_PREFIX)) {
            if (patientConfirmed) {
                return;
            }
            throw new IllegalStateException(verdict);
        }
        throw new IllegalStateException(verdict);
    }

    /**
     * [Chức năng]: Phân loại các dịch vụ trong Combo cần dời sang buổi sau khi áp dụng Combo vào ca khám.
     * [Mục đích]: Mô phỏng thứ tự dịch vụ trong combo theo năng lực bác sĩ/phòng và ngưỡng thời gian làm thêm (45p/dịch vụ, 60p/tổng khi có bệnh nhân chờ); ném lỗi nếu mọi dịch vụ đủ năng lực đều bị chặn hoặc cần xác nhận từ bệnh nhân, ngược lại trả về tập `serviceId` phải lưu KHDT hẹn buổi sau.
     */
    public Set<Long> resolveComboDeferredServiceIds(Long visitId, Long comboId, boolean patientConfirmed) {
        if (visitId == null || comboId == null || serviceComboRepository == null) {
            return Set.of();
        }
        var combo = serviceComboRepository.findByIdWithItems(comboId).orElse(null);
        if (combo == null || combo.getItems() == null || combo.getItems().isEmpty()) {
            return Set.of();
        }
        Visit visit = visits != null ? visits.findById(visitId).orElse(null) : null;
        if (visit == null) {
            return Set.of();
        }
        var booked = resolveBookedServiceSummary(visit);
        Long bookedServiceId = (booked != null && booked.get("serviceId") instanceof Number bNum)
                ? bNum.longValue()
                : null;
        List<EngagedService> engaged = new java.util.ArrayList<>(resolveEngagedServices(visitId));
        int waiting = countWaitingPatientsForVisitDoctor(visit);

        List<com.cvdcms.entity.ServiceComboItem> ordered = combo.getItems().stream()
                .filter(i -> i != null && i.getService() != null)
                .sorted(java.util.Comparator.comparingInt(i -> i.getSequenceOrder() != null ? i.getSequenceOrder() : 999))
                .toList();
        if (ordered.isEmpty()) {
            return Set.of();
        }

        Set<Long> deferred = new LinkedHashSet<>();
        List<String> confirmNames = new java.util.ArrayList<>();
        List<String> deferredNames = new java.util.ArrayList<>();
        LocalDateTime simBase = LocalDateTime.now();
        Long branchId = visit.getBranch() != null ? visit.getBranch().getBranchId() : null;
        Long docId = visit.getPrimaryDoctor() != null ? visit.getPrimaryDoctor().getStaffId() : null;
        int capableCount = 0;

        for (int idx = 0; idx < ordered.size(); idx++) {
            var item = ordered.get(idx);
            var srv = item.getService();
            if (capabilityService != null
                    && !capabilityService.isParaclinicalOrLabService(srv.getServiceId())
                    && !capabilityService.checkDoctorAndRoomCapability(branchId, docId, visit.getRoomId(), srv.getServiceId()).allowed()) {
                continue;
            }
            capableCount++;
            int qty = item.getDefaultQuantity() != null ? Math.max(1, item.getDefaultQuantity()) : 1;
            int minutes = durationOf(srv) * qty;
            String srvName = srv.getServiceName() != null ? srv.getServiceName() : "Dịch vụ";
            String verdict = hasOpenExecutionFromPreviousVisit(visit, srv.getServiceId())
                    ? null
                    : evaluateSameVisitService(srv, minutes, engaged, bookedServiceId, waiting);

            if (verdict == null || verdict.startsWith(CONFIRM_PREFIX)) {
                if (verdict != null) {
                    confirmNames.add(srvName + " (~" + minutes + "p)");
                }
                Long sid = srv.getServiceId();
                if (sid != null && !Boolean.TRUE.equals(srv.getIsParaclinical())
                        && engaged.stream().noneMatch(e -> sid.equals(e.serviceId()))) {
                    engaged.add(new EngagedService(sid, minutes, simBase.plusSeconds(idx)));
                }
            } else {
                if (srv.getServiceId() != null) {
                    deferred.add(srv.getServiceId());
                }
                deferredNames.add(srvName + " (~" + minutes + "p)");
            }
        }

        if (capableCount > 0 && deferred.size() >= capableCount) {
            throw new IllegalStateException("Không có dịch vụ nào trong Combo đủ điều kiện làm trong ca này ("
                    + waiting + " bệnh nhân chờ; ngưỡng " + MAX_ADDON_SERVICE_MINUTES + " phút/dịch vụ, "
                    + MAX_ADDON_TOTAL_MINUTES + " phút tổng làm thêm). Vui lòng lưu Combo vào Kế hoạch điều trị để hẹn buổi sau!");
        }
        if (!confirmNames.isEmpty() && !patientConfirmed) {
            String deferredPart = deferredNames.isEmpty()
                    ? ""
                    : (" Để buổi sau (vượt ngưỡng thời gian): " + String.join(", ", deferredNames) + ".");
            throw new IllegalStateException(CONFIRM_PREFIX + " Combo có dịch vụ làm thêm trong ca: "
                    + String.join(", ", confirmNames) + "." + deferredPart
                    + " Vui lòng xác nhận bệnh nhân đồng ý làm luôn trong ca này!");
        }
        return deferred;
    }

    /**
     * [Chức năng]: Kiểm tra điều kiện áp dụng Combo dịch vụ vào ca khám hiện tại.
     * [Mục đích]: Gọi `resolveComboDeferredServiceIds` để kiểm chứng và ném ngoại lệ nếu bị chặn hoặc cần xác nhận bệnh nhân.
     */
    public void assertComboExecutionAllowed(Long visitId, Long comboId, boolean patientConfirmed) {
        resolveComboDeferredServiceIds(visitId, comboId, patientConfirmed);
    }
}
