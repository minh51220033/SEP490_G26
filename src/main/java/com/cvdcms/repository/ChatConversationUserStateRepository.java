package com.cvdcms.repository;

import com.cvdcms.entity.ChatConversationUserState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository quản lý trạng thái riêng theo từng người dùng đối với từng cuộc hội thoại
 * ({@link ChatConversationUserState}), bao gồm mốc thời gian đọc gần nhất ({@code lastReadAt} / {@code lastReadMessageId})
 * và mốc xóa cuộc hội thoại ở phía tài khoản hiện tại ({@code clearedAt}) mà không ảnh hưởng tới các tài khoản khác.
 */
public interface ChatConversationUserStateRepository extends JpaRepository<ChatConversationUserState, Long> {

    /**
     * Tìm trạng thái cá nhân của một tài khoản đối với một cuộc hội thoại cụ thể.
     */
    Optional<ChatConversationUserState> findByConversationIdAndUserId(Long conversationId, Long userId);

    /**
     * Lấy hàng loạt trạng thái cá nhân của một tài khoản cho danh sách cuộc hội thoại (tránh N+1 query
     * khi tính số tin chưa đọc và lọc hội thoại đã xóa phía người dùng trên danh sách Hộp thư).
     */
    List<ChatConversationUserState> findByUserIdAndConversationIdIn(Long userId, Collection<Long> conversationIds);
}
