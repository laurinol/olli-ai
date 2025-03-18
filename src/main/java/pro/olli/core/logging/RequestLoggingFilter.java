/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.core.logging;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * Request logging filter for traceability and monitoring.
 * Adds trace IDs to all requests and logs request/response details.
 */
@Provider
@Priority(Priorities.USER)
public class RequestLoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String TRACE_ID = "traceId";
    private static final String REQUEST_START_TIME = "requestStartTime";

    @Inject
    StructuredLogger logger;

    @Context
    UriInfo uriInfo;

    @Context
    SecurityContext securityContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Generate or extract trace ID
        String traceId = requestContext.getHeaderString("X-Trace-ID");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        // Store in MDC for logging context
        MDC.put(TRACE_ID, traceId);
        requestContext.setProperty(TRACE_ID, traceId);
        requestContext.setProperty(REQUEST_START_TIME, System.currentTimeMillis());

        // Add user info if available
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            String userId = securityContext.getUserPrincipal().getName();
            MDC.put("userId", userId);
            requestContext.setProperty("userId", userId);
        }

        // Log the request
        logger.info("API Request", Map.of(
            "method", requestContext.getMethod(),
            "path", uriInfo.getPath(),
            "traceId", traceId,
            "remoteAddr", requestContext.getHeaderString("X-Forwarded-For") != null ?
                requestContext.getHeaderString("X-Forwarded-For") :
                "direct-call"
        ));
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String traceId = (String) requestContext.getProperty(TRACE_ID);
        Long startTime = (Long) requestContext.getProperty(REQUEST_START_TIME);
        Long duration = startTime != null ? System.currentTimeMillis() - startTime : null;

        // Log the response
        logger.info("API Response", Map.of(
            "method", requestContext.getMethod(),
            "path", uriInfo.getPath(),
            "traceId", traceId != null ? traceId : "unknown",
            "statusCode", responseContext.getStatus(),
            "durationMs", duration != null ? duration : -1
        ));

        // Log metrics for monitoring
        if (duration != null) {
            logger.metric("api.request.duration", duration, Map.of(
                "method", requestContext.getMethod(),
                "path", uriInfo.getPath(),
                "statusCode", responseContext.getStatus()
            ));
        }

        // Clear MDC at the end of the request
        MDC.clear();
    }
}