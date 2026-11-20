package com.cvdcms.controller;

import com.cvdcms.DTO.ChatMessageDTO;
import com.cvdcms.DTO.ChatSendRequestDTO;
import com.cvdcms.config.ChatStompPrincipal;
import com.cvdcms.config.ChatWebSocketHandshakeInterceptor;
import com.cvdcms.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private ChatWebSocketController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatWebSocketController(chatService, messagingTemplate);
    }

    @Test
    void handleSendMessage_ThrowsAccessDenied_WhenUnauthenticated() {
        ChatSendRequestDTO request = new ChatSendRequestDTO();
        SimpMessageHeaderAccessor emptyAccessor = SimpMessageHeaderAccessor.create();

        assertThrows(AccessDeniedException.class,
                () -> controller.handleSendMessage(request, null, emptyAccessor));
        verifyNoInteractions(chatService, messagingTemplate);
    }

    @Test
    void handleSendMessage_UsesChatStompPrincipalAndSavesBeforeBroadcast() {
        ChatSendRequestDTO req = ChatSendRequestDTO.builder().messageText("Xin chào").build();
        ChatMessageDTO saved = ChatMessageDTO.builder()
                .messageId(1L)
                .branchId(1L)
                .directConversation(false)
                .initiatorUsername("bn01")
                .senderUsername("bn01")
                .messageText("Xin chào")
                .build();
        when(chatService.sendMessage(10L, req)).thenReturn(saved);

        controller.handleSendMessage(req, new ChatStompPrincipal(10L, "bn01"), null);

        InOrder inOrder = inOrder(chatService, messagingTemplate);
        inOrder.verify(chatService).sendMessage(10L, req);
        inOrder.verify(messagingTemplate).convertAndSendToUser("bn01", "/queue/chat/messages", saved);
        inOrder.verify(messagingTemplate).convertAndSend("/topic/chat/branch/1", saved);
        inOrder.verify(messagingTemplate).convertAndSend("/topic/chat/admin", saved);
    }

    @Test
    void handleSendMessage_ExtractsUserIdFromHeaderAccessorOrSessionAttributes() {
        ChatSendRequestDTO req = ChatSendRequestDTO.builder().messageText("Hi").build();
        ChatMessageDTO saved = ChatMessageDTO.builder().messageId(2L).directConversation(true).senderUsername("lt01").build();
        when(chatService.sendMessage(22L, req)).thenReturn(saved);

        SimpMessageHeaderAccessor accessorWithUser = SimpMessageHeaderAccessor.create();
        accessorWithUser.setUser(new ChatStompPrincipal(22L, "lt01"));
        controller.handleSendMessage(req, null, accessorWithUser);
        verify(chatService).sendMessage(22L, req);

        SimpMessageHeaderAccessor accessorWithSession = SimpMessageHeaderAccessor.create();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(ChatWebSocketHandshakeInterceptor.ATTR_USER_ID, 33);
        accessorWithSession.setSessionAttributes(attrs);
        when(chatService.sendMessage(33L, req)).thenReturn(saved);
        controller.handleSendMessage(req, null, accessorWithSession);
        verify(chatService).sendMessage(33L, req);
    }

    @Test
    void broadcastSavedMessage_IgnoresNullAndDeduplicatesTargetUsers() {
        controller.broadcastSavedMessage(null);
        verifyNoInteractions(messagingTemplate);

        ChatMessageDTO groupMsg = ChatMessageDTO.builder()
                .messageId(10L)
                .branchId(2L)
                .directConversation(false)
                .initiatorUsername(" bn01 ")
                .senderUsername("letan02")
                .recipientUsername("bn01")
                .build();

        controller.broadcastSavedMessage(groupMsg);

        verify(messagingTemplate, times(1)).convertAndSendToUser("bn01", "/queue/chat/messages", groupMsg);
        verify(messagingTemplate, times(1)).convertAndSendToUser("letan02", "/queue/chat/messages", groupMsg);
        verify(messagingTemplate).convertAndSend("/topic/chat/branch/2", groupMsg);
        verify(messagingTemplate).convertAndSend("/topic/chat/admin", groupMsg);
    }

    @Test
    void broadcastSavedMessage_SkipsBranchTopicForDirectConversation() {
        ChatMessageDTO directMsg = ChatMessageDTO.builder()
                .messageId(20L)
                .branchId(1L)
                .directConversation(true)
                .initiatorUsername("lt01")
                .recipientUsername("bs01")
                .senderUsername("lt01")
                .build();

        controller.broadcastSavedMessage(directMsg);

        verify(messagingTemplate).convertAndSendToUser("lt01", "/queue/chat/messages", directMsg);
        verify(messagingTemplate).convertAndSendToUser("bs01", "/queue/chat/messages", directMsg);
        verify(messagingTemplate, never()).convertAndSend(startsWith("/topic/chat/branch/"), eq(directMsg));
        verify(messagingTemplate).convertAndSend("/topic/chat/admin", directMsg);
    }

    @Test
    void handleSendMessage_ThrowsAccessDenied_WhenPrincipalNotChatStompAndSessionHasNoUserId() {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionAttributes(new HashMap<>());

        assertThrows(AccessDeniedException.class,
                () -> controller.handleSendMessage(new ChatSendRequestDTO(), () -> "otherPrincipal", accessor));
        verifyNoInteractions(chatService, messagingTemplate);
    }

    @Test
    void broadcastSavedMessage_SkipsBranchTopicWhenBranchIdIsNull() {
        ChatMessageDTO noBranchMsg = ChatMessageDTO.builder()
                .messageId(30L)
                .branchId(null)
                .directConversation(false)
                .senderUsername("letan01")
                .build();

        controller.broadcastSavedMessage(noBranchMsg);

        verify(messagingTemplate).convertAndSendToUser("letan01", "/queue/chat/messages", noBranchMsg);
        verify(messagingTemplate, never()).convertAndSend(startsWith("/topic/chat/branch/"), eq(noBranchMsg));
        verify(messagingTemplate).convertAndSend("/topic/chat/admin", noBranchMsg);
    }
}
