package com.cvdcms.repository;

import com.cvdcms.entity.CustomerCareTask;
import com.cvdcms.entity.Prescription;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.CustomerCareTaskStatus;
import com.cvdcms.entity.enums.CustomerCareTaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository truy vấn dữ liệu bảng công việc chăm sóc khách hàng (customer_care_tasks - FT44)
 * kèm các truy vấn an toàn (JOIN FETCH kiểm tra khoá ngoại thực tế) phục vụ tổng hợp tự động và phân quyền cơ sở.
 */
@Repository
public interface CustomerCareTaskRepository extends JpaRepository<CustomerCareTask, Long> {

    /**
     * Kiểm tra mã công việc CSKH đã tồn tại hay chưa.
     */
    boolean existsByTaskCode(String taskCode);

    /**
     * Kiểm tra đã tạo công việc CSKH cho lượt khám và loại công việc tương ứng hay chưa (tránh trùng khi quét tự động).
     */
    boolean existsByVisit_VisitIdAndTaskType(Long visitId, CustomerCareTaskType taskType);

    /**
     * Kiểm tra đã tạo công việc CSKH cho lịch hẹn và loại công việc tương ứng hay chưa.
     */
    boolean existsByAppointment_AppointmentIdAndTaskType(Long appointmentId, CustomerCareTaskType taskType);

    /**
     * Kiểm tra đã tạo công việc CSKH cho đơn thuốc và loại công việc tương ứng hay chưa.
     */
    boolean existsByPrescription_PrescriptionIdAndTaskType(Long prescriptionId, CustomerCareTaskType taskType);

    /**
     * Lấy chi tiết một công việc CSKH kèm đầy đủ thông tin bệnh nhân, chi nhánh, nhân viên, bác sĩ, lượt khám, lịch hẹn, đơn thuốc.
     */
    @Query("""
        SELECT t
        FROM CustomerCareTask t
        JOIN FETCH t.patientProfile p
        JOIN FETCH t.branch b
        LEFT JOIN FETCH t.assignedStaff s
        LEFT JOIN FETCH t.escalatedToDoctor d
        LEFT JOIN FETCH t.visit v
        LEFT JOIN FETCH v.patientProfile vp
        LEFT JOIN FETCH t.appointment a
        LEFT JOIN FETCH a.patientProfile ap
        LEFT JOIN FETCH a.service srv
        LEFT JOIN FETCH t.prescription pr
        LEFT JOIN FETCH pr.patientProfile prp
        WHERE t.taskId = :taskId
        """)
    Optional<CustomerCareTask> findByIdWithDetails(@Param("taskId") Long taskId);

    /**
     * Lấy toàn bộ công việc CSKH của một chi nhánh (hoặc tất cả chi nhánh nếu branchId = null) kèm thông tin liên kết.
     */
    @Query("""
        SELECT t
        FROM CustomerCareTask t
        JOIN FETCH t.patientProfile p
        JOIN FETCH t.branch b
        LEFT JOIN FETCH t.assignedStaff s
        LEFT JOIN FETCH t.escalatedToDoctor d
        LEFT JOIN FETCH t.visit v
        LEFT JOIN FETCH v.patientProfile vp
        LEFT JOIN FETCH t.appointment a
        LEFT JOIN FETCH a.patientProfile ap
        LEFT JOIN FETCH a.service srv
        LEFT JOIN FETCH t.prescription pr
        LEFT JOIN FETCH pr.patientProfile prp
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
        ORDER BY t.dueDate DESC, t.taskId DESC
        """)
    List<CustomerCareTask> findAllByBranchWithDetails(@Param("branchId") Long branchId);

    /**
     * Lấy danh sách công việc CSKH của một bệnh nhân cụ thể (phục vụ SCR-44.6).
     */
    @Query("""
        SELECT t
        FROM CustomerCareTask t
        JOIN FETCH t.patientProfile p
        JOIN FETCH t.branch b
        LEFT JOIN FETCH t.assignedStaff s
        LEFT JOIN FETCH t.escalatedToDoctor d
        LEFT JOIN FETCH t.visit v
        LEFT JOIN FETCH v.patientProfile vp
        LEFT JOIN FETCH t.appointment a
        LEFT JOIN FETCH a.patientProfile ap
        LEFT JOIN FETCH a.service srv
        LEFT JOIN FETCH t.prescription pr
        LEFT JOIN FETCH pr.patientProfile prp
        WHERE p.profileId = :profileId
        ORDER BY t.dueDate DESC, t.taskId DESC
        """)
    List<CustomerCareTask> findByPatientProfileIdWithDetails(@Param("profileId") Long profileId);

