package com.cvdcms.config;

import com.cvdcms.service.ChatService;
import com.cvdcms.service.CustomerCareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatStompChannelInterceptorTest {

    @Mock
    private ChatService chatService;

    @Mock
    private CustomerCareService customerCareService;

    @Mock
    private MessageChannel channel;

    private ChatStompChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ChatStompChannelInterceptor(chatService);
        interceptor.setCustomerCareService(customerCareService);
    }

    private Message<byte[]> stompFrame(StompCommand command, String destination, ChatStompPrincipal principal, Map<String, Object> sessionAttrs) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (principal != null) {
            accessor.setUser(principal);
        }
        if (sessionAttrs != null) {
            accessor.setSessionAttributes(sessionAttrs);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void preSend_PassesThroughNonStompMessage() {
        Message<byte[]> raw = new GenericMessage<>(new byte[0]);
        assertSame(raw, interceptor.preSend(raw, channel));
    }

    @Test
    void preSend_ConnectThrowsAccessDenied_WhenUnauthenticated() {
        Message<byte[]> msg = stompFrame(StompCommand.CONNECT, null, null, new HashMap<>());
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(msg, channel));
    }

    @Test
    void preSend_ConnectAttachesPrincipalFromSessionAttributes() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(ChatWebSocketHandshakeInterceptor.ATTR_USER_ID, 15);
        attrs.put(ChatWebSocketHandshakeInterceptor.ATTR_USERNAME, "bacsi15");
        Message<byte[]> msg = stompFrame(StompCommand.CONNECT, null, null, attrs);

        Message<?> result = interceptor.preSend(msg, channel);
        StompHeaderAccessor resAccessor = StompHeaderAccessor.wrap(result);
        assertInstanceOf(ChatStompPrincipal.class, resAccessor.getUser());
        assertEquals(15L, ((ChatStompPrincipal) resAccessor.getUser()).getUserId());
        assertEquals("bacsi15", resAccessor.getUser().getName());
    }

    @Test
    void preSend_SubscribeAllowsPersonalQueues() {
        ChatStompPrincipal p = new ChatStompPrincipal(5L, "patient5");
        assertNotNull(interceptor.preSend(stompFrame(StompCommand.SUBSCRIBE, "/user/queue/chat/messages", p, null), channel));
        assertNotNull(interceptor.preSend(stompFrame(StompCommand.SUBSCRIBE, "/queue/chat/messages", p, null), channel));
        assertNotNull(interceptor.preSend(stompFrame(StompCommand.SUBSCRIBE, "/user/queue/customer-care/notifications", p, null), channel));
        assertNotNull(interceptor.preSend(stompFrame(StompCommand.SUBSCRIBE, "/queue/customer-care/notifications", p, null), channel));
    }

    @Test
    void preSend_SubscribeRejectsUnauthenticatedOrBlankDestination() {
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(stompFrame(StompCommand.SUBSCRIBE, "/user/queue/chat/messages", null, new HashMap<>()), channel));
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(stompFrame(StompCommand.SUBSCRIBE, "   ", new ChatStompPrincipal(5L, "u5"), null), channel));
    }

    @Test
    void validateSubscription_AdminTopicsRestrictedToAdmin() {
        when(chatService.isAdminUser(1L)).thenReturn(true);
        when(chatService.isAdminUser(2L)).thenReturn(false);

        assertDoesNotThrow(() -> interceptor.validateSubscription(1L, "/topic/chat/admin"));
        assertDoesNotThrow(() -> interceptor.validateSubscription(1L, "/topic/customer-care/admin"));
        assertThrows(AccessDeniedException.class, () -> interceptor.validateSubscription(2L, "/topic/chat/admin"));
        assertThrows(AccessDeniedException.class, () -> interceptor.validateSubscription(2L, "/topic/customer-care/admin"));
    }

    @Test
    void validateSubscription_ChatBranchTopicValidatesBranchIdAndAccess() {
        when(chatService.canAccessBranchTopic(10L, 1L)).thenReturn(true);
        when(chatService.canAccessBranchTopic(10L, 2L)).thenReturn(false);

        assertDoesNotThrow(() -> interceptor.validateSubscription(10L, "/topic/chat/branch/1"));
        assertThrows(AccessDeniedException.class, () -> interceptor.validateSubscription(10L, "/topic/chat/branch/2"));
        assertThrows(AccessDeniedException.class, () -> interceptor.validateSubscription(10L, "/topic/chat/branch/abc"));
    }

    @Test
    void validateSubscription_CustomerCareBranchTopicChecksCustomerCareAndChatAccess() {
        when(customerCareService.getAccessibleBranches(10L)).thenReturn(Map.of());
        when(chatService.canAccessBranchTopic(10L, 1L)).thenReturn(true);
        assertDoesNotThrow(() -> interceptor.validateSubscription(10L, "/topic/customer-care/branch/1"));

        when(chatService.canAccessBranchTopic(10L, 2L)).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> interceptor.validateSubscription(10L, "/topic/customer-care/branch/2"));
        assertThrows(AccessDeniedException.class, () -> interceptor.validateSubscription(10L, "/topic/customer-care/branch/xyz"));

        when(customerCareService.getAccessibleBranches(20L)).thenThrow(new RuntimeException("No CSKH access"));
        assertThrows(AccessDeniedException.class, () -> interceptor.validateSubscription(20L, "/topic/customer-care/branch/1"));
    }

    @Test
    void validateSubscription_WorksWhenCustomerCareServiceNull_AndRejectsUnknownDestination() {
        interceptor.setCustomerCareService(null);
        when(chatService.canAccessBranchTopic(10L, 3L)).thenReturn(true);
        assertDoesNotThrow(() -> interceptor.validateSubscription(10L, "/topic/customer-care/branch/3"));

        assertThrows(AccessDeniedException.class, () -> interceptor.validateSubscription(10L, "/topic/unknown/room"));
    }

    @Test
    void preSend_SendAllowsAppChatSendAndRejectsOtherDestinationsOrUnauth() {
        ChatStompPrincipal p = new ChatStompPrincipal(7L, "u7");
        assertNotNull(interceptor.preSend(stompFrame(StompCommand.SEND, "/app/chat.send", p, null), channel));
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(stompFrame(StompCommand.SEND, "/app/chat.other", p, null), channel));
        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(stompFrame(StompCommand.SEND, "/app/chat.send", null, new HashMap<>()), channel));
    }

    @Test
    void preSend_DisconnectCommandPassesThroughWithoutBlocking() {
        Message<byte[]> disconnect = stompFrame(StompCommand.DISCONNECT, null, new ChatStompPrincipal(7L, "u7"), null);
        assertSame(disconnect, interceptor.preSend(disconnect, channel));
    }

    @Test
    void preSend_SubscribeTrimsDestinationWhitespace() {
        when(chatService.isAdminUser(1L)).thenReturn(true);
        Message<byte[]> msg = stompFrame(StompCommand.SUBSCRIBE, "  /topic/chat/admin  ",
                new ChatStompPrincipal(1L, "admin"), null);
        assertSame(msg, interceptor.preSend(msg, channel));
    }
}
