/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.repository.firestore;

import com.google.cloud.firestore.Firestore;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import pro.olli.repository.StatsRepository;

import java.util.Map;
import java.util.Optional;

public class FirestoreStatsRepository implements StatsRepository {

    private static final Logger LOG = Logger.getLogger(FirestoreStatsRepository.class);
    private final Firestore firestore;

    @Inject
    public FirestoreStatsRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Uni<Optional<Map<String, Object>>> getGlobalStats() {
        LOG.info("Firestore: Getting global stats");
        return Uni.createFrom().future(
            firestore.collection("stats").document("global").get()
        ).map(doc -> doc.exists() ? Optional.of(doc.getData()) : Optional.empty());
    }

    @Override
    public Uni<Long> getActiveCustomersCount(long sinceTimestamp) {
        LOG.infof("Firestore: Getting active customers count since %d", sinceTimestamp);
        return Uni.createFrom().future(
            firestore.collection("users")
                .whereGreaterThanOrEqualTo("lastActive", sinceTimestamp)
                .get()
        ).map(querySnapshot -> (long) querySnapshot.getDocuments().size());
    }

    @Override
    public Uni<Boolean> checkConnectivity() {
        LOG.info("Firestore: Checking connectivity");
        return Uni.createFrom().future(
                firestore.collection("stats").document("test").get()
            ).map(doc -> true)
            .onFailure().recoverWithItem(false);
    }
}
