package com.cvdcms.repository;

import com.cvdcms.entity.ChatMessageUserState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatMessageUserStateRepository extends JpaRepository<ChatMessageUserState, Long> {

    Optional<ChatMessageUserState> findByMessageIdAndUserId(Long messageId, Long userId);

    List<ChatMessageUserState> findByConversationIdAndUserId(Long conversationId, Long userId);

    List<ChatMessageUserState> findByUserIdAndConversationIdIn(Long userId, Collection<Long> conversationIds);
}
