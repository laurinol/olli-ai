/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.auth;

import io.quarkus.oidc.runtime.OidcUtils;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/auth")
public class AuthController {

    @Inject
    OidcUtils oidcUtils;

    @ConfigProperty(name = "quarkus.smallrye-jwt.sign.key")
    String jwtSecret;

    @GET
    @Path("/google/callback")
    public Response googleCallback(@QueryParam("code") String code) {
        // Exchange code for token, get user info, issue JWT
        String jwt = issueJwt("google", "user-id-from-google");
        return Response.ok().header("Authorization", "Bearer " + jwt).build();
    }

    // Similar for facebook, microsoft...

    @GET
    @Path("/x/callback")
    public Response xCallback(@QueryParam("oauth_token") String token, @QueryParam("oauth_verifier") String verifier) {
        // Custom X OAuth 1.0a flow
        String jwt = issueJwt("x", "user-id-from-x");
        return Response.ok().header("Authorization", "Bearer " + jwt).build();
    }

    private String issueJwt(String provider, String userId) {
        return Jwt.issuer("olli-ai")
            .subject(userId)
            .claim("provider", provider)
            .signWithSecret(jwtSecret);
    }
}
