/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Startup
public class AiFactory {

    @ConfigProperty(name = "ai.provider", defaultValue = "vertex")
    String aiProvider;

    @Produces
    @ApplicationScoped
    public QueryService createQueryService() {
        switch (aiProvider.toLowerCase()) {
            case "vertex":
                return new VertexAiQueryService();
            case "ollama":
                return new OllamaQueryService();
            default:
                throw new IllegalArgumentException("Unknown AI provider: " + aiProvider);
        }
    }
}
