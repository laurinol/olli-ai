/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;
import pro.olli.service.SyncService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@QuarkusTest
class SyncResourceTest {

    @Inject
    SyncService syncService; // Real bean - we'll mock it

    private SyncResource syncResource;

    @BeforeEach
    void setUp() {
        syncResource = new SyncResource(syncService);
        syncResource.oidcEnabled = false; // Simulate dev profile
        // Mock the SyncService behavior
        SyncService spyService = Mockito.spy(syncService);
        syncResource = new SyncResource(spyService);
    }

    @Test
    void testSyncFolderLocalNoAuthSuccess() {
        when(syncService.sync("acme-corp", Mockito.anyString(), "local", "/test/test-files"))
            .thenReturn(Uni.createFrom().item("task-123"));

        SecurityContext ctx = Mockito.mock(SecurityContext.class);
        when(ctx.getUserPrincipal()).thenReturn(null); // No auth

        Uni<Response> responseUni = syncResource.syncFolder("local", "/test/test-files", "acme-corp", ctx);
        Response response = responseUni.await().indefinitely();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("task-123", ((Map<String, String>) response.getEntity()).get("taskId"));
    }

    @Test
    void testSyncFolderMissingBusinessId() {
        SecurityContext ctx = Mockito.mock(SecurityContext.class);
        when(ctx.getUserPrincipal()).thenReturn(null);

        Uni<Response> responseUni = syncResource.syncFolder("local", "/test/test-files", null, ctx);
        Response response = responseUni.await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals("Business ID is required", ((Map<String, String>) response.getEntity()).get("error"));
    }

    @Test
    void testSyncFolderWithAuthProd() {
        syncResource.oidcEnabled = true; // Prod mode
        Principal principal = Mockito.mock(Principal.class);
        when(principal.getName()).thenReturn("john-123");
        SecurityContext ctx = Mockito.mock(SecurityContext.class);
        when(ctx.getUserPrincipal()).thenReturn(principal);

        when(syncService.sync("acme-corp", "john-123", "google", "folder123"))
            .thenReturn(Uni.createFrom().item("task-456"));

        Uni<Response> responseUni = syncResource.syncFolder("google", "folder123", "acme-corp", ctx);
        Response response = responseUni.await().indefinitely();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("task-456", ((Map<String, String>) response.getEntity()).get("taskId"));
    }
}
