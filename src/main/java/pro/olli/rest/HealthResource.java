/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * Simple health check endpoint for application monitoring.
 */
@Path("/")
@ApplicationScoped
public class HealthResource {

    private static final Logger LOG = Logger.getLogger(HealthResource.class);

    /**
     * Simple health check endpoint at the root path.
     * 
     * @return "OK" if the application is running
     */
    @GET
    @Path("/health")
    @Produces(MediaType.TEXT_PLAIN)
    public String healthCheck() {
        LOG.info("Health check at /health requested");
        return "OK";
    }

    /**
     * Auth-specific health check endpoint.
     * This endpoint is used by the e2e-manual-test.sh script.
     * 
     * @return "OK" if the application is running
     */
    @GET
    @Path("/auth/health")
    @Produces(MediaType.TEXT_PLAIN)
    public String authHealthCheck() {
        LOG.info("Health check at /auth/health requested");
        return "OK";
    }

    /**
     * Simple test endpoint for troubleshooting.
     * This endpoint always returns success and is useful for checking if the application is responsive.
     * 
     * @return "Test endpoint is alive!" if the application is running
     */
    @GET
    @Path("/test-alive")
    @Produces(MediaType.TEXT_PLAIN)
    public String testAlive() {
        LOG.info("Test-alive endpoint requested");
        return "Test endpoint is alive!";
    }
}
