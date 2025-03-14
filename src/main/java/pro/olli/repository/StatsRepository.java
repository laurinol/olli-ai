/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import io.smallrye.mutiny.Uni;

import java.util.Map;
import java.util.Optional;

public interface StatsRepository {

    // Get global stats document
    Uni<Optional<Map<String, Object>>> getGlobalStats();

    // Get count of active customers (users active in last 30 days)
    Uni<Long> getActiveCustomersCount(long sinceTimestamp);

    // Check Firestore connectivity (for server status)
    Uni<Boolean> checkConnectivity();
}
