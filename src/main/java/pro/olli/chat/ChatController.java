/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.chat;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.QueryService;
import pro.olli.models.ChatRequest;
import pro.olli.models.ChatResponse;
import pro.olli.models.Conversation;
import pro.olli.repository.ChatRepository;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

@Path("/chat")
@Authenticated
public class ChatController {

    private static final Logger LOG = Logger.getLogger(ChatController.class);

    private final ChatRepository chatRepository;
    private final QueryService queryService;
    private final SecurityContext securityContext;

    public ChatController(ChatRepository chatRepository, QueryService queryService, SecurityContext securityContext) {
        this.chatRepository = chatRepository;
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
                            storeChat(userId, history, userMsg, assistantMsg)
                                .subscribe().with(
                                    ignored -> emitter.complete(new ChatResponse(response)),
                                    e -> {
                                        LOG.errorf("Failed to store chat for user %s: %s", userId, e.getMessage());
                                        emitter.fail(e);
                                    }
                                );
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
        return chatRepository.getConversations(userId)
            .onFailure().invoke(e -> LOG.errorf("History fetch failed for user %s: %s", userId, e.getMessage()));
    }

    private Uni<Void> storeChat(String userId, List<ChatMessage> history, ChatMessage userMsg, ChatMessage assistantMsg) {
        String conversationId = String.valueOf(System.currentTimeMillis());
        List<ChatMessage> messages = new ArrayList<>(history);
        messages.add(userMsg);
        messages.add(assistantMsg);
        return chatRepository.storeConversation(userId, conversationId, messages);
    }

    private String getUserId() {
        Principal principal = securityContext.getUserPrincipal();
        return principal != null ? principal.getName() : "anonymous";
    }
}
