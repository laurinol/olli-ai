/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

public class OllamaQueryService extends OllamaEmbeddingService implements QueryService {

    private static final Logger LOG = Logger.getLogger(OllamaQueryService.class);

    private final OllamaApi ollamaApi;

    public OllamaQueryService() {
        this.ollamaApi = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(ollamaEndpoint))
            .build(OllamaApi.class);
    }

    @Override
    public Uni<String> query(String prompt, List<ChatMessage> history) {
        return ollamaApi.chat(new ChatRequest("llama3", prompt, history))
            .map(response -> {
                LOG.infof("Generated chat response with Ollama: %s", response.message.content);
                return response.message.content;
            })
            .onFailure().invoke(e -> LOG.errorf("Chat query failed: %s", e.getMessage()));
    }

    @Path("/api")
    public interface OllamaApi {
        @POST
        @Path("/chat")
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        Uni<ChatResponse> chat(ChatRequest request);
    }

    static class ChatRequest {
        public String model;
        public String prompt;
        public List<ChatMessage> messages;

        public ChatRequest(String model, String prompt, List<ChatMessage> history) {
            this.model = model;
            this.prompt = prompt;
            this.messages = history;
        }
    }

    static class ChatResponse {
        public ChatMessage message;
    }
}