    /**
     * Truy vấn các ca khám COMPLETED hợp lệ tại cơ sở (có thật hồ sơ bệnh nhân trong bảng patient_profiles
     * và chi nhánh trong bảng branches) để tránh lỗi EntityNotFoundException (ví dụ profile_id = 201 không tồn tại).
     */
    @Query("""
        SELECT v
        FROM Visit v
        JOIN FETCH v.patientProfile p
        JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
          AND v.status = com.cvdcms.entity.enums.VisitStatus.COMPLETED
          AND (v.primaryDoctor IS NULL OR d.staffId IS NOT NULL)
        ORDER BY v.checkInAt DESC
        """)
    List<Visit> findCompletedVisitsByBranchForCare(@Param("branchId") Long branchId);

    /**
     * Truy vấn danh sách ca khám hợp lệ tại cơ sở để hiển thị trong dropdown Lập lịch CSKH.
     */
    @Query("""
        SELECT v
        FROM Visit v
        JOIN FETCH v.patientProfile p
        JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
          AND (v.primaryDoctor IS NULL OR d.staffId IS NOT NULL)
        ORDER BY v.checkInAt DESC
        """)
    List<Visit> findValidVisitsByBranchForLookup(@Param("branchId") Long branchId);

    /**
     * Truy vấn danh sách đơn thuốc hợp lệ tại cơ sở (bắt buộc tồn tại patientProfile, branch, visit và doctor)
     * để tránh lỗi EntityNotFoundException khi tổng hợp tự động hoặc tải danh mục.
     */
    @Query("""
        SELECT pr
        FROM Prescription pr
        JOIN FETCH pr.patientProfile p
        JOIN FETCH pr.branch b
        JOIN FETCH pr.visit v
        JOIN FETCH v.patientProfile vp
        JOIN FETCH v.branch vb
        JOIN FETCH pr.doctor d
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
        ORDER BY pr.prescriptionId DESC
        """)
    List<Prescription> findValidPrescriptionsByBranchForCare(@Param("branchId") Long branchId);

    /**
     * Lấy danh sách tên vai trò (role_name) đang hoạt động của một tài khoản từ database.
     */
    @Query(value = """
        SELECT DISTINCT UPPER(r.role_name)
        FROM users u
        JOIN user_roles ur ON ur.user_id = u.user_id
        JOIN roles r ON r.role_id = ur.role_id
        WHERE u.user_id = :userId
          AND u.is_active = 1
          AND (u.status IS NULL OR UPPER(u.status) = 'ACTIVE')
          AND r.is_active = 1
        """, nativeQuery = true)
    List<String> findActiveRoleNamesByUserId(@Param("userId") Long userId);

    /**
     * Lấy danh sách ID cơ sở (branch_id) được phân công chính thức cho nhân viên thông qua
     * bảng staff_branch_assignments (còn hiệu lực) hoặc cột users.branch_id.
     */
    @Query(value = """
        SELECT DISTINCT b.branch_id
        FROM branches b
        WHERE b.is_active = 1
          AND (
              EXISTS (
                  SELECT 1
                  FROM users u
                  WHERE u.user_id = :userId
                    AND u.branch_id = b.branch_id
              )
              OR EXISTS (
                  SELECT 1
                  FROM staff_profiles sp
                  JOIN staff_branch_assignments sba ON sba.staff_id = sp.staff_id
                  WHERE sp.user_id = :userId
                    AND UPPER(sp.status) = 'ACTIVE'
                    AND UPPER(sba.status) = 'ACTIVE'
                    AND sba.start_date <= CURDATE()
                    AND (sba.end_date IS NULL OR sba.end_date >= CURDATE())
                    AND sba.branch_id = b.branch_id
              )
          )
        ORDER BY b.branch_id ASC
        """, nativeQuery = true)
    List<Long> findExplicitBranchIdsByUserId(@Param("userId") Long userId);

    /**
     * Truy vấn dự phòng (fallback) lấy danh sách branch_id mà nhân viên/bác sĩ (staff_id) đang phụ trách
     * trong bảng customer_care_tasks, visits, appointments, prescriptions (hỗ trợ dữ liệu seed chưa chèn staff_branch_assignments).
     */
    @Query(value = """
        SELECT DISTINCT branch_id FROM (
            SELECT branch_id FROM customer_care_tasks WHERE assigned_staff_id = :staffId OR escalated_to_doctor_id = :staffId
            UNION
            SELECT branch_id FROM visits WHERE primary_doctor_id = :staffId
            UNION
            SELECT branch_id FROM appointments WHERE doctor_id = :staffId
            UNION
            SELECT branch_id FROM prescriptions WHERE doctor_id = :staffId
        ) AS staff_branches
        WHERE branch_id IS NOT NULL
        ORDER BY branch_id ASC
        """, nativeQuery = true)
    List<Long> findInferredBranchIdsByStaffId(@Param("staffId") Long staffId);

    /**
     * Lấy danh sách staff_id thuộc một cơ sở cụ thể (từ staff_branch_assignments, users.branch_id hoặc dữ liệu tác nghiệp tại cơ sở).
     */
    @Query(value = """
        SELECT DISTINCT sp.staff_id
        FROM staff_profiles sp
        LEFT JOIN users u ON u.user_id = sp.user_id
        WHERE UPPER(sp.status) = 'ACTIVE'
          AND (
              u.branch_id = :branchId
              OR EXISTS (
                  SELECT 1 FROM staff_branch_assignments sba
                  WHERE sba.staff_id = sp.staff_id
                    AND sba.branch_id = :branchId
                    AND UPPER(sba.status) = 'ACTIVE'
                    AND sba.start_date <= CURDATE()
                    AND (sba.end_date IS NULL OR sba.end_date >= CURDATE())
              )
              OR (
                  NOT EXISTS (
                      SELECT 1 FROM staff_branch_assignments sba2
                      WHERE sba2.staff_id = sp.staff_id
                        AND UPPER(sba2.status) = 'ACTIVE'
                  )
                  AND u.branch_id IS NULL
                  AND (
                      EXISTS (SELECT 1 FROM customer_care_tasks cct WHERE (cct.assigned_staff_id = sp.staff_id OR cct.escalated_to_doctor_id = sp.staff_id) AND cct.branch_id = :branchId)
                      OR EXISTS (SELECT 1 FROM visits v WHERE v.primary_doctor_id = sp.staff_id AND v.branch_id = :branchId)
                      OR EXISTS (SELECT 1 FROM appointments a WHERE a.doctor_id = sp.staff_id AND a.branch_id = :branchId)
                  )
              )
          )
        """, nativeQuery = true)
    List<Long> findActiveStaffIdsByBranch(@Param("branchId") Long branchId);

    /**
     * Đếm tổng số công việc CSKH tại chi nhánh (phục vụ KPI Card 1).
     */
    long countByBranch_BranchId(Long branchId);

    /**
     * Đếm số công việc CSKH theo trạng thái tại chi nhánh (phục vụ KPI Cards).
     */
    long countByBranch_BranchIdAndStatus(Long branchId, CustomerCareTaskStatus status);

    /**
     * Đếm số công việc CSKH đang chờ/đang thực hiện nhưng đã đến hạn hoặc quá hạn (due_date <= today).
     */
    @Query("""
        SELECT COUNT(t)
        FROM CustomerCareTask t
        WHERE (:branchId IS NULL OR t.branch.branchId = :branchId)
          AND t.status IN :activeStatuses
          AND t.dueDate <= :today
        """)
    long countDueOrOverdueTasks(
            @Param("branchId") Long branchId,
            @Param("activeStatuses") List<CustomerCareTaskStatus> activeStatuses,
            @Param("today") LocalDate today
    );
}
