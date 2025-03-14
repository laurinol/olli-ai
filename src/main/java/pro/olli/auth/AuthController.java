package pro.olli.auth;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.repository.UserRepository;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

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

    @POST
    @Path("/invite")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> createInvite(InviteRequest inviteRequest) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String token = generateTemporaryToken();
                long expiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000;

                Map<String, Object> inviteData = new HashMap<>();
                inviteData.put("email", inviteRequest.email);
                inviteData.put("businessId", inviteRequest.businessId);
                inviteData.put("token", token);
                inviteData.put("expiresAt", expiresAt);
                inviteData.put("createdAt", System.currentTimeMillis()); // Simplified for abstraction

                userRepository.saveInvite(token, inviteData).await().indefinitely();

                String inviteLink = String.format("%s/auth/register?token=%s", appDomain, token);
                mailer.send(Mail.withText(inviteRequest.email,
                    "You’re Invited to Join Olli-AI",
                    "Click here to register: " + inviteLink + "\nExpires in 24 hours."));

                LOG.infof("Invite sent to %s for business %s", inviteRequest.email, inviteRequest.businessId);
                emitter.complete(Response.ok(Map.of("message", "Invite sent")).build());
            } catch (Exception e) {
                LOG.errorf("Failed to create invite: %s", e.getMessage());
                emitter.fail(e);
            }
        });
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

                Map<String, Object> inviteData = inviteOpt.get();
                String inviteEmail = (String) inviteData.get("email");
                String businessId = (String) inviteData.get("businessId");
                long expiresAt = (Long) inviteData.get("expiresAt");

                if (!inviteEmail.equals(registerRequest.email) || System.currentTimeMillis() > expiresAt) {
                    return Uni.createFrom().item(
                        Response.status(400).entity(Map.of("error", "Invalid or expired token")).build()
                    );
                }

                String hashedPassword = hashPassword(registerRequest.password);
                Map<String, Object> userData = new HashMap<>();
                userData.put("email", registerRequest.email);
                userData.put("hashedPassword", hashedPassword);
                userData.put("businessId", businessId);
                userData.put("createdAt", System.currentTimeMillis()); // Simplified

                return userRepository.saveUser(registerRequest.email, userData)
                    .flatMap(ignored -> userRepository.deleteInvite(registerRequest.token))
                    .map(ignored -> {
                        String jwt = issueJwt(registerRequest.email);
                        return Response.ok().header("Authorization", "Bearer " + jwt).build();
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

    private String hashPassword(String password) { return password; } // TODO: Implement
    private String issueJwt(String email) { return "jwt-token"; } // TODO: Implement

    public static class InviteRequest {
        public String email;
        public String businessId;
    }

    public static class RegisterRequest {
        public String email;
        public String password;
        public String token;
    }
}
