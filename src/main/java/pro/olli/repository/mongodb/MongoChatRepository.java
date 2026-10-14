/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.repository.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;
import pro.olli.ai.ChatMessage;
import pro.olli.models.Conversation;
import pro.olli.repository.ChatRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MongoDB implementation of the ChatRepository interface.
 */
public class MongoChatRepository implements ChatRepository {

    private static final Logger LOG = Logger.getLogger(MongoChatRepository.class);
    private static final String CHATS_COLLECTION = "chats";
    private static final String CONVERSATIONS_COLLECTION = "conversations";

    private final MongoClient mongoClient;

    @Inject
    public MongoChatRepository(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    private MongoCollection<Document> getChatsCollection() {
        return mongoClient.getDatabase("olli").getCollection(CHATS_COLLECTION);
    }

    private MongoCollection<Document> getConversationsCollection(String userId) {
        // Use a naming convention for user-specific chat collections
        return mongoClient.getDatabase("olli").getCollection(CONVERSATIONS_COLLECTION);
    }

    @Override
    public Uni<Void> storeConversation(String userId, String conversationId, List<ChatMessage> messages) {
        LOG.infof("Storing conversation %s for user %s", conversationId, userId);

        return Uni.createFrom().item(() -> {
            try {
                // Convert messages to BSON documents
                List<Document> messageDocs = messages.stream()
                    .map(msg -> new Document()
                        .append("role", msg.role)
                        .append("content", msg.content)
                        .append("timestamp", System.currentTimeMillis())
                    )
                    .collect(Collectors.toList());

                // Create conversation document
                Document conversationDoc = new Document()
                    .append("userId", userId)
                    .append("conversationId", conversationId)
                    .append("messages", messageDocs)
                    .append("timestamp", System.currentTimeMillis());

                // Use upsert to handle both new conversations and updates
                getConversationsCollection(userId).replaceOne(
                    Filters.and(
                        Filters.eq("userId", userId),
                        Filters.eq("conversationId", conversationId)
                    ),
                    conversationDoc,
                    new com.mongodb.client.model.ReplaceOptions().upsert(true)
                );

                LOG.infof("Successfully stored conversation %s for user %s", conversationId, userId);
                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to store conversation %s for user %s: %s",
                          conversationId, userId, e.getMessage());
                throw new RuntimeException("Failed to store conversation", e);
            }
        });
    }

    @Override
    public Uni<List<Conversation>> getConversations(String userId) {
        LOG.infof("Retrieving conversations for user %s", userId);

        return Uni.createFrom().item(() -> {
            try {
                List<Conversation> conversations = new ArrayList<>();

                // Query for all conversations for this user
                getConversationsCollection(userId)
                    .find(Filters.eq("userId", userId))
                    .sort(Sorts.descending("timestamp"))
                    .forEach(doc -> {
                        String conversationId = doc.getString("conversationId");

                        // Extract and convert messages
                        List<ChatMessage> messages = new ArrayList<>();
                        @SuppressWarnings("unchecked")
                        List<Document> messageDocs = (List<Document>) doc.get("messages");
                        if (messageDocs != null) {
                            for (Document msgDoc : messageDocs) {
                                String role = msgDoc.getString("role");
                                String content = msgDoc.getString("content");
                                messages.add(new ChatMessage(role, content));
                            }
                        }

                        conversations.add(new Conversation(conversationId, messages));
                    });

                LOG.infof("Retrieved %d conversations for user %s", conversations.size(), userId);
                return conversations;
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve conversations for user %s: %s", userId, e.getMessage());
                throw new RuntimeException("Failed to retrieve conversations", e);
            }
        });
    }
}
