/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import pro.olli.ai.ollama.OllamaEmbeddingService;
import pro.olli.ai.ollama.OllamaQueryService;
import pro.olli.ai.vertexai.VertexAiEmbeddingService;
import pro.olli.ai.vertexai.VertexAiQueryService;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Qualifier
@Retention(RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
@interface DefaultService {}

@ApplicationScoped
@Startup
public class AiFactory {

    @ConfigProperty(name = "ai.provider", defaultValue = "vertex")
    String aiProvider;

    @Produces
    @ApplicationScoped
    @DefaultService
    public EmbeddingService produceEmbeddingService() {
        return switch (aiProvider.toLowerCase()) {
            case "vertex" -> new VertexAiEmbeddingService();
            case "ollama" -> new OllamaEmbeddingService();
            default -> throw new IllegalArgumentException("Unknown AI provider: " + aiProvider);
        };
    }

    @Produces
    @ApplicationScoped
    @DefaultService
    public QueryService produceQueryService() {
        return switch (aiProvider.toLowerCase()) {
            case "vertex" -> new VertexAiQueryService();
            case "ollama" -> new OllamaQueryService();
            default -> throw new IllegalArgumentException("Unknown AI provider: " + aiProvider);
        };
    }
}
