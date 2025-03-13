/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.auth;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pro.olli.models.User;
import java.util.HashMap;
import java.util.Map;

@Path("/auth")
public class AuthController {

    @Inject
    Firestore firestore;

    @POST
    @Path("/register")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> register(User user) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Hash password (e.g., with BCrypt)
                String hashedPassword = hashPassword(user.password);
                Map<String, Object> userData = new HashMap<>();
                userData.put("email", user.email);
                userData.put("hashedPassword", hashedPassword);
                userData.put("createdAt", FieldValue.serverTimestamp());

                firestore.collection("users").document(user.email).set(userData).get();
                String jwt = issueJwt(user.email); // Implement JWT issuance
                emitter.complete(Response.ok().header("Authorization", "Bearer " + jwt).build());
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }

    @POST
    @Path("/login")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> login(User user) { // User as LoginRequest
        return Uni.createFrom().emitter(emitter -> {
            try {
                DocumentSnapshot doc = firestore.collection("users").document(user.email).get().get();
                if (doc.exists()) {
                    String storedHash = doc.getString("hashedPassword");
                    if (verifyPassword(user.password, storedHash)) {
                        String jwt = issueJwt(user.email);
                        emitter.complete(Response.ok().header("Authorization", "Bearer " + jwt).build());
                    } else {
                        emitter.complete(Response.status(401).build());
                    }
                } else {
                    emitter.complete(Response.status(401).build());
                }
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }

    // Stub methods
    private String hashPassword(String password) { /* Implement hashing */ return password; }
    private boolean verifyPassword(String password, String hash) { /* Implement verification */ return password.equals(hash); }
    private String issueJwt(String email) { /* Implement JWT */ return "jwt-token"; }
}
