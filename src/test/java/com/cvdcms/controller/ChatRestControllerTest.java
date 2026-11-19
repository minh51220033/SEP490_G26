package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.User;
import com.cvdcms.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRestControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private ChatWebSocketController chatWebSocketController;

    @InjectMocks
    private ChatRestController controller;

    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(10L);
        user.setUsername("letan01");
        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
    }

    @Test
    void getChatContext_ReturnsUnauthenticatedMap_WhenSessionMissingOrServiceThrows() {
        ResponseEntity<Map<String, Object>> noSession = controller.getChatContext(null);
        assertEquals(false, noSession.getBody().get("authenticated"));
        assertEquals(0L, noSession.getBody().get("totalUnreadCount"));

        when(chatService.getUserChatContext(10L)).thenThrow(new RuntimeException("Inactive user"));
        ResponseEntity<Map<String, Object>> errRes = controller.getChatContext(session);
        assertEquals(false, errRes.getBody().get("authenticated"));
        assertEquals(0L, errRes.getBody().get("totalUnreadCount"));
    }

    @Test
    void getChatContext_ReturnsServiceContext_WhenAuthenticated() {
        when(chatService.getUserChatContext(10L)).thenReturn(Map.of("authenticated", true, "userId", 10L));
        ResponseEntity<Map<String, Object>> res = controller.getChatContext(session);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(true, res.getBody().get("authenticated"));
    }

    @Test
    void getUnreadCount_HandlesUnauthenticatedErrorAndAuthenticated() {
        assertEquals(false, controller.getUnreadCount(new MockHttpSession()).getBody().get("authenticated"));

        when(chatService.getTotalUnreadCount(10L)).thenReturn(7L);
        Map<String, Object> ok = controller.getUnreadCount(session).getBody();
        assertEquals(true, ok.get("authenticated"));
        assertEquals(7L, ok.get("totalUnreadCount"));

        when(chatService.getTotalUnreadCount(10L)).thenThrow(new RuntimeException("DB fail"));
        assertEquals(false, controller.getUnreadCount(session).getBody().get("authenticated"));
    }

    @Test
    void getMyWidgetConversation_RequiresLoginAndDelegates() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> controller.getMyWidgetConversation(null)).getStatusCode());

        when(chatService.getOrInitMyWidgetConversation(10L)).thenReturn(Map.of("conversationId", 55L));
        assertEquals(55L, controller.getMyWidgetConversation(session).getBody().get("conversationId"));
    }

    @Test
    void searchRecipientUsers_SupportsUnpagedAndPagedModesWithPaginationHeaders() {
        List<ChatUserSearchItemDTO> unpaged = List.of(ChatUserSearchItemDTO.builder().userId(20L).build());
        when(chatService.searchRecipientUsers(10L, "bs", "BAC_SI", 1L)).thenReturn(unpaged);

        ResponseEntity<List<ChatUserSearchItemDTO>> unpagedRes =
                controller.searchRecipientUsers("bs", "BAC_SI", 1L, null, null, session);
        assertEquals(1, unpagedRes.getBody().size());

        Page<ChatUserSearchItemDTO> page = new PageImpl<>(unpaged);
        when(chatService.searchRecipientUsersPage(eq(10L), eq("bs"), eq("BAC_SI"), eq(1L), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<List<ChatUserSearchItemDTO>> pagedRes =
                controller.searchRecipientUsers("bs", "BAC_SI", 1L, -2, 500, session);
        assertEquals("1", pagedRes.getHeaders().getFirst("X-Total-Count"));
        assertEquals("1", pagedRes.getHeaders().getFirst("X-Total-Pages"));
        assertEquals("false", pagedRes.getHeaders().getFirst("X-Has-More"));

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(chatService).searchRecipientUsersPage(eq(10L), eq("bs"), eq("BAC_SI"), eq(1L), cap.capture());
        assertEquals(0, cap.getValue().getPageNumber());
        assertEquals(100, cap.getValue().getPageSize());
    }

    @Test
    void searchRecipientUsersPage_ClampsBoundsAndReturnsPage() {
        Page<ChatUserSearchItemDTO> page = new PageImpl<>(List.of());
        when(chatService.searchRecipientUsersPage(eq(10L), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<Page<ChatUserSearchItemDTO>> res =
                controller.searchRecipientUsersPage(null, null, null, -1, 0, session);
        assertSame(page, res.getBody());

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(chatService).searchRecipientUsersPage(eq(10L), isNull(), isNull(), isNull(), cap.capture());
        assertEquals(0, cap.getValue().getPageNumber());
        assertEquals(1, cap.getValue().getPageSize());
    }

    @Test
    void startConversationWithRecipient_ParsesNumberAndStringIdentifiers() {
        when(chatService.startOrGetConversationWithRecipient(10L, 25L, 2L))
                .thenReturn(Map.of("conversationId", 101L));

        Map<String, Object> strBody = Map.of("targetUserId", "25", "branchId", " 2 ");
        assertEquals(101L, controller.startConversationWithRecipient(strBody, session).getBody().get("conversationId"));

        Map<String, Object> numBody = Map.of("targetUserId", 25, "branchId", 2);
        assertEquals(101L, controller.startConversationWithRecipient(numBody, session).getBody().get("conversationId"));
    }

    @Test
    void searchConversationsAndGetMessages_DelegateWithClampedPageable() {
        Page<ChatConversationDTO> page = new PageImpl<>(List.of());
        when(chatService.searchInboxConversations(eq(10L), eq("a"), eq("BENH_NHAN"), eq(1L), eq(true), any(Pageable.class)))
                .thenReturn(page);
        when(chatService.getConversationMessages(10L, 50L)).thenReturn(Map.of("conversationId", 50L));

        assertSame(page, controller.searchConversations("a", "BENH_NHAN", 1L, true, -1, 250, session).getBody());
        assertEquals(50L, controller.getConversationMessages(50L, session).getBody().get("conversationId"));
    }

    @Test
    void sendEditAndRecallMessage_PersistAndBroadcastViaWebSocketController() {
        ChatSendRequestDTO sendReq = ChatSendRequestDTO.builder().messageText("Hello").build();
        ChatMessageDTO dto = ChatMessageDTO.builder().messageId(99L).messageText("Hello").build();

        when(chatService.sendMessage(10L, sendReq)).thenReturn(dto);
        when(chatService.editMessage(10L, 99L, "Edited")).thenReturn(dto);
        when(chatService.recallMessage(10L, 99L)).thenReturn(dto);

        assertSame(dto, controller.sendMessage(sendReq, session).getBody());
        assertSame(dto, controller.editMessage(99L, Map.of("messageText", "Edited"), session).getBody());
        assertSame(dto, controller.recallMessage(99L, session).getBody());

        verify(chatWebSocketController, times(3)).broadcastSavedMessage(dto);
    }

    @Test
    void deleteSelfAndMarkReadAndExceptionHandler_WorkCorrectly() {
        when(chatService.deleteMessageForUser(10L, 5L)).thenReturn(Map.of("messageId", 5L));
        when(chatService.deleteConversationForUser(10L, 6L)).thenReturn(Map.of("conversationId", 6L));
        when(chatService.markConversationAsRead(10L, 6L)).thenReturn(Map.of("updatedCount", 3));

        assertEquals(5L, controller.deleteMessageForSelf(5L, session).getBody().get("messageId"));
        assertEquals(6L, controller.deleteConversationForSelf(6L, session).getBody().get("conversationId"));
        assertEquals(3, controller.markConversationAsRead(6L, session).getBody().get("updatedCount"));

        ResponseEntity<Map<String, String>> err = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, err.getStatusCode());
        assertEquals("Yêu cầu không hợp lệ.", err.getBody().get("message"));
    }

    @Test
    void writeAndSearchEndpoints_ThrowUnauthorized_WhenSessionMissingOrNoUser() {
        MockHttpSession empty = new MockHttpSession();
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.searchRecipientUsers(null, null, null, null, null, empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.startConversationWithRecipient(Map.of(), empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.sendMessage(new ChatSendRequestDTO(), empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.editMessage(1L, Map.of("messageText", "x"), empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.recallMessage(1L, empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.deleteMessageForSelf(1L, empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.deleteConversationForSelf(1L, empty)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.markConversationAsRead(1L, empty)).getStatusCode());
    }

    @Test
    void editMessage_PassesNullText_WhenRequestBodyNull() {
        ChatMessageDTO dto = ChatMessageDTO.builder().messageId(5L).build();
        when(chatService.editMessage(10L, 5L, null)).thenReturn(dto);

        ResponseEntity<ChatMessageDTO> res = controller.editMessage(5L, null, session);
        assertSame(dto, res.getBody());
        verify(chatWebSocketController).broadcastSavedMessage(dto);
    }

    @Test
    void startConversationWithRecipient_HandlesNullOrBlankStringValuesSafely() {
        when(chatService.startOrGetConversationWithRecipient(10L, null, null))
                .thenReturn(Map.of("conversationId", 1L));

        assertEquals(1L, controller.startConversationWithRecipient(null, session).getBody().get("conversationId"));
        assertEquals(1L, controller.startConversationWithRecipient(
                Map.of("targetUserId", "  ", "branchId", ""), session).getBody().get("conversationId"));
    }

    @Test
    void searchConversations_ClampsNegativePageAndOversizedPage() {
        Page<ChatConversationDTO> expected = new PageImpl<>(List.of());
        when(chatService.searchInboxConversations(eq(10L), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(expected);

        controller.searchConversations(null, null, null, null, -5, 999, session);

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(chatService).searchInboxConversations(eq(10L), isNull(), isNull(), isNull(), isNull(), cap.capture());
        assertEquals(0, cap.getValue().getPageNumber());
        assertEquals(100, cap.getValue().getPageSize());
    }

    @Test
    void handleResponseStatus_PreservesExplicitReason() {
        ResponseEntity<Map<String, String>> res = controller.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hội thoại"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals("Không tìm thấy hội thoại", res.getBody().get("message"));
    }
}
