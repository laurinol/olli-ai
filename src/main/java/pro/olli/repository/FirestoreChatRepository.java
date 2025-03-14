/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import pro.olli.ai.ChatMessage;
import pro.olli.models.Conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class FirestoreChatRepository implements ChatRepository {

    private final Firestore firestore;

    public FirestoreChatRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Uni<Void> storeConversation(String userId, String conversationId, List<ChatMessage> messages) {
        List<Map<String, Object>> messageData = messages.stream()
            .map(msg -> Map.of(
                "role", msg.role,
                "content", msg.content,
                "timestamp", FieldValue.serverTimestamp()
            ))
            .toList();

        return Uni.createFrom().future(
            firestore.collection("chats")
                .document(userId)
                .collection("conversations")
                .document(conversationId)
                .set(Map.of("messages", messageData))
        ).map(ignored -> (Void) null);
    }

    @Override
    public Uni<List<Conversation>> getConversations(String userId) {
        return Uni.createFrom().future(
            firestore.collection("chats")
                .document(userId)
                .collection("conversations")
                .get()
        ).map(querySnapshot -> querySnapshot.getDocuments().stream()
            .map(doc -> {
                List<Map<String, Object>> messages = (List<Map<String, Object>>) doc.get("messages");
                List<ChatMessage> chatMessages = messages != null
                    ? messages.stream()
                    .map(m -> new ChatMessage((String) m.get("role"), (String) m.get("content")))
                    .collect(Collectors.toList())
                    : new ArrayList<>();
                return new Conversation(doc.getId(), chatMessages);
            })
            .collect(Collectors.toList())
        );
    }
}
