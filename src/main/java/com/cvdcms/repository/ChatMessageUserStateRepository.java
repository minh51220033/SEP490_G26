package com.cvdcms.repository;

import com.cvdcms.entity.ChatMessageUserState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository quản lý trạng thái riêng theo từng người dùng đối với từng tin nhắn ({@link ChatMessageUserState}),
 * bao gồm cờ xóa tin nhắn ở phía tài khoản hiện tại ({@code deleted}) mà không ảnh hưởng tới người khác
 * và không làm thay đổi trực tiếp {@code ChatMessage.isRead}.
 */
public interface ChatMessageUserStateRepository extends JpaRepository<ChatMessageUserState, Long> {

    /**
     * Tìm bản ghi trạng thái cá nhân của một tài khoản đối với một tin nhắn cụ thể.
     */
    Optional<ChatMessageUserState> findByMessageIdAndUserId(Long messageId, Long userId);

    /**
     * Lấy toàn bộ trạng thái tin nhắn cá nhân của một tài khoản trong một cuộc hội thoại
     * (dùng để lọc bỏ các tin nhắn người dùng đã xóa ở phía mình).
     */
    List<ChatMessageUserState> findByConversationIdAndUserId(Long conversationId, Long userId);

    /**
     * Lấy hàng loạt trạng thái tin nhắn cá nhân của một tài khoản trên nhiều cuộc hội thoại (tránh N+1 query).
     */
    List<ChatMessageUserState> findByUserIdAndConversationIdIn(Long userId, Collection<Long> conversationIds);
}
