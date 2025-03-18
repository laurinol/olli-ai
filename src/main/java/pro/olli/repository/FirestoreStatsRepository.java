/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import com.google.cloud.firestore.Firestore;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class FirestoreStatsRepository implements StatsRepository {

    private final Firestore firestore;

    @Inject
    public FirestoreStatsRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Uni<Optional<Map<String, Object>>> getGlobalStats() {
        return Uni.createFrom().future(
            firestore.collection("stats").document("global").get()
        ).map(doc -> doc.exists() ? Optional.of(doc.getData()) : Optional.empty());
    }

    @Override
    public Uni<Long> getActiveCustomersCount(long sinceTimestamp) {
        return Uni.createFrom().future(
            firestore.collection("users")
                .whereGreaterThanOrEqualTo("lastActive", sinceTimestamp)
                .get()
        ).map(querySnapshot -> (long) querySnapshot.getDocuments().size());
    }

    @Override
    public Uni<Boolean> checkConnectivity() {
        return Uni.createFrom().future(
                firestore.collection("stats").document("test").get()
            ).map(doc -> true)
            .onFailure().recoverWithItem(false);
    }
}
