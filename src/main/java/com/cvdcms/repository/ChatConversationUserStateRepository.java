package com.cvdcms.repository;

import com.cvdcms.entity.ChatConversationUserState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatConversationUserStateRepository extends JpaRepository<ChatConversationUserState, Long> {

    Optional<ChatConversationUserState> findByConversationIdAndUserId(Long conversationId, Long userId);

    List<ChatConversationUserState> findByUserIdAndConversationIdIn(Long userId, Collection<Long> conversationIds);
}
