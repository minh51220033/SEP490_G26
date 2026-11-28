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
 * <p>
 * Không sử dụng AI Chatbot hay trả lời tự động bằng bot; chỉ hỗ trợ chat văn bản thực tế giữa người dùng và nhân viên.
 * Quản lý trạng thái đọc/xóa độc lập theo từng tài khoản qua {@code ChatMessageUserState} và
 * {@code ChatConversationUserState} (không làm thay đổi {@code ChatMessage.isRead}), đồng thời cho phép
 * chỉnh sửa/thu hồi tin nhắn chính chủ trong cửa sổ 10 phút ({@link #EDIT_RECALL_WINDOW_MINUTES}).
 */
public interface ChatService {

    /** Lời chào hiển thị ban đầu trên Floating Chat Widget. */
    String GREETING_MESSAGE = "Xin chào! Bạn cần chúng tôi hỗ trợ điều gì?";
    /** Thông báo hệ thống hiển thị sau tin nhắn đầu tiên của bệnh nhân trong hội thoại mới. */
    String FIRST_MESSAGE_SYSTEM_NOTICE = "Cảm ơn bạn đã liên hệ! Tin nhắn của bạn đã được gửi đến đội ngũ nhân viên tại cơ sở. Vui lòng chờ trong giây lát, chúng tôi sẽ phản hồi sớm nhất có thể.";
    /** Chuỗi thay thế nội dung hiển thị cho tin nhắn đã bị thu hồi. */
    String RECALLED_MESSAGE_PLACEHOLDER = "Tin nhắn đã được thu hồi";
    /** Thời hạn tối đa (tính bằng phút) cho phép người gửi chỉnh sửa hoặc thu hồi tin nhắn của mình. */
    long EDIT_RECALL_WINDOW_MINUTES = 10L;

    /** Xác định mã vai trò chính ưu tiên của tài khoản ({@code ADMIN}, {@code BAC_SI}, {@code LE_TAN}, {@code QUAN_LY_KHO}, {@code KE_TOAN}, {@code BENH_NHAN}). */
    String resolvePrimaryRoleCode(Long userId);

    /** Chuyển đổi mã vai trò hệ thống sang nhãn tiếng Việt hiển thị trên giao diện. */
    String toRoleLabel(String roleCode);

    /** Kiểm tra tài khoản đang hoạt động và có quyền truy cập Hộp thư nhân viên ({@code /chat}); từ chối Bệnh nhân (403). */
    String requireInboxAccess(Long userId);

    /** Kiểm tra người dùng có quyền đăng ký kênh STOMP theo cơ sở ({@code /topic/chat/branch/{branchId}}, CSKH/Queue branch) hay không. */
    boolean canAccessBranchTopic(Long userId, Long branchId);

    /** Kiểm tra tài khoản có vai trò chính là {@code ADMIN} hay không. */
    boolean isAdminUser(Long userId);

    /** Lấy danh sách cơ sở mà nhân viên / Admin được phép xem trong Hộp thư tin nhắn. */
    List<Branch> getAccessibleBranchesForInbox(Long userId);

    /** Lấy ngữ cảnh chat đầy đủ cho tài khoản hiện tại (vai trò, cơ sở, cờ quyền hạn, tổng tin chưa đọc). */
    Map<String, Object> getUserChatContext(Long userId);

    /** Lấy hoặc khởi tạo dữ liệu cuộc hội thoại hiện tại của người dùng trên Floating Chat Widget. */
    Map<String, Object> getOrInitMyWidgetConversation(Long userId);

    /** Tìm kiếm danh sách tài khoản người nhận hợp lệ trong cùng cơ sở (chỉ dành cho nhân viên/Admin). */
    List<ChatUserSearchItemDTO> searchRecipientUsers(
            Long currentUserId,
            String keyword,
            String roleFilter,
            Long branchId
    );

    /** Tìm kiếm tài khoản người nhận trong cùng cơ sở có phân trang. */
    Page<ChatUserSearchItemDTO> searchRecipientUsersPage(
            Long currentUserId,
            String keyword,
            String roleFilter,
            Long branchId,
            Pageable pageable
    );

    /** Mở lại hoặc tạo mới cuộc hội thoại với một tài khoản người nhận cụ thể trong cùng cơ sở. */
    Map<String, Object> startOrGetConversationWithRecipient(
            Long currentUserId,
            Long targetUserId,
            Long branchId
    );

    /** Tìm kiếm, lọc (từ khóa, vai trò, cơ sở, chỉ chưa đọc) và phân trang danh sách hội thoại trong Hộp thư. */
    Page<ChatConversationDTO> searchInboxConversations(
            Long currentUserId,
            String keyword,
            String initiatorRole,
            Long branchId,
            Boolean unreadOnly,
            Pageable pageable
    );

    /** Lấy lịch sử tin nhắn hiển thị riêng cho tài khoản hiện tại (đã lọc bỏ tin người dùng tự xóa ở phía mình). */
    Map<String, Object> getConversationMessages(Long currentUserId, Long conversationId);

    /** Lưu tin nhắn mới vào MySQL (kèm chống trùng theo {@code clientMessageId}) và trả về DTO để phát STOMP. */
    ChatMessageDTO sendMessage(Long currentUserId, ChatSendRequestDTO request);

    /** Đánh dấu đã đọc tất cả tin nhắn trong cuộc hội thoại chỉ riêng cho tài khoản hiện tại qua bảng state cá nhân. */
    Map<String, Object> markConversationAsRead(Long currentUserId, Long conversationId);

    /** Đếm tổng số tin nhắn chưa đọc trên toàn bộ hội thoại thuộc phạm vi của tài khoản hiện tại. */
    long getTotalUnreadCount(Long currentUserId);

    /** Chỉnh sửa nội dung tin nhắn chính chủ trong cửa sổ 10 phút và trả về DTO sự kiện {@code EDIT}. */
    ChatMessageDTO editMessage(Long currentUserId, Long messageId, String newMessageText);

    /** Thu hồi (gỡ) tin nhắn chính chủ trong cửa sổ 10 phút và trả về DTO sự kiện {@code RECALL}. */
    ChatMessageDTO recallMessage(Long currentUserId, Long messageId);

    /** Xóa (ẩn) một tin nhắn chỉ ở phía tài khoản hiện tại qua {@code ChatMessageUserState}. */
    Map<String, Object> deleteMessageForUser(Long currentUserId, Long messageId);

    /** Xóa (ẩn) toàn bộ cuộc hội thoại đến thời điểm hiện tại chỉ ở phía tài khoản hiện tại qua {@code ChatConversationUserState}. */
    Map<String, Object> deleteConversationForUser(Long currentUserId, Long conversationId);
}
