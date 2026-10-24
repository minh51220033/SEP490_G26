package com.cvdcms.repository;

import com.cvdcms.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * Lấy toàn bộ lịch sử tin nhắn của một cuộc hội thoại theo thứ tự thời gian tăng dần.
     */
    @Query("""
        SELECT m FROM ChatMessage m
        WHERE m.conversation.conversationId = :conversationId
        ORDER BY m.sentAt ASC, m.messageId ASC
    """)
    List<ChatMessage> findByConversationIdOrderBySentAtAsc(@Param("conversationId") Long conversationId);

    /**
     * Đếm tổng số tin nhắn trong một cuộc hội thoại.
     */
    long countByConversation_ConversationId(Long conversationId);

    /**
     * Lấy tin nhắn mới nhất của từng cuộc hội thoại trong danh sách (tránh N+1 query).
     */
    @Query("""
        SELECT m FROM ChatMessage m
        WHERE m.conversation.conversationId IN :conversationIds
          AND m.messageId IN (
              SELECT MAX(m2.messageId)
              FROM ChatMessage m2
              WHERE m2.conversation.conversationId IN :conversationIds
              GROUP BY m2.conversation.conversationId
          )
    """)
    List<ChatMessage> findLatestMessagesByConversationIds(@Param("conversationIds") Collection<Long> conversationIds);

    /**
     * Đếm số tin nhắn chưa đọc đối với nhân viên/Admin cho các hội thoại chung của cơ sở
     * (tin nhắn do người khởi tạo gửi và isRead = false).
     */
    @Query("""
        SELECT m.conversation.conversationId, COUNT(m.messageId)
        FROM ChatMessage m
        WHERE m.conversation.conversationId IN :conversationIds
          AND m.isRead = false
          AND m.senderId = m.conversation.user.userId
        GROUP BY m.conversation.conversationId
    """)
    List<Object[]> countUnreadForStaffByConversationIds(@Param("conversationIds") Collection<Long> conversationIds);

    /**
     * Đếm số tin nhắn chưa đọc đối với người đang xem (viewerUserId) trong các cuộc hội thoại trực tiếp 1-1
     * (tin nhắn không phải do chính viewerUserId gửi và isRead = false).
     */
    @Query("""
        SELECT m.conversation.conversationId, COUNT(m.messageId)
        FROM ChatMessage m
        WHERE m.conversation.conversationId IN :conversationIds
          AND m.isRead = false
          AND (m.senderId IS NULL OR m.senderId <> :viewerUserId)
        GROUP BY m.conversation.conversationId
    """)
    List<Object[]> countUnreadForDirectViewerByConversationIds(
            @Param("conversationIds") Collection<Long> conversationIds,
            @Param("viewerUserId") Long viewerUserId
    );

    /**
     * Đếm số tin nhắn chưa đọc đối với người khởi tạo (tin nhắn do nhân viên/Admin phản hồi và isRead = false).
     */
    @Query("""
        SELECT COUNT(m.messageId)
        FROM ChatMessage m
        WHERE m.conversation.conversationId = :conversationId
          AND m.isRead = false
          AND (m.senderId IS NULL OR m.senderId <> :initiatorUserId)
    """)
    long countUnreadForInitiator(
            @Param("conversationId") Long conversationId,
            @Param("initiatorUserId") Long initiatorUserId
    );

    /**
     * Đánh dấu đã đọc cho các tin nhắn từ người khởi tạo khi nhân viên hoặc Admin mở hội thoại chung.
     */
    @Modifying
    @Query("""
        UPDATE ChatMessage m
        SET m.isRead = true
        WHERE m.conversation.conversationId = :conversationId
          AND m.isRead = false
          AND m.senderId = :initiatorUserId
    """)
    int markMessagesAsReadForStaff(
            @Param("conversationId") Long conversationId,
            @Param("initiatorUserId") Long initiatorUserId
    );

    /**
     * Đánh dấu đã đọc cho các tin nhắn từ đối phương khi người dùng mở hội thoại.
     */
    @Modifying
    @Query("""
        UPDATE ChatMessage m
        SET m.isRead = true
        WHERE m.conversation.conversationId = :conversationId
          AND m.isRead = false
          AND (m.senderId IS NULL OR m.senderId <> :viewerUserId)
    """)
    int markMessagesAsReadForInitiator(
            @Param("conversationId") Long conversationId,
            @Param("viewerUserId") Long viewerUserId
    );

    /**
     * Tìm danh sách conversationId có chứa nội dung tin nhắn khớp với từ khóa tìm kiếm (không phân biệt hoa thường).
     */
    @Query("""
        SELECT DISTINCT m.conversation.conversationId
        FROM ChatMessage m
        WHERE LOWER(m.messageText) LIKE LOWER(CONCAT('%', :keyword, '%'))
    """)
    List<Long> findConversationIdsByMessageKeyword(@Param("keyword") String keyword);
}
