/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
public class FirestoreSyncRepository implements SyncRepository {

    private final Firestore firestore;

    public FirestoreSyncRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Uni<Void> appendSyncHistory(String clientId, String userId, Map<String, Object> historyEntry) {
        return Uni.createFrom().future(
                firestore.collection("clients").document(clientId)
                    .collection("users").document(userId)
                    .update("syncHistory", FieldValue.arrayUnion(historyEntry))
            ).map(ignored -> (Void) null)
            .onFailure().recoverWithUni(() ->
                Uni.createFrom().future(
                    firestore.collection("clients").document(clientId)
                        .collection("users").document(userId)
                        .set(Map.of("syncHistory", java.util.Collections.singletonList(historyEntry)))
                ).map(ignored -> (Void) null)
            );
    }
}
