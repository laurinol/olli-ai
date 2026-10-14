/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.ai;

import io.smallrye.mutiny.Uni;

import java.util.List;

public interface EmbeddingService {
    Uni<List<Float>> generateEmbedding(String text);
}
