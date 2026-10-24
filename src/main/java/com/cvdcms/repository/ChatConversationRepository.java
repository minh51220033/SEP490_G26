package com.cvdcms.repository;

import com.cvdcms.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    /**
     * Lấy chi tiết cuộc hội thoại kèm User khởi tạo, Người nhận trực tiếp (nếu có) và Cơ sở (Branch).
     * Không JOIN FETCH trực tiếp patientProfile để tránh lỗi EntityNotFoundException nếu DB có profile_id mồ côi.
     */
    @Query("""
        SELECT c FROM ChatConversation c
        LEFT JOIN FETCH c.user u
        LEFT JOIN FETCH c.recipientUser r
        LEFT JOIN FETCH c.branch b
        WHERE c.conversationId = :conversationId
    """)
    Optional<ChatConversation> findByIdWithDetails(@Param("conversationId") Long conversationId);

    /**
     * Tìm các cuộc hội thoại hộp thư chung đang mở (recipientUser IS NULL, khác CLOSED) của một người dùng tại một cơ sở cụ thể,
     * sắp xếp mới nhất lên đầu.
     */
    @Query("""
        SELECT c FROM ChatConversation c
        LEFT JOIN FETCH c.user u
        LEFT JOIN FETCH c.recipientUser r
        LEFT JOIN FETCH c.branch b
        WHERE u.userId = :userId
          AND c.recipientUser IS NULL
          AND b.branchId = :branchId
          AND c.status <> com.cvdcms.entity.enums.ChatStatus.CLOSED
        ORDER BY c.lastMessageAt DESC, c.conversationId DESC
    """)
    List<ChatConversation> findActiveByUserIdAndBranchId(
            @Param("userId") Long userId,
            @Param("branchId") Long branchId
    );

    /**
     * Tìm các cuộc hội thoại trực tiếp 1-1 đang mở giữa 2 tài khoản (userA và userB) theo cả 2 chiều.
     */
    @Query("""
        SELECT c FROM ChatConversation c
        LEFT JOIN FETCH c.user u
        LEFT JOIN FETCH c.recipientUser r
        LEFT JOIN FETCH c.branch b
        WHERE c.recipientUser IS NOT NULL
          AND (
              (u.userId = :userAId AND r.userId = :userBId)
              OR (u.userId = :userBId AND r.userId = :userAId)
          )
          AND c.status <> com.cvdcms.entity.enums.ChatStatus.CLOSED
        ORDER BY c.lastMessageAt DESC, c.conversationId DESC
    """)
    List<ChatConversation> findActiveDirectConversationsBetweenUsers(
            @Param("userAId") Long userAId,
            @Param("userBId") Long userBId
    );

    /**
     * Tìm các cuộc hội thoại đang mở của người dùng nhưng chưa gắn branch_id (dữ liệu cũ nếu có),
     * để tự động gắn cơ sở hiện tại của tài khoản.
     */
    @Query("""
        SELECT c FROM ChatConversation c
        LEFT JOIN FETCH c.user u
        LEFT JOIN FETCH c.recipientUser r
        LEFT JOIN FETCH c.branch b
        WHERE u.userId = :userId
          AND c.recipientUser IS NULL
          AND c.branch IS NULL
          AND c.status <> com.cvdcms.entity.enums.ChatStatus.CLOSED
        ORDER BY c.lastMessageAt DESC, c.conversationId DESC
    """)
    List<ChatConversation> findActiveByUserIdWithNullBranch(@Param("userId") Long userId);

    /**
     * Lấy danh sách tất cả hội thoại kèm User khởi tạo, Người nhận trực tiếp (nếu có) và Branch,
     * phục vụ lọc theo cơ sở, vai trò người khởi tạo và tìm kiếm tại hộp thư nhân viên / Admin.
     */
    @Query("""
        SELECT DISTINCT c FROM ChatConversation c
        INNER JOIN FETCH c.user u
        LEFT JOIN FETCH c.recipientUser r
        LEFT JOIN FETCH c.branch b
        ORDER BY c.lastMessageAt DESC, c.conversationId DESC
    """)
    List<ChatConversation> findAllConversationsWithUserAndBranch();

    /**
     * Lấy danh sách các role đang hoạt động của một tài khoản.
     */
    @Query(value = """
        SELECT DISTINCT UPPER(TRIM(r.role_name))
        FROM users u
        JOIN user_roles ur ON ur.user_id = u.user_id
        JOIN roles r ON r.role_id = ur.role_id
        WHERE u.user_id = :userId
          AND u.is_active = 1
          AND UPPER(u.status) = 'ACTIVE'
          AND r.is_active = 1
    """, nativeQuery = true)
    List<String> findActiveRoleNamesByUserId(@Param("userId") Long userId);

    /**
     * Lấy danh sách branch_id được phân công cho nhân viên (từ staff_branch_assignments hoặc users.branch_id).
     */
    @Query(value = """
        SELECT DISTINCT b.branch_id
        FROM branches b
        WHERE b.is_active = 1
          AND (
              b.branch_id IN (
                  SELECT sba.branch_id
                  FROM staff_profiles sp
                  JOIN staff_branch_assignments sba ON sba.staff_id = sp.staff_id
                  WHERE sp.user_id = :userId
                    AND UPPER(sp.status) = 'ACTIVE'
                    AND UPPER(sba.status) = 'ACTIVE'
                    AND sba.start_date <= CURDATE()
                    AND (sba.end_date IS NULL OR sba.end_date >= CURDATE())
              )
              OR b.branch_id IN (
                  SELECT u.branch_id
                  FROM users u
                  WHERE u.user_id = :userId
                    AND u.branch_id IS NOT NULL
              )
          )
        ORDER BY b.branch_id ASC
    """, nativeQuery = true)
    List<Long> findExplicitBranchIdsByUserId(@Param("userId") Long userId);
}
