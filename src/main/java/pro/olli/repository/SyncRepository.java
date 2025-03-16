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
}
