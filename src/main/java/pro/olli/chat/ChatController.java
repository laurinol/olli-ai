/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.chat;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.QueryService;
import pro.olli.models.ChatRequest;
import pro.olli.models.ChatResponse;
import pro.olli.models.Conversation;
import pro.olli.models.User;
import pro.olli.repository.ChatRepository;
import pro.olli.repository.Factory;
import pro.olli.repository.UserRepository;
import pro.olli.service.BillingService;

@Path("/chat")
//@Authenticated
public class ChatController {

    private static final Logger LOG = Logger.getLogger(ChatController.class);

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final BillingService billingService;
    private final QueryService queryService;
    private final SecurityContext securityContext;

    public ChatController(@Factory ChatRepository chatRepository,
                         @Factory UserRepository userRepository,
                         BillingService billingService,
                         QueryService queryService,
                         SecurityContext securityContext) {
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.billingService = billingService;
        this.queryService = queryService;
        this.securityContext = securityContext;
    }

    @POST
    @Path("/query")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ChatResponse> query(ChatRequest request) {
        String userId = getUserId();

        // Get the user to find their business
        return userRepository.getUser(userId)
            .flatMap(userOpt -> {
                if (userOpt.isEmpty()) {
                    return Uni.createFrom().failure(
                        new IllegalArgumentException("User not found: " + userId));
                }

                User user = userOpt.get();
                String businessId = (String) user.getMetadata().get("businessId");

                if (businessId == null) {
                    LOG.warnf("User %s has no business ID, skipping billing checks", userId);
                    return processQuery(userId, request); // Proceed without billing checks
                }

                // Check if business can make more queries
                return billingService.checkBusinessLimits(businessId)
                    .flatMap(business -> {
                        if (business.isQueryLimitExceeded()) {
                            LOG.warnf("Business %s has exceeded query limit", businessId);
                            return Uni.createFrom().failure(
                                new IllegalStateException("Query limit exceeded for business"));
                        }

                        // Record query usage
                        return billingService.recordQueryUsage(businessId)
                            .flatMap(v -> processQuery(userId, request));
                    })
                    .onFailure().recoverWithItem(e -> {
                        if (e instanceof IllegalStateException &&
                            e.getMessage().contains("limit exceeded")) {
                            return new ChatResponse(
                                "Sorry, your organization has reached its monthly query limit. " +
                                "Please contact your administrator to upgrade your subscription.");
                        }
                        // For other errors, proceed with the query
                        LOG.warnf("Error checking billing limits: %s - proceeding with query", e.getMessage());
                        return null;
                    })
                    .flatMap(response -> {
                        if (response != null) {
                            return Uni.createFrom().item(response);
                        }
                        return processQuery(userId, request);
                    });
            });
    }

    /**
     * Process the query after billing checks
     */
    private Uni<ChatResponse> processQuery(String userId, ChatRequest request) {
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
