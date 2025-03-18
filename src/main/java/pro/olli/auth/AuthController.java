package pro.olli.auth;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.models.Invite;
import pro.olli.models.User;
import pro.olli.repository.UserRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Path("/auth")
public class AuthController {

    private static final Logger LOG = Logger.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final Mailer mailer;
    private final String appDomain;

    public AuthController(
        UserRepository userRepository,
        Mailer mailer,
        @ConfigProperty(name = "app.domain", defaultValue = "http://localhost:8080") String appDomain) {
        this.userRepository = userRepository;
        this.mailer = mailer;
        this.appDomain = appDomain;
    }
    
    // Health check moved to HealthResource to avoid conflicts

    @POST
    @Path("/invite")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> createInvite(InviteRequest inviteRequest) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String token = generateTemporaryToken();

                // Create an Invite model object instead of a raw map
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
                            // Send email non-blocking
                            mailer.send(Mail.withText(inviteRequest.email,
                                "You're Invited to Join Olli-AI",
                                "Click here to register: " + inviteLink + "\nExpires in 24 hours."));

                            LOG.infof("Invite sent to %s for business %s", inviteRequest.email, inviteRequest.businessId);
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
        return userRepository.getUser(loginRequest.email)
            .map(userOpt -> {
                if (userOpt.isEmpty()) {
                    return Response.status(401).entity(Map.of("error", "Invalid credentials")).build();
                }
                
                User user = userOpt.get();
                String storedHash = (String) user.getMetadata().get("hashedPassword");
                
                if (storedHash == null || !verifyPassword(loginRequest.password, storedHash)) {
                    return Response.status(401).entity(Map.of("error", "Invalid credentials")).build();
                }
                
                String jwt = issueJwt(user.getId());
                return Response.ok(Map.of("token", jwt, "userId", user.getId()))
                        .header("Authorization", "Bearer " + jwt)
                        .build();
            })
            .onFailure().invoke(e -> LOG.errorf("Login failed: %s", e.getMessage()));
    }

    @POST
    @Path("/register")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> register(RegisterRequest registerRequest) {
        return userRepository.getInvite(registerRequest.token)
            .flatMap(inviteOpt -> {
                if (inviteOpt.isEmpty()) {
                    return Uni.createFrom().item(
                        Response.status(400).entity(Map.of("error", "Invalid or expired token")).build()
                    );
                }

                // Use the Invite model object instead of raw map
                Invite invite = inviteOpt.get();
                String inviteEmail = invite.getEmail();
                String businessId = invite.getBusinessId();
                Instant expiresAt = invite.getExpiresAt();

                if (!inviteEmail.equals(registerRequest.email) || Instant.now().isAfter(expiresAt)) {
                    return Uni.createFrom().item(
                        Response.status(400).entity(Map.of("error", "Invalid or expired token")).build()
                    );
                }

                String hashedPassword = hashPassword(registerRequest.password);
                
                // Create a User model object instead of a raw map
                User user = new User(
                    registerRequest.email, // Using email as ID for simplicity
                    registerRequest.email, 
                    null, // No name provided in registration form
                    null, // No avatar URL yet
                    Instant.now(),
                    Instant.now(),
                    Map.of("businessId", businessId, "hashedPassword", hashedPassword),
                    null // Never store raw password in the model
                );

                return userRepository.saveUser(user)
                    .flatMap(ignored -> userRepository.deleteInvite(registerRequest.token))
                    .map(ignored -> {
                        String jwt = issueJwt(registerRequest.email);
                        return Response.ok(Map.of("token", jwt, "userId", registerRequest.email))
                               .header("Authorization", "Bearer " + jwt)
                               .build();
                    })
                    .onFailure().invoke(e -> LOG.errorf("Failed to register user: %s", e.getMessage()));
            });
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
    
    private String issueJwt(String email) { 
        // TODO: Implement proper JWT creation with jjwt or smallrye-jwt
        return "jwt-token-" + System.currentTimeMillis(); 
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