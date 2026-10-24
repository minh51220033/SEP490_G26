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

    /**
     * Xác định mã vai trò chính của người dùng theo thứ tự ưu tiên:
     * ADMIN -> BAC_SI -> LE_TAN -> QUAN_LY_KHO -> KE_TOAN -> BENH_NHAN.
     */
    String resolvePrimaryRoleCode(Long userId);

    /**
     * Chuyển đổi mã vai trò sang nhãn hiển thị tiếng Việt.
     */
    String toRoleLabel(String roleCode);

    /**
     * Kiểm tra quyền truy cập Hộp thư tin nhắn (/chat) và quyền tìm kiếm người nhận trong cơ sở:
     * - Cho phép: ADMIN, LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN.
     * - Từ chối (403 FORBIDDEN): BENH_NHAN hoặc tài khoản bị khóa.
     */
    String requireInboxAccess(Long userId);

    /**
     * Kiểm tra người dùng có được phép đăng ký nhận sự kiện STOMP của một cơ sở (/topic/chat/branch/{branchId}) hay không.
     */
    boolean canAccessBranchTopic(Long userId, Long branchId);

    /**
     * Kiểm tra người dùng có phải là ADMIN để đăng ký /topic/chat/admin hay không.
     */
    boolean isAdminUser(Long userId);

    /**
     * Lấy danh sách các cơ sở mà nhân viên hoặc Admin được phép xem trong hộp thư.
     */
    List<Branch> getAccessibleBranchesForInbox(Long userId);

    /**
     * Lấy thông tin ngữ cảnh chat của người dùng đang đăng nhập (dùng cho cả Floating Widget và trang Inbox).
     */
    Map<String, Object> getUserChatContext(Long userId);

    /**
     * Lấy hoặc khởi tạo cuộc hội thoại tại cơ sở của người dùng đang đăng nhập (dùng cho Floating Widget).
     */
    Map<String, Object> getOrInitMyWidgetConversation(Long userId);

    /**
     * Tìm kiếm tất cả các tài khoản trong cùng cơ sở để gửi tin nhắn (Chỉ dành cho các vai trò khác Bệnh nhân:
     * LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN, ADMIN; Bệnh nhân gọi sẽ bị chặn 403 FORBIDDEN).
     */
    List<ChatUserSearchItemDTO> searchRecipientUsers(
            Long currentUserId,
            String keyword,
            String roleFilter,
            Long branchId
    );

    /**
     * Mở hoặc khởi tạo cuộc hội thoại trực tiếp với một tài khoản người nhận cụ thể trong cùng cơ sở
     * (Chỉ dành cho các vai trò khác Bệnh nhân: LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN, ADMIN).
     */
    Map<String, Object> startOrGetConversationWithRecipient(
            Long currentUserId,
            Long targetUserId,
            Long branchId
    );

    /**
     * Tìm kiếm, lọc và phân trang danh sách cuộc hội thoại tại Hộp thư nhân viên / Admin.
     * Kết hợp đầy đủ điều kiện AND: keyword + initiatorRole + branchId + unreadOnly, sắp xếp lastMessageAt DESC.
     */
    Page<ChatConversationDTO> searchInboxConversations(
            Long currentUserId,
            String keyword,
            String initiatorRole,
            Long branchId,
            Boolean unreadOnly,
            Pageable pageable
    );

    /**
     * Lấy lịch sử tin nhắn của một cuộc hội thoại (có kiểm tra quyền truy cập theo cơ sở hoặc chủ sở hữu hội thoại).
     */
    Map<String, Object> getConversationMessages(Long currentUserId, Long conversationId);

    /**
     * Gửi tin nhắn mới (lưu vào MySQL trước khi phát sự kiện STOMP).
     * Danh tính người gửi luôn lấy từ tài khoản đăng nhập thực tế (currentUserId).
     */
    ChatMessageDTO sendMessage(Long currentUserId, ChatSendRequestDTO request);

    /**
     * Đánh dấu các tin nhắn trong cuộc hội thoại là đã đọc đối với người đang xem.
     */
    Map<String, Object> markConversationAsRead(Long currentUserId, Long conversationId);
}
