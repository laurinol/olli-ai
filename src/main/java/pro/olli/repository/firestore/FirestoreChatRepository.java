/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository.firestore;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.jboss.logging.Logger;
import pro.olli.ai.ChatMessage;
import pro.olli.models.Conversation;
import pro.olli.repository.ChatRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("firestore-chat")
public class FirestoreChatRepository implements ChatRepository {

    private static final Logger LOG = Logger.getLogger(FirestoreChatRepository.class);
    private final Firestore firestore;

    public FirestoreChatRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Uni<Void> storeConversation(String userId, String conversationId, List<ChatMessage> messages) {
        LOG.infof("Firestore: Storing conversation %s for user %s", conversationId, userId);
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
        ).map(ignored -> {
            LOG.infof("Successfully stored conversation %s for user %s", conversationId, userId);
            return (Void) null;
        });
    }

    @Override
    public Uni<List<Conversation>> getConversations(String userId) {
        LOG.infof("Firestore: Getting conversations for user %s", userId);
        return Uni.createFrom().future(
            firestore.collection("chats")
                .document(userId)
                .collection("conversations")
                .get()
        ).map(querySnapshot -> {
            List<Conversation> conversations = querySnapshot.getDocuments().stream()
                .map(doc -> {
                    List<Map<String, Object>> messages = (List<Map<String, Object>>) doc.get("messages");
                    List<ChatMessage> chatMessages = messages != null
                        ? messages.stream()
                        .map(m -> new ChatMessage((String) m.get("role"), (String) m.get("content")))
                        .collect(Collectors.toList())
                        : new ArrayList<>();
                    return new Conversation(doc.getId(), chatMessages);
                })
                .collect(Collectors.toList());

            LOG.infof("Retrieved %d conversations for user %s", conversations.size(), userId);
            return conversations;
        });
    }
}