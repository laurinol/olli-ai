/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

public class OllamaEmbeddingService implements EmbeddingService {

    private static final Logger LOG = Logger.getLogger(OllamaEmbeddingService.class);

    @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434")
    String ollamaEndpoint;

    private final OllamaApi ollamaApi;

    public OllamaEmbeddingService() {
        this.ollamaApi = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(ollamaEndpoint))
            .build(OllamaApi.class);
    }

    @Override
    public Uni<List<Float>> generateEmbedding(String text) {
        return ollamaApi.embeddings(new EmbeddingRequest("all-minilm", text))
            .map(response -> {
                LOG.infof("Generated embedding with Ollama, length: %d", response.embedding.size());
                return response.embedding;
            })
            .onFailure().invoke(e -> LOG.errorf("Failed to generate embedding with Ollama: %s", e.getMessage()));
    }

    @Path("/api")
    public interface OllamaApi {
        @POST
        @Path("/embeddings")
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        Uni<EmbeddingResponse> embeddings(EmbeddingRequest request); // No RestBody needed
    }

    public static class EmbeddingRequest {
        public String model;
        public String prompt;

        public EmbeddingRequest(String model, String prompt) {
            this.model = model;
            this.prompt = prompt;
        }
    }

    public static class EmbeddingResponse {
        public List<Float> embedding;
    }
}
