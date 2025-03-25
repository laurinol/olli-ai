/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import io.smallrye.mutiny.Uni;

import java.util.Map;
import pro.olli.models.SyncHistoryEntry;

public interface SyncRepository {

    // Append an entry to the syncHistory array for a client/user
    Uni<Void> appendSyncHistory(String businessId, String userId, SyncHistoryEntry historyEntry);
    
    /**
     * Store embedding vector in local database (for development use)
     * This is used as a replacement for Vector Search API in dev mode
     */
    default Uni<Void> storeEmbedding(String dataId, String businessId, String userId, String file, 
                                     java.util.List<Float> embedding) {
        // Default implementation does nothing - override in concrete implementations
        return Uni.createFrom().item(() -> null);
    }
    
    /**
     * Search for similar embeddings (for development use)
     * This is used as a replacement for Vector Search API in dev mode
     */
    default Uni<java.util.List<String>> findSimilarDocuments(String businessId, String userId, 
                                                             java.util.List<Float> queryEmbedding, 
                                                             int limit) {
        // Default implementation returns empty list - override in concrete implementations
        return Uni.createFrom().item(java.util.Collections.emptyList());
    }
}
