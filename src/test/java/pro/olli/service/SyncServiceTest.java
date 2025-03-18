/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service;

import com.google.gson.Gson;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pro.olli.core.logging.TracingService;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.SyncRepository;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Removing QuarkusTest so we don't need CDI injection for this unit test
class SyncServiceTest {

    private SyncService syncService;
    private TaskQueueService taskQueueService;
    private StorageFactory storageFactory;
    private SyncRepository syncRepository;
    private TracingService tracingService;
    private Gson gson;

    @BeforeEach
    void setUp() {
        taskQueueService = Mockito.mock(TaskQueueService.class);
        storageFactory = Mockito.mock(StorageFactory.class);
        syncRepository = Mockito.mock(SyncRepository.class);
        tracingService = Mockito.mock(TracingService.class);
        gson = new Gson(); // Use real Gson instance - can't mock final classes
        String projectId = "test-project"; // Dummy value
        String location = "us-test1"; // Dummy value
        syncService = new SyncService(taskQueueService, storageFactory, syncRepository, gson, tracingService, projectId, location);
    }

    @Test
    void testSyncSuccess() {
        // Arrange
        StorageProvider provider = Mockito.mock(StorageProvider.class);
        when(storageFactory.getProvider("local")).thenReturn(provider);
        when(taskQueueService.enqueueTask(anyString(), anyString()))
            .thenReturn(Uni.createFrom().nullItem());
        when(syncRepository.appendSyncHistory(eq("acme-corp"), eq("john-123"), any()))
            .thenReturn(Uni.createFrom().nullItem());
        
        // Setup tracing mocks - make withClientSpan execute the provided lambda
        when(tracingService.withClientSpan(anyString(), any(), any())).thenAnswer(invocation -> {
            return ((java.util.function.Supplier<?>) invocation.getArgument(1)).get();
        });
        doNothing().when(tracingService).addEvent(anyString(), any());

        // Act
        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "local", "/test/test-files");
        String taskId = taskIdUni.await().indefinitely();

        // Assert
        assertEquals(36, taskId.length(), "Task ID should be a UUID"); // UUID length
        verify(taskQueueService, times(1)).enqueueTask(eq(taskId), anyString());
        verify(syncRepository, times(1)).appendSyncHistory(eq("acme-corp"), eq("john-123"), any());
    }

    @Test
    void testSyncUnsupportedSource() {
        // Arrange
        when(storageFactory.getProvider("invalid")).thenReturn(null);
        
        // Setup tracing mocks
        when(tracingService.withClientSpan(anyString(), any(), any())).thenAnswer(invocation -> {
            return ((java.util.function.Supplier<?>) invocation.getArgument(1)).get();
        });
        doNothing().when(tracingService).addEvent(anyString(), any());

        // Act
        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "invalid", "/test/test-files");
        String result = taskIdUni.await().indefinitely();

        // Assert
        assertEquals("Unsupported source: invalid", result, "Should return unsupported source message");
        verify(taskQueueService, never()).enqueueTask(anyString(), anyString());
        verify(syncRepository, never()).appendSyncHistory(anyString(), anyString(), any());
    }

    @Test
    void testSyncTaskQueueFailure() {
        // Arrange
        StorageProvider provider = Mockito.mock(StorageProvider.class);
        when(storageFactory.getProvider("local")).thenReturn(provider);
        when(taskQueueService.enqueueTask(anyString(), anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Queue error")));
        when(syncRepository.appendSyncHistory(eq("acme-corp"), eq("john-123"), any()))
            .thenReturn(Uni.createFrom().nullItem());
        
        // Setup tracing mocks
        when(tracingService.withClientSpan(anyString(), any(), any())).thenAnswer(invocation -> {
            return ((java.util.function.Supplier<?>) invocation.getArgument(1)).get();
        });
        doNothing().when(tracingService).addEvent(anyString(), any());

        // Act
        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "local", "/test/test-files");
        String result = taskIdUni.await().indefinitely();

        // Assert
        assertTrue(result.startsWith("Failed to queue task: "), "Should return failure message");
        verify(taskQueueService, times(1)).enqueueTask(anyString(), anyString());
        verify(syncRepository, never()).appendSyncHistory(anyString(), anyString(), any());
    }
}
