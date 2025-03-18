/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.worker;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.interceptor.Interceptor;
import org.jboss.logging.Logger;

import pro.olli.ai.EmbeddingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mock implementation of EmbeddingService for testing
 */
@ApplicationScoped
@Alternative
@Priority(Interceptor.Priority.APPLICATION)
public class MockEmbeddingService implements EmbeddingService {
    
    private static final Logger LOG = Logger.getLogger(MockEmbeddingService.class);
    private static final Random RANDOM = new Random();
    
    @Override
    public Uni<List<Float>> generateEmbedding(String text) {
        LOG.info("Generating mock embedding for text length: " + text.length());
        
        // Create a deterministic but unique embedding based on the text content
        List<Float> mockEmbedding = new ArrayList<>();
        int dimensions = 384; // Typical embedding dimension
        
        // Use the hash of the text to seed the random generator for deterministic output
        Random seededRandom = new Random(text.hashCode());
        
        for (int i = 0; i < dimensions; i++) {
            mockEmbedding.add(seededRandom.nextFloat() * 2 - 1); // Values between -1 and 1
        }
        
        return Uni.createFrom().item(mockEmbedding);
    }
}