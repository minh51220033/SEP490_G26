package com.cvdcms.util;

import com.cvdcms.entity.enums.EmrRecordStatus;
import com.cvdcms.repository.EmrRecordRepository;

import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;

import java.util.EnumSet;
import java.util.Set;

public final class EmrStatePolicy {
    private EmrStatePolicy() {}

    private static final Set<EmrRecordStatus> EDITABLE =
            EnumSet.of(EmrRecordStatus.DRAFT, EmrRecordStatus.AMENDMENT_DRAFT, EmrRecordStatus.READY_TO_LOCK);

    public static boolean canRefresh(EmrRecordStatus status) {
        return EDITABLE.contains(status);
    }

    public static boolean canValidate(EmrRecordStatus status) {
        return status == EmrRecordStatus.DRAFT
                || status == EmrRecordStatus.AMENDMENT_DRAFT
                || status == EmrRecordStatus.READY_TO_LOCK;
    }

    public static boolean canLock(EmrRecordStatus status) {
        return status == EmrRecordStatus.READY_TO_LOCK;
    }

    public static boolean canDoctorSign(EmrRecordStatus status) {
        return status == EmrRecordStatus.DOCTOR_SIGNATURE_PENDING
                || status == EmrRecordStatus.PATIENT_SIGNATURE_PENDING;
    }

    public static boolean canAmend(EmrRecordStatus status) {
        return status == EmrRecordStatus.SIGNED;
    }

    public static boolean isEmrLockedStatus(EmrRecordStatus status) {
        return status == EmrRecordStatus.PATIENT_SIGNATURE_PENDING
                || status == EmrRecordStatus.DOCTOR_SIGNATURE_PENDING
                || status == EmrRecordStatus.SIGNED;
    }

    /**
     * Enforces BR-EMR-02 immutability on both visit lifecycle statuses (WAITING_PAYMENT, COMPLETED, CANCELLED)
     * and locked/signed EMR records so clinical services cannot mutate frozen medical charts.
     */
    public static void assertMutableVisitAndEmr(Visit visit, EmrRecordRepository emrRecordRepository, String blockedMessage) {
        if (visit == null) {
            return;
        }
        if (visit.getStatus() == VisitStatus.WAITING) {
            throw new IllegalStateException(
                    "Bệnh nhân đang ở trạng thái Đang chờ. Vui lòng đợi hệ thống gọi số và bấm nút 'Vào khám' trước khi thao tác.");
        }
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT
                || visit.getStatus() == VisitStatus.COMPLETED
                || visit.getStatus() == VisitStatus.CANCELLED) {
            throw new IllegalStateException(blockedMessage + " (BR-EMR-02).");
        }
        if (emrRecordRepository != null && visit.getVisitId() != null) {
            emrRecordRepository.findByVisit_VisitId(visit.getVisitId()).ifPresent(record -> {
                if (isEmrLockedStatus(record.getStatus())) {
                    throw new IllegalStateException(
                            "Hồ sơ bệnh án điện tử (" + record.getRecordCode()
                                    + ") đã khóa/ký xác thực. Không thể sửa trực tiếp dữ liệu lâm sàng (BR-EMR-02).");
                }
            });
        }
    }
}
