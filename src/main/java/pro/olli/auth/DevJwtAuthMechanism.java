/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.auth;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Set;

/**
 * A simple development-only authentication mechanism that accepts development tokens.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class DevJwtAuthMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(DevJwtAuthMechanism.class);
    private static final String PREFIX = "Bearer ";
    private static final String DEV_TOKEN_PREFIX = "dev-jwt-token-";

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        // Get the Authorization header
        String auth = context.request().getHeader(HttpHeaderNames.AUTHORIZATION.toString());

        if (auth != null && auth.startsWith(PREFIX)) {
            String token = auth.substring(PREFIX.length());

            // If this is a development token, extract the user ID
            if (token.startsWith(DEV_TOKEN_PREFIX)) {
                LOG.info("Authenticating dev token: " + token);

                // Extract user ID from token (format: dev-jwt-token-user-xxx...)
                String[] parts = token.split("-");
                String userId;
                if (parts.length > 3) {
                    userId = parts[3]; // Get the user part
                } else {
                    userId = "unknown";
                }

                // Create a security identity with admin role
                QuarkusSecurityIdentity identity = QuarkusSecurityIdentity.builder()
                    .setPrincipal(() -> userId)
                    .addRole("user")
                    .addRole("admin")
                    .build();

                return Uni.createFrom().item(identity);
            }
        }

        // Not our token, let other mechanisms handle it
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, "Bearer", "realm=development"));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Collections.emptySet();
    }
}
