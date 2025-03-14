/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository;

import com.google.cloud.firestore.Firestore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class FirestoreUserRepository implements UserRepository {

    private final Firestore firestore;

    public FirestoreUserRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    // From AuthController
    @Override
    public Uni<Void> saveUser(String email, Map<String, Object> userData) {
        return Uni.createFrom().future(
            firestore.collection("users").document(email).set(userData)
        ).map(ignored -> (Void) null);
    }

    @Override
    public Uni<Void> saveInvite(String token, Map<String, Object> inviteData) {
        return Uni.createFrom().future(
            firestore.collection("invites").document(token).set(inviteData)
        ).map(ignored -> (Void) null);
    }

    @Override
    public Uni<Optional<Map<String, Object>>> getInvite(String token) {
        return Uni.createFrom().future(
            firestore.collection("invites").document(token).get()
        ).map(doc -> doc.exists() ? Optional.of(doc.getData()) : Optional.empty());
    }

    @Override
    public Uni<Void> deleteInvite(String token) {
        return Uni.createFrom().future(
            firestore.collection("invites").document(token).delete()
        ).map(ignored -> (Void) null);
    }

    // For AuthResource
    @Override
    public Uni<Optional<Map<String, Object>>> getBusiness(String businessId) {
        return Uni.createFrom().future(
            firestore.collection("clients").document(businessId).get()
        ).map(doc -> doc.exists() ? Optional.of(doc.getData()) : Optional.empty());
    }

    @Override
    public Uni<Void> saveBusiness(String businessId, Map<String, Object> businessData) {
        return Uni.createFrom().future(
            firestore.collection("clients").document(businessId).set(businessData)
        ).map(ignored -> (Void) null);
    }

    @Override
    public Uni<Void> saveBusinessUser(String businessId, String userId, Map<String, Object> userData) {
        return Uni.createFrom().future(
            firestore.collection("clients").document(businessId)
                .collection("users").document(userId).set(userData)
        ).map(ignored -> (Void) null);
    }
}
