package pro.olli.auth;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.models.Business;
import pro.olli.models.BusinessUser;
import pro.olli.models.Invite;
import pro.olli.models.User;
import pro.olli.repository.UserRepository;
import pro.olli.repository.Factory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Path("/auth")
public class AuthController {

    private static final Logger LOG = Logger.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final ReactiveMailer mailer;
    private final String appDomain;

    @Inject
    public AuthController(
        @Factory UserRepository userRepository,
        ReactiveMailer mailer,
        @ConfigProperty(name = "app.domain", defaultValue = "http://localhost:8080") String appDomain) {
        this.userRepository = userRepository;
        this.mailer = mailer;
        this.appDomain = appDomain;
    }

    @POST
    @Path("/invite")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> createInvite(InviteRequest inviteRequest) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String token = generateTemporaryToken();

                // Create a new Invite
                Invite invite = new Invite(
                    token,
                    inviteRequest.businessId,
                    inviteRequest.email,
                    "user", // Default role
                    null, // No inviter info in this context
                    Instant.now(),
                    Instant.now().plusSeconds(24 * 60 * 60),
                    false // Not claimed yet
                );

                // Use reactive style - no blocking calls!
                userRepository.saveInvite(invite)
                    .subscribe().with(
                        success -> {
                            String inviteLink = String.format("%s/auth/register?token=%s", appDomain, token);
                            // Create the email with the invite link
                            Mail mail = Mail.withText(inviteRequest.email,
                                "You're Invited to Join Olli-AI",
                                "Click here to register: " + inviteLink + "\nExpires in 24 hours.");

                            // Send the email using the reactive mailer, which won't block
                            mailer.send(mail)
                                .subscribe().with(
                                    result -> LOG.infof("Email sent to %s", inviteRequest.email),
                                    error -> LOG.errorf("Failed to send email: %s", error.getMessage())
                                );

                            // Acknowledge immediately without waiting for email to be sent
                            LOG.infof("Invite created for %s for business %s", inviteRequest.email, inviteRequest.businessId);
                            emitter.complete(Response.ok(Map.of("message", "Invite sent")).build());
                        },
                        failure -> {
                            LOG.errorf("Failed to save invite: %s", failure.getMessage());
                            emitter.fail(failure);
                        }
                    );
            } catch (Exception e) {
                LOG.errorf("Failed to create invite: %s", e.getMessage());
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
                String jwt = issueJwt(user.getId());
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
        AtomicReference<Invite> inviteRef = new AtomicReference<>();

        return Uni.createFrom().emitter(emitter -> {
            try {
                // Step 1: Find the invite
                userRepository.getInvite(registerRequest.token)
                    .subscribe().with(
                        inviteOpt -> {
                            if (inviteOpt.isEmpty()) {
                                LOG.warnf("Invalid token: %s", registerRequest.token);
                                emitter.complete(Response.status(400)
                                    .entity(Map.of("error", "Invalid or expired token"))
                                    .build());
                                return;
                            }

                            // Got the invite
                            Invite invite = inviteOpt.get();
                            inviteRef.set(invite);
                            String inviteEmail = invite.getEmail();
                            String inviteBusinessId = invite.getBusinessId();
                            businessId.set(inviteBusinessId);
                            Instant expiresAt = invite.getExpiresAt();

                            LOG.infof("Found invite for email: %s, business: %s", inviteEmail, inviteBusinessId);

                            if (!inviteEmail.equals(registerRequest.email) || Instant.now().isAfter(expiresAt)) {
                                LOG.warnf("Invite email mismatch or expired. Expected: %s, Got: %s, Expired: %s",
                                    inviteEmail, registerRequest.email, Instant.now().isAfter(expiresAt));
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
                            metadata.put("businessId", inviteBusinessId);
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
                                    LOG.infof("STEP 3: Checking if business %s exists", inviteBusinessId);
                                    userRepository.getBusiness(inviteBusinessId).subscribe().with(
                                        businessOpt -> {
                                            try {
                                                Business business;
                                                if (businessOpt.isEmpty()) {
                                                    LOG.infof("Business doesn't exist, creating it: %s", inviteBusinessId);
                                                    business = new Business(inviteBusinessId, inviteBusinessId, "basic");
                                                    // Save business first
                                                    userRepository.saveBusiness(business).subscribe().with(
                                                        v -> {
                                                            LOG.infof("STEP 3a: Business created: %s", inviteBusinessId);
                                                            createBusinessUserAndFinish(
                                                                newUserId, inviteBusinessId, registerRequest.email,
                                                                invite, registerRequest, emitter);
                                                        },
                                                        err -> {
                                                            LOG.errorf("Failed to save business: %s", err.getMessage());
                                                            emitter.complete(Response.status(500)
                                                                .entity(Map.of("error", "Failed to save business: " + err.getMessage()))
                                                                .build());
                                                        }
                                                    );
                                                } else {
                                                    LOG.infof("STEP 3b: Business already exists: %s", inviteBusinessId);
                                                    createBusinessUserAndFinish(
                                                        newUserId, inviteBusinessId, registerRequest.email,
                                                        invite, registerRequest, emitter);
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
                            LOG.errorf("Error retrieving invite: %s", error.getMessage());
                            emitter.complete(Response.status(500)
                                .entity(Map.of("error", "Error retrieving invite: " + error.getMessage()))
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

    private void createBusinessUserAndFinish(String userId, String businessId, String email,
                                            Invite invite, RegisterRequest registerRequest,
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

                // Step 5: Mark invite as claimed
                LOG.infof("STEP 5: Marking invite as claimed");
                // Use the withClaimed() method to ensure we get a claimed copy
                Invite claimedInvite = invite.withClaimed();
                LOG.infof("STEP 5: Created claimed invite object with token=%s, claimed=%s",
                        claimedInvite.getToken(), claimedInvite.isClaimed());

                userRepository.saveInvite(claimedInvite).subscribe().with(
                    v -> {
                        LOG.infof("STEP 5: Invite marked as claimed");

                        // Step 6: Generate token and return success
                        LOG.infof("STEP 6: Registration complete, generating JWT");
                        String jwt = issueJwt(userId);
                        emitter.complete(Response.ok(Map.of("token", jwt, "userId", userId))
                            .header("Authorization", "Bearer " + jwt)
                            .build());
                    },
                    err -> {
                        LOG.errorf("Failed to mark invite as claimed: %s", err.getMessage());
                        // Continue anyway since the user is created
                        String jwt = issueJwt(userId);
                        emitter.complete(Response.ok(Map.of(
                                "token", jwt,
                                "userId", userId,
                                "warning", "User created but invite not marked as claimed"))
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
        // TODO: Implement proper password hashing with bcrypt/Argon2/PBKDF2
        return password;
    }

    private boolean verifyPassword(String password, String storedHash) {
        // TODO: Implement proper password verification with bcrypt/Argon2/PBKDF2
        return Objects.equals(password, storedHash);
    }

    private String generateUserId() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "user-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String issueJwt(String userId) {
        // TODO: Implement proper JWT creation with jjwt or smallrye-jwt
        return "jwt-token-" + userId + "-" + System.currentTimeMillis();
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
