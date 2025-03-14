/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import io.smallrye.mutiny.Uni;
import pro.olli.ai.ChatMessage;

import java.util.List;
import pro.olli.models.Conversation;

public interface ChatRepository {

    // Store a conversation with messages
    Uni<Void> storeConversation(String userId, String conversationId, List<ChatMessage> messages);

    // Get all conversations for a user
    Uni<List<Conversation>> getConversations(String userId);
}
