package com.cvdcms.service;

import com.cvdcms.DTO.ChatConversationDTO;
import com.cvdcms.DTO.ChatMessageDTO;
import com.cvdcms.DTO.ChatSendRequestDTO;
import com.cvdcms.DTO.ChatUserSearchItemDTO;
import com.cvdcms.entity.Branch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/**
 * Service xử lý nghiệp vụ Nhắn tin trực tiếp Realtime theo Cơ sở (Branch-Scoped Realtime Chat).
 * Không sử dụng AI Chatbot hay trả lời tự động bằng bot; chỉ hỗ trợ chat văn bản thực tế giữa người dùng và nhân viên.
 */
public interface ChatService {

    String GREETING_MESSAGE = "Xin chào! Bạn cần chúng tôi hỗ trợ điều gì?";
    String FIRST_MESSAGE_SYSTEM_NOTICE = "Cảm ơn bạn đã liên hệ! Tin nhắn của bạn đã được gửi đến đội ngũ nhân viên tại cơ sở. Vui lòng chờ trong giây lát, chúng tôi sẽ phản hồi sớm nhất có thể.";

    String resolvePrimaryRoleCode(Long userId);

    String toRoleLabel(String roleCode);

    String requireInboxAccess(Long userId);

    boolean canAccessBranchTopic(Long userId, Long branchId);

    boolean isAdminUser(Long userId);

    List<Branch> getAccessibleBranchesForInbox(Long userId);

    Map<String, Object> getUserChatContext(Long userId);

    Map<String, Object> getOrInitMyWidgetConversation(Long userId);

    List<ChatUserSearchItemDTO> searchRecipientUsers(
            Long currentUserId,
            String keyword,
            String roleFilter,
            Long branchId
    );

    Page<ChatUserSearchItemDTO> searchRecipientUsersPage(
            Long currentUserId,
            String keyword,
            String roleFilter,
            Long branchId,
            Pageable pageable
    );

    Map<String, Object> startOrGetConversationWithRecipient(
            Long currentUserId,
            Long targetUserId,
            Long branchId
    );

    Page<ChatConversationDTO> searchInboxConversations(
            Long currentUserId,
            String keyword,
            String initiatorRole,
            Long branchId,
            Boolean unreadOnly,
            Pageable pageable
    );

    Map<String, Object> getConversationMessages(Long currentUserId, Long conversationId);

    ChatMessageDTO sendMessage(Long currentUserId, ChatSendRequestDTO request);

    Map<String, Object> markConversationAsRead(Long currentUserId, Long conversationId);
}
