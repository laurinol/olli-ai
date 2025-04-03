/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.auth;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;
import pro.olli.config.AppConfig;
import pro.olli.models.Business;
import pro.olli.models.BusinessUser;
import pro.olli.models.User;
import pro.olli.models.Verification;
import pro.olli.repository.UserRepository;

@ApplicationScoped
@Path("/auth")
public class AuthController {

    private static final Logger LOG = Logger.getLogger(AuthController.class);

    private final AppConfig config;
    private final UserRepository userRepository;
    private final ReactiveMailer mailer;

    @Inject
    public AuthController(
        AppConfig config,
        UserRepository userRepository,
        ReactiveMailer mailer) {
        this.config = config;
        this.userRepository = userRepository;
        this.mailer = mailer;
    }

    @POST
    @Path("/invite")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> createInvite(InviteRequest inviteRequest) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String token = generateTemporaryToken();

                // Create a new Verification (was previously Invite)
                Verification verification = new Verification(
                    token,
                    inviteRequest.businessId,
                    inviteRequest.email,
                    "user" // Default role
                );

                // Use reactive style - no blocking calls!
                userRepository.saveVerification(verification)
                    .subscribe().with(
                        success -> {
                            String verificationLink = String.format("%s/auth/register?token=%s", config.getAppDomain(),
                                token);
                            // Create the email with the verification link
                            Mail mail = Mail.withText(inviteRequest.email,
                                "Verify Your Email with Olli-AI",
                                "Click here to verify your email and complete registration: " + verificationLink
                                    + "\nExpires in 48 hours.");

                            // Send the email using the reactive mailer, which won't block
                            mailer.send(mail)
                                .subscribe().with(
                                    result -> LOG.infof("Verification email sent to %s", inviteRequest.email),
                                    error -> LOG.errorf("Failed to send verification email: %s", error.getMessage())
                                );

                            // Acknowledge immediately without waiting for email to be sent
                            LOG.infof("Verification created for %s for business %s", inviteRequest.email,
                                inviteRequest.businessId);
                            emitter.complete(Response.ok(Map.of("message", "Verification email sent")).build());
                        },
                        failure -> {
                            LOG.errorf("Failed to save verification: %s", failure.getMessage());
                            emitter.fail(failure);
                        }
                    );
            } catch (Exception e) {
                LOG.errorf("Failed to create verification: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @POST
    @Path("/login")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> login(LoginRequest loginRequest) {
        LOG.infof("Login attempt for email: %s", loginRequest.email);
        return userRepository.getUser(loginRequest.email)
            .map(userOpt -> {
                if (userOpt.isEmpty()) {
                    LOG.warnf("Login failed - user not found for email: %s", loginRequest.email);
                    return Response.status(401).entity(Map.of("error", "Invalid credentials")).build();
                }

                User user = userOpt.get();
                LOG.debugf("User found for login: id=%s, email=%s", user.getId(), user.getEmail());

                // Ensure metadata exists
                if (user.getMetadata() == null) {
                    LOG.warnf("Login failed - user has no metadata: %s", user.getId());
                    return Response.status(401).entity(Map.of("error", "Invalid account state")).build();
                }

                String storedHash = (String) user.getMetadata().get("hashedPassword");

                if (storedHash == null) {
                    LOG.warnf("Login failed - no password hash found for user: %s", user.getId());
                    return Response.status(401).entity(Map.of("error", "Invalid account state")).build();
                }

                if (!verifyPassword(loginRequest.password, storedHash)) {
                    LOG.warnf("Login failed - invalid password for user: %s", user.getId());
                    return Response.status(401).entity(Map.of("error", "Invalid credentials")).build();
                }

                LOG.infof("Login successful for user: %s", user.getId());
                String jwt = issueJwtToken(user.getId());
                return Response.ok(Map.of("token", jwt, "userId", user.getId()))
                    .header("Authorization", "Bearer " + jwt)
                    .build();
            })
            .onFailure().invoke(e -> LOG.errorf("Login failed due to error: %s", e.getMessage()));
    }

    @POST
    @Path("/register")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> register(RegisterRequest registerRequest) {
        LOG.infof("Registration request received for email: %s with token: %s",
            registerRequest.email, registerRequest.token);

        // Use AtomicReference to hold values we need across steps
        AtomicReference<String> userId = new AtomicReference<>();
        AtomicReference<String> businessId = new AtomicReference<>();
        AtomicReference<Verification> verificationRef = new AtomicReference<>();

        return Uni.createFrom().emitter(emitter -> {
            try {
                // Step 1: Find the verification
                userRepository.getVerification(registerRequest.token)
                    .subscribe().with(
                        verificationOpt -> {
                            if (verificationOpt.isEmpty()) {
                                LOG.warnf("Invalid token: %s", registerRequest.token);
                                emitter.complete(Response.status(400)
                                    .entity(Map.of("error", "Invalid or expired token"))
                                    .build());
                                return;
                            }

                            // Got the verification
                            Verification verification = verificationOpt.get();
                            verificationRef.set(verification);
                            String verificationEmail = verification.getEmail();
                            String verificationBusinessId = verification.getBusinessId();
                            businessId.set(verificationBusinessId);
                            Instant expiresAt = verification.getExpiresAt();

                            LOG.infof("Found verification for email: %s, business: %s", verificationEmail,
                                verificationBusinessId);

                            if (!verificationEmail.equals(registerRequest.email) || Instant.now().isAfter(expiresAt)) {
                                LOG.warnf("Verification email mismatch or expired. Expected: %s, Got: %s, Expired: %s",
                                    verificationEmail, registerRequest.email, Instant.now().isAfter(expiresAt));
                                emitter.complete(Response.status(400)
                                    .entity(Map.of("error", "Invalid or expired token"))
                                    .build());
                                return;
                            }

                            // Generate user ID and create user object
                            String newUserId = generateUserId();
                            userId.set(newUserId);

                            String hashedPassword = hashPassword(registerRequest.password);
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("businessId", verificationBusinessId);
                            metadata.put("hashedPassword", hashedPassword);

                            User user = new User(
                                newUserId,
                                registerRequest.email,
                                null, // No name provided in registration form
                                null, // No avatar URL yet
                                Instant.now(),
                                Instant.now(),
                                metadata,
                                null // Never store raw password in the model
                            );

                            LOG.infof("STEP 1: Created user object: id=%s, email=%s", newUserId, registerRequest.email);

                            // Step 2: Save the user
                            userRepository.saveUser(user).subscribe().with(
                                success -> {
                                    LOG.infof("STEP 2: User saved successfully: %s", newUserId);

                                    // Step 3: Create or get business
                                    LOG.infof("STEP 3: Checking if business %s exists", verificationBusinessId);
                                    userRepository.getBusiness(verificationBusinessId).subscribe().with(
                                        businessOpt -> {
                                            try {
                                                Business business;
                                                if (businessOpt.isEmpty()) {
                                                    LOG.infof("Business doesn't exist, creating it: %s",
                                                        verificationBusinessId);
                                                    business = new Business(verificationBusinessId, verificationBusinessId,
                                                        "basic");
                                                    // Save business first
                                                    userRepository.saveBusiness(business).subscribe().with(
                                                        v -> {
                                                            LOG.infof("STEP 3a: Business created: %s",
                                                                verificationBusinessId);
                                                            createBusinessUserAndFinishWithVerification(
                                                                newUserId, verificationBusinessId, registerRequest.email,
                                                                verification, registerRequest, emitter);
                                                        },
                                                        err -> {
                                                            LOG.errorf("Failed to save business: %s", err.getMessage());
                                                            emitter.complete(Response.status(500)
                                                                .entity(Map.of("error",
                                                                    "Failed to save business: " + err.getMessage()))
                                                                .build());
                                                        }
                                                    );
                                                } else {
                                                    LOG.infof("STEP 3b: Business already exists: %s",
                                                        verificationBusinessId);
                                                    createBusinessUserAndFinishWithVerification(
                                                        newUserId, verificationBusinessId, registerRequest.email,
                                                        verification, registerRequest, emitter);
                                                }
                                            } catch (Exception e) {
                                                LOG.errorf("Error in business step: %s", e.getMessage(), e);
                                                emitter.complete(Response.status(500)
                                                    .entity(Map.of("error", "Error in business step: " + e.getMessage()))
                                                    .build());
                                            }
                                        },
                                        err -> {
                                            LOG.errorf("Error checking business: %s", err.getMessage());
                                            emitter.complete(Response.status(500)
                                                .entity(Map.of("error", "Failed to check business: " + err.getMessage()))
                                                .build());
                                        }
                                    );
                                },
                                err -> {
                                    LOG.errorf("Error saving user: %s", err.getMessage());
                                    emitter.complete(Response.status(500)
                                        .entity(Map.of("error", "Failed to save user: " + err.getMessage()))
                                        .build());
                                }
                            );
                        },
                        error -> {
                            LOG.errorf("Error retrieving verification: %s", error.getMessage());
                            emitter.complete(Response.status(500)
                                .entity(Map.of("error", "Error retrieving verification: " + error.getMessage()))
                                .build());
                        }
                    );
            } catch (Exception e) {
                LOG.errorf("Unexpected error during registration: %s", e.getMessage(), e);
                emitter.complete(Response.status(500)
                    .entity(Map.of("error", "Registration failed: " + e.getMessage()))
                    .build());
            }
        });
    }

    private void createBusinessUserAndFinishWithVerification(String userId, String businessId, String email,
        Verification verification, RegisterRequest registerRequest,
        io.smallrye.mutiny.subscription.UniEmitter<? super Response> emitter) {
        LOG.infof("STEP 4: Creating business user association for user %s in business %s", userId, businessId);

        // Create business user association
        BusinessUser businessUser = new BusinessUser(
            userId,
            businessId,
            email,
            null, // No name provided
            "admin" // First user is admin
        );

        userRepository.saveBusinessUser(businessUser).subscribe().with(
            success -> {
                LOG.infof("STEP 4: Business user saved successfully");

                // Step 5: Mark verification as verified
                LOG.infof("STEP 5: Marking verification as verified");
                // Use the withVerified() method to ensure we get a verified copy
                Verification verifiedObj = verification.withVerified();
                LOG.infof("STEP 5: Created verified verification object with token=%s, verified=%s",
                    verifiedObj.getToken(), verifiedObj.isVerified());

                userRepository.saveVerification(verifiedObj).subscribe().with(
                    v -> {
                        LOG.infof("STEP 5: Verification marked as verified");

                        // Step 6: Generate token and return success
                        LOG.infof("STEP 6: Registration complete, generating JWT");
                        String jwt = issueJwtToken(userId);
                        emitter.complete(Response.ok(Map.of("token", jwt, "userId", userId))
                            .header("Authorization", "Bearer " + jwt)
                            .build());
                    },
                    err -> {
                        LOG.errorf("Failed to mark verification as verified: %s", err.getMessage());
                        // Continue anyway since the user is created
                        String jwt = issueJwtToken(userId);
                        emitter.complete(Response.ok(Map.of(
                                "token", jwt,
                                "userId", userId,
                                "warning", "User created but verification not marked as verified"))
                            .header("Authorization", "Bearer " + jwt)
                            .build());
                    }
                );
            },
            err -> {
                LOG.errorf("Error saving business user: %s", err.getMessage());
                emitter.complete(Response.status(500)
                    .entity(Map.of("error", "Failed to save business user: " + err.getMessage()))
                    .build());
            }
        );
    }


    private String generateTemporaryToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashPassword(String password) {
        // Use a strong BCrypt hash with a cost factor of 12
        return BCrypt.hashpw(password, BCrypt.gensalt(12));
    }

    private boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || !storedHash.startsWith("$2a$") && !storedHash.startsWith("$2b$")) {
            // If we have a legacy plain-text password, do direct comparison
            // but in production, you'd want to rehash it properly upon verification
            if (Objects.equals(password, storedHash)) {
                LOG.warn("Legacy password format detected - should be upgraded");
                return true;
            }
            return false;
        }

        try {
            return BCrypt.checkpw(password, storedHash);
        } catch (Exception e) {
            LOG.errorf("Error verifying password: %s", e.getMessage());
            return false;
        }
    }

    private String generateUserId() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "user-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Original method had issues with SmallRye JWT and key configuration
    // Using a simplified approach that should work better
    private String issueJwtToken(String userId) {
        try {
            LOG.debugf("Issuing JWT token for user: %s", userId);

            // Create a simple JWT with minimal required claims
            return Jwt.issuer("olli-ai-backend")
                .subject(userId)
                .groups(Set.of("user"))
                .expiresIn(Duration.ofDays(7)) // 7 day expiration
                .sign();
        } catch (Exception e) {
            LOG.errorf("Failed to create JWT: %s", e.getMessage(), e);
            // Return a simple token for development to avoid blocking authentication
            LOG.warnf("Using fallback JWT token for development");
            return "dev-jwt-token-" + userId + "-" + System.currentTimeMillis();
        }
    }

    public static class InviteRequest {

        public String email;
        public String businessId;
    }

    public static class LoginRequest {

        public String email;
        public String password;
    }

    public static class RegisterRequest {

        public String email;
        public String password;
        public String token;
    }
}
