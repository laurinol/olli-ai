/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;
import pro.olli.service.SyncService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the SyncResource endpoint.
 */
@QuarkusTest
class SyncResourceTest {

    private SyncService syncService;
    private SyncResource syncResource;

    @BeforeEach
    void setUp() {
        // Use a mock SyncService instead of injecting the real one
        syncService = mock(SyncService.class);
        syncResource = new SyncResource(syncService);
        syncResource.oidcEnabled = false; // Simulate dev profile
    }

    @Test
    void testSyncFolderLocalNoAuthSuccess() {
        // Arrange - set up mock service
        when(syncService.sync(
                eq("acme-corp"),    // Business ID should match exactly
                anyString(),        // User ID can be anything
                eq("local"),        // Connector type should match exactly
                eq("/test/test-files"))) // Path should match exactly
            .thenReturn(Uni.createFrom().item("task-123"));

        // Mock security context with no auth
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getUserPrincipal()).thenReturn(null);

        // Act - call the endpoint
        Uni<Response> responseUni = syncResource.syncFolder("local", "/test/test-files", "acme-corp", ctx);
        Response response = responseUni.await().indefinitely();

        // Assert - check response
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("task-123", ((Map<String, String>) response.getEntity()).get("taskId"));
    }

    @Test
    void testSyncFolderMissingBusinessId() {
        // Arrange - mock security context with no auth
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getUserPrincipal()).thenReturn(null);

        // Act - call without business ID
        Uni<Response> responseUni = syncResource.syncFolder("local", "/test/test-files", null, ctx);
        Response response = responseUni.await().indefinitely();

        // Assert - should get bad request
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals("Business ID is required", ((Map<String, String>) response.getEntity()).get("error"));
    }

    @Test
    void testSyncFolderWithAuthProd() {
        // Arrange - set up for prod mode with authentication
        syncResource.oidcEnabled = true;
        
        // Mock authenticated user
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("john-123");
        
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getUserPrincipal()).thenReturn(principal);

        // Set up mock service for this scenario
        when(syncService.sync(
                eq("acme-corp"),  // Business ID
                eq("john-123"),   // User ID from principal
                eq("google"),     // Connector type
                eq("folder123"))) // Path
            .thenReturn(Uni.createFrom().item("task-456"));

        // Act - call the endpoint
        Uni<Response> responseUni = syncResource.syncFolder("google", "folder123", "acme-corp", ctx);
        Response response = responseUni.await().indefinitely();

        // Assert - check response
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("task-456", ((Map<String, String>) response.getEntity()).get("taskId"));
    }
}