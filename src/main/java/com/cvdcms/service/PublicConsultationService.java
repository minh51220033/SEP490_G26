package com.cvdcms.service;

import com.cvdcms.entity.BookingConsultationRequest;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.enums.ConsultationRequestStatus;
import com.cvdcms.entity.enums.PreferredSession;
import com.cvdcms.repository.AppointmentBookingLockRepository;
import com.cvdcms.repository.BookingConsultationRequestRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Xử lý an toàn yêu cầu tư vấn đặt lịch của khách chưa đăng nhập (Guest Booking P0).
 * - SĐT đã tồn tại ở users hoặc patient_profiles: không gắn hồ sơ, không tạo Appointment/User/Profile,
 *   không chiếm slot, không tạo bản ghi tư vấn, trả thông báo trung lập chống dò SĐT.
 * - SĐT chưa tồn tại: tạo bản ghi trong booking_consultation_requests với idempotency theo requestKey
 *   và chống trùng mở trên (SĐT, chi nhánh, ngày, buổi), đảm bảo rollback hoàn toàn nếu lỗi giữa chừng.
 */
@org.springframework.stereotype.Service
public class PublicConsultationService {

    public static final String NEUTRAL_GUEST_RESPONSE_MESSAGE =
            "Đã tiếp nhận thông tin đặt lịch của Quý khách. Nếu số điện thoại đã có tài khoản trên hệ thống, "
                    + "vui lòng đăng nhập để chọn đúng hồ sơ và giữ lịch trực tiếp; nếu là khách hàng mới, "
                    + "lễ tân cơ sở sẽ liên hệ xác minh và đặt lịch cho Quý khách.";

    public static final String REQUEST_KEY_CONFLICT_MESSAGE =
            "Mã yêu cầu đã được sử dụng cho thông tin khác.";

    private static final Pattern VIETNAM_PHONE_PATTERN = Pattern.compile("^0\\d{9,10}$");
    private static final Pattern REQUEST_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    private static final List<ConsultationRequestStatus> OPEN_STATUSES =
            List.of(ConsultationRequestStatus.NEW, ConsultationRequestStatus.CONTACTED);

    private final UserRepository userRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final BookingConsultationRequestRepository consultationRepository;
    private final AppointmentBookingLockRepository lockedBranches;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    public PublicConsultationService(
            UserRepository userRepository,
            PatientProfileRepository patientProfileRepository,
            BookingConsultationRequestRepository consultationRepository,
            AppointmentBookingLockRepository lockedBranches,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.consultationRepository = consultationRepository;
        this.lockedBranches = lockedBranches;
        if (transactionManager != null) {
            this.writeTx = new TransactionTemplate(transactionManager);
            this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

            this.readTx = new TransactionTemplate(transactionManager);
            this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.readTx.setReadOnly(true);
        } else {
            this.writeTx = null;
            this.readTx = null;
        }
    }

    public record ConsultationCommand(
            String requestKey,
            Branch branch,
            StaffProfile preferredDoctor,
            Service service,
            String guestName,
            String guestPhone,
            LocalDate preferredDate,
            PreferredSession preferredSession,
            LocalTime preferredTime,
            String notes
    ) {}

    public record GuestSubmissionOutcome(
            boolean consultationMode,
            String loginUrl,
            String message,
            boolean createdNewConsultation
    ) {}

    public record ConsultationCreatedEvent(Long requestId, Long branchId) {}

    public static String normalizeVietnamPhone(String rawPhone) {
        if (rawPhone == null) {
            return "";
        }
        String digits = rawPhone.trim().replaceAll("[^0-9]", "");
        if (digits.startsWith("84") && (digits.length() == 11 || digits.length() == 12)) {
            digits = "0" + digits.substring(2);
        }
        return digits;
    }

    public static boolean isValidVietnamPhone(String normalizedPhone) {
        return normalizedPhone != null && VIETNAM_PHONE_PATTERN.matcher(normalizedPhone).matches();
    }

    public static boolean isValidRequestKey(String requestKey) {
        return requestKey != null && REQUEST_KEY_PATTERN.matcher(requestKey.trim()).matches();
    }

    public static List<String> phoneVariants(String normalizedPhone) {
        if (!isValidVietnamPhone(normalizedPhone)) {
            return List.of();
        }
        String withoutLeadingZero = normalizedPhone.substring(1);
        return List.of(normalizedPhone, "84" + withoutLeadingZero, "+84" + withoutLeadingZero);
    }

    public GuestSubmissionOutcome submitGuestConsultation(ConsultationCommand command) {
        if (command == null || !isValidRequestKey(command.requestKey())
                || command.branch() == null || command.branch().getBranchId() == null
                || !isValidVietnamPhone(command.guestPhone())
                || command.guestName() == null || command.guestName().isBlank()
                || command.preferredDate() == null || command.preferredSession() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin yêu cầu tư vấn không hợp lệ.");
        }

        String normalizedKey = command.requestKey().trim();
        try {
            if (writeTx != null) {
                return writeTx.execute(status -> persistOrReuseInTransaction(normalizedKey, command));
            }
            return persistOrReuseInTransaction(normalizedKey, command);
        } catch (DataIntegrityViolationException raceEx) {
            // Transaction ghi vừa bị rollback do đụng unique key đồng thời -> đọc lại ở transaction mới sạch sẽ
            return resolveConcurrentKeyReplay(normalizedKey, command);
        }
    }

    private GuestSubmissionOutcome persistOrReuseInTransaction(String requestKey, ConsultationCommand cmd) {
        // 1. Khóa bi quan theo chi nhánh trước để tuần tự hóa kiểm tra trùng mở (khác requestKey) & ghi nhận
        if (lockedBranches != null) {
            lockedBranches.findByIdForBooking(cmd.branch().getBranchId());
        }

        // 2. Idempotency theo requestKey
        Optional<BookingConsultationRequest> byKey = consultationRepository.findByRequestKey(requestKey);
        if (byKey.isPresent()) {
            BookingConsultationRequest existing = byKey.get();
            if (!matchesPayload(existing, cmd)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, REQUEST_KEY_CONFLICT_MESSAGE);
            }
            return neutralOutcome(false);
        }

        // 3. Kiểm tra SĐT đã tồn tại trong users hoặc patient_profiles -> tuyệt đối không tạo hồ sơ/lịch/tư vấn, trả thông báo trung lập
        if (phoneExistsInSystem(cmd.guestPhone())) {
            return neutralOutcome(false);
        }

        // 4. Chống tạo nhiều bản ghi trùng thông tin khi gửi với requestKey khác nhau cho cùng khách hàng & ca đang mở
        List<BookingConsultationRequest> existingOpen = consultationRepository
                .findByGuestPhoneAndBranch_BranchIdAndPreferredDateAndPreferredSessionAndStatusInOrderByRequestIdDesc(
                        cmd.guestPhone(),
                        cmd.branch().getBranchId(),
                        cmd.preferredDate(),
                        cmd.preferredSession(),
                        OPEN_STATUSES
                );
        if (existingOpen != null && !existingOpen.isEmpty()) {
            return neutralOutcome(false);
        }

        // 5. Tạo mới yêu cầu tư vấn riêng trong booking_consultation_requests
        BookingConsultationRequest request = new BookingConsultationRequest();
        request.setRequestCode("TV" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        request.setRequestKey(requestKey);
        request.setBranch(cmd.branch());
        request.setPreferredDoctor(cmd.preferredDoctor());
        request.setService(cmd.service());
        request.setGuestName(cmd.guestName().trim());
        request.setGuestPhone(cmd.guestPhone());
        request.setPreferredDate(cmd.preferredDate());
        request.setPreferredSession(cmd.preferredSession());
        request.setPreferredTime(cmd.preferredTime());
        request.setNotes(cmd.notes());
        request.setStatus(ConsultationRequestStatus.NEW);

        BookingConsultationRequest saved = consultationRepository.saveAndFlush(request);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ConsultationCreatedEvent(saved.getRequestId(), cmd.branch().getBranchId()));
        }
        return neutralOutcome(true);
    }

    private GuestSubmissionOutcome resolveConcurrentKeyReplay(String requestKey, ConsultationCommand cmd) {
        Optional<BookingConsultationRequest> existingOpt = readTx != null
                ? readTx.execute(status -> consultationRepository.findByRequestKey(requestKey))
                : consultationRepository.findByRequestKey(requestKey);
        if (existingOpt != null && existingOpt.isPresent()) {
            if (!matchesPayload(existingOpt.get(), cmd)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, REQUEST_KEY_CONFLICT_MESSAGE);
            }
            return neutralOutcome(false);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, REQUEST_KEY_CONFLICT_MESSAGE);
    }

    private boolean phoneExistsInSystem(String normalizedPhone) {
        List<String> variants = phoneVariants(normalizedPhone);
        if (variants.isEmpty()) {
            return false;
        }
        return userRepository.existsByPhoneNumberIn(variants)
                || patientProfileRepository.existsByPhoneNumberIn(variants);
    }

    private boolean matchesPayload(BookingConsultationRequest existing, ConsultationCommand cmd) {
        Long existingBranchId = existing.getBranch() != null ? existing.getBranch().getBranchId() : null;
        Long existingDoctorId = existing.getPreferredDoctor() != null ? existing.getPreferredDoctor().getStaffId() : null;
        Long existingServiceId = existing.getService() != null ? existing.getService().getServiceId() : null;
        Long cmdDoctorId = cmd.preferredDoctor() != null ? cmd.preferredDoctor().getStaffId() : null;
        Long cmdServiceId = cmd.service() != null ? cmd.service().getServiceId() : null;

        return Objects.equals(existingBranchId, cmd.branch().getBranchId())
                && Objects.equals(existingDoctorId, cmdDoctorId)
                && Objects.equals(existingServiceId, cmdServiceId)
                && Objects.equals(existing.getGuestName(), cmd.guestName().trim())
                && Objects.equals(existing.getGuestPhone(), cmd.guestPhone())
                && Objects.equals(existing.getPreferredDate(), cmd.preferredDate())
                && existing.getPreferredSession() == cmd.preferredSession()
                && Objects.equals(existing.getPreferredTime(), cmd.preferredTime())
                && Objects.equals(existing.getNotes(), cmd.notes());
    }

    private static GuestSubmissionOutcome neutralOutcome(boolean createdNewConsultation) {
        return new GuestSubmissionOutcome(true, "/login", NEUTRAL_GUEST_RESPONSE_MESSAGE, createdNewConsultation);
    }
}
