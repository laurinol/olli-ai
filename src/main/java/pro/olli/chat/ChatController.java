/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.chat;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.QueryService;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pro.olli.models.ChatRequest;
import pro.olli.models.ChatResponse;
import pro.olli.models.Conversation;

@Path("/chat")
@Authenticated
public class ChatController {

    private static final Logger LOG = Logger.getLogger(ChatController.class);

    private final Firestore firestore;
    private final QueryService queryService;
    private final SecurityContext securityContext;

    @Inject
    public ChatController(Firestore firestore, QueryService queryService, SecurityContext securityContext) {
        this.firestore = firestore;
        this.queryService = queryService;
        this.securityContext = securityContext;
    }

    @POST
    @Path("/query")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ChatResponse> query(ChatRequest request) {
        String userId = getUserId();
        return Uni.createFrom().emitter(emitter -> {
            try {
                List<ChatMessage> history = request.history != null ? request.history : new ArrayList<>();
                queryService.query(request.prompt, history)
                    .subscribe().with(
                        response -> {
                            ChatMessage userMsg = new ChatMessage("user", request.prompt);
                            ChatMessage assistantMsg = new ChatMessage("assistant", response);
                            storeChat(userId, history, userMsg, assistantMsg);
                            emitter.complete(new ChatResponse(response));
                        },
                        emitter::fail
                    );
            } catch (Exception e) {
                LOG.errorf("Query failed for user %s: %s", userId, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @GET
    @Path("/history")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<List<Conversation>> getHistory() {
        String userId = getUserId();
        return Uni.createFrom().emitter(emitter -> {
            try {
                List<Conversation> conversations = firestore.collection("chats")
                    .document(userId)
                    .collection("conversations")
                    .get()
                    .get()
                    .getDocuments()
                    .stream()
                    .map(doc -> {
                        List<Map<String, Object>> messages = (List<Map<String, Object>>) doc.get("messages");
                        List<ChatMessage> chatMessages = messages.stream()
                            .map(m -> new ChatMessage((String) m.get("role"), (String) m.get("content")))
                            .collect(Collectors.toList());
                        return new Conversation(doc.getId(), chatMessages);
                    })
                    .collect(Collectors.toList());
                emitter.complete(conversations);
            } catch (Exception e) {
                LOG.errorf("History fetch failed for user %s: %s", userId, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    private void storeChat(String userId, List<ChatMessage> history, ChatMessage userMsg, ChatMessage assistantMsg) {
        String conversationId = String.valueOf(System.currentTimeMillis());
        DocumentReference docRef = firestore.collection("chats")
            .document(userId)
            .collection("conversations")
            .document(conversationId);
        List<Map<String, Object>> messages = new ArrayList<>();
        history.forEach(msg -> messages.add(Map.of("role", msg.role, "content", msg.content, "timestamp", FieldValue.serverTimestamp())));
        messages.add(Map.of("role", userMsg.role, "content", userMsg.content, "timestamp", FieldValue.serverTimestamp()));
        messages.add(Map.of("role", assistantMsg.role, "content", assistantMsg.content, "timestamp", FieldValue.serverTimestamp()));
        docRef.set(Map.of("messages", messages));
    }

    private String getUserId() {
        Principal principal = securityContext.getUserPrincipal();
        return principal != null ? principal.getName() : "anonymous";
    }
}

