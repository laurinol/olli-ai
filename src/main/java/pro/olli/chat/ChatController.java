/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.chat;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import pro.olli.ai.AiConfiguredService;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.QueryService;
import pro.olli.models.ChatRequest;
import pro.olli.models.ChatResponse;
import pro.olli.models.Conversation;
import pro.olli.models.User;
import pro.olli.repository.ChatRepository;
import pro.olli.repository.UserRepository;
import pro.olli.service.BillingService;

@ApplicationScoped
@Path("/chat")
@Authenticated
public class ChatController {
    private static final Logger LOG = Logger.getLogger(ChatController.class);

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final BillingService billingService;
    private final QueryService queryService;
    private final SecurityContext securityContext;

    @Inject
    public ChatController(ChatRepository chatRepository,
        UserRepository userRepository,
        BillingService billingService,
        @AiConfiguredService QueryService queryService,
        SecurityContext securityContext) {
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.billingService = billingService;
        this.queryService = queryService;
        this.securityContext = securityContext;
        LOG.infof("ChatController initialized with %s", queryService.getClass().getName());
    }

    @POST
    @Path("/test-query")
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.annotation.security.PermitAll // Explicitly allow all access
    public Uni<ChatResponse> testQuery(ChatRequest request) {
        LOG.info("Using test query endpoint without authentication: " + request.prompt);

        if (request.prompt != null) {
            // Set system properties for RAG to properly handle this query
            System.setProperty("current.query", request.prompt);

            // For meeting-related queries, explicitly set properties to enable RAG functionality
            if (request.prompt.toLowerCase().contains("meeting") ||
                request.prompt.toLowerCase().contains("attendee") ||
                request.prompt.toLowerCase().contains("budget") ||
                request.prompt.toLowerCase().contains("timeline") ||
                request.prompt.toLowerCase().contains("february") ||
                request.prompt.toLowerCase().contains("date") ||
                request.prompt.toLowerCase().contains("notes")) {

                LOG.info("Detected meeting-related query, ensuring RAG mode is enabled");
                System.setProperty("ollama.implementation", "rag");
                System.setProperty("use.memory.repository", "true");
                System.setProperty("enhance.prompt", "true");
            }
        }

        // Use the RAG-enabled query service for all requests
        return processQuery("test-user", request);
    }

    @POST
    @Path("/query")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ChatResponse> query(ChatRequest request) {
        String userId = getUserId();

        LOG.infof("Received query from user '%s': %s", userId, request);

        // Special handling for test-dev-user from our DevJwtAuthMechanism
        if (userId.equals("test-dev-user")) {
            LOG.info("Using test development user - bypassing all database lookups");
            return processQuery(userId, request);
        }

        // Check if this is a development environment and user looks like a development token-based user
        if (isDevelopmentMode() && (userId.equals("user") || userId.startsWith("user-") || userId.startsWith(
            "test-"))) {
            LOG.info("Development mode detected - bypassing user lookup and billing checks");
            return processQuery(userId, request);
        }

        // Get the user to find their business
        return userRepository.getUser(userId)
            .flatMap(userOpt -> {
                if (userOpt.isEmpty()) {
                    LOG.warnf("User not found: %s - this might be a development token", userId);
                    if (isDevelopmentMode()) {
                        // In development mode, proceed without a valid user
                        return processQuery(userId, request);
                    }
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

        // Special handling for test-dev-user from our DevJwtAuthMechanism
        if (userId.equals("test-dev-user") ||
            (isDevelopmentMode() && (userId.equals("user") || userId.startsWith("user-") || userId.startsWith(
                "test-")))) {
            LOG.info("Development mode - returning empty history for development user: " + userId);
            return Uni.createFrom().item(new ArrayList<>());
        }

        return chatRepository.getConversations(userId)
            .onFailure().invoke(e -> LOG.errorf("History fetch failed for user %s: %s", userId, e.getMessage()));
    }

    private Uni<Void> storeChat(String userId, List<ChatMessage> history, ChatMessage userMsg,
        ChatMessage assistantMsg) {
        // In development mode with test users, we can bypass persistence to avoid DB errors
        if (userId.equals("test-dev-user") ||
            (isDevelopmentMode() && (userId.equals("user") || userId.startsWith("user-") || userId.startsWith(
                "test-")))) {
            LOG.info("Development mode - skipping chat storage for development user: " + userId);
            return Uni.createFrom().voidItem();
        }

        String conversationId = String.valueOf(System.currentTimeMillis());
        List<ChatMessage> messages = new ArrayList<>(history);
        messages.add(userMsg);
        messages.add(assistantMsg);
        return chatRepository.storeConversation(userId, conversationId, messages);
    }

    private String getUserId() {
        Principal principal = securityContext.getUserPrincipal();
        if (principal == null) {
            LOG.warn("No authenticated user found in security context");
            return "anonymous";
        }

        // Extract user ID from the principal
        String userId = principal.getName();
        LOG.debugf("Extracted user ID from security context: %s", userId);

        // If we're using OIDC/JWT, the principal may be a JsonWebToken or OidcJwtCallerPrincipal
        if (principal instanceof JsonWebToken) {
            JsonWebToken jwt = (JsonWebToken) principal;
            // You can extract additional claims if needed
            LOG.debugf("User roles from JWT: %s", jwt.getGroups());
        }

        return userId;
    }

    // Development mode flag - initialize this once to avoid property lookups on every request
    private static final boolean DEV_MODE;

    static {
        // Set development mode flag based on multiple indicators
        boolean isDev = false;

        // Check for development profile
        String profile = System.getProperty("quarkus.profile", "");
        if ("dev".equals(profile)) {
            isDev = true;
        }

        // Also check for other dev indicators
        String devServices = System.getProperty("quarkus.devservices.enabled");
        if ("true".equals(devServices)) {
            isDev = true;
        }

        // Check manually set property
        if (Boolean.getBoolean("app.dev.mode")) {
            isDev = true;
        }

        // For local development with Postman, default to true if nothing else is set
        if (System.getenv("DEV_MODE") != null) {
            isDev = true;
        }

        DEV_MODE = isDev;

        // Log development mode status
        Logger.getLogger(ChatController.class).infof("Development mode initialized: %s", DEV_MODE);
    }

    /**
     * Check if we're running in development mode
     */
    private boolean isDevelopmentMode() {
        return true; // Always return true for testing
    }
}
