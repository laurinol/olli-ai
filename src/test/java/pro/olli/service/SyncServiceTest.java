/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service;

import com.google.gson.Gson;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.SyncRepository;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@QuarkusTest
class SyncServiceTest {

    private SyncService syncService;
    private TaskQueueService taskQueueService;
    private StorageFactory storageFactory;
    private SyncRepository syncRepository;
    private Gson gson;
    private Emitter<String> taskEmitter;

    @BeforeEach
    void setUp() {
        taskQueueService = Mockito.mock(TaskQueueService.class);
        storageFactory = Mockito.mock(StorageFactory.class);
        syncRepository = Mockito.mock(SyncRepository.class);
        gson = Mockito.mock(Gson.class);
        String projectId = "test-project";
        String location = "us-test1";
        taskEmitter = Mockito.mock(Emitter.class);
        when(taskEmitter.send(anyString()))
            .thenReturn(CompletableFuture.completedFuture(null)); // Return Void
        syncService = new SyncService(taskQueueService, storageFactory, syncRepository, gson, projectId, location, taskEmitter);
    }

    @Test
    void testSyncSuccess() {
        // Arrange
        StorageProvider provider = Mockito.mock(StorageProvider.class);
        when(storageFactory.getProvider("local")).thenReturn(provider);
        when(syncRepository.appendSyncHistory(eq("acme-corp"), eq("john-123"), any()))
            .thenReturn(Uni.createFrom().nullItem());
        when(gson.toJson(any())).thenReturn("mocked-json-payload");

        // Act
        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "local", "testfolder");
        String taskId = taskIdUni.await().indefinitely();

        // Assert
        assertEquals(36, taskId.length(), "Task ID should be a UUID");
        verify(taskEmitter, times(1)).send(anyString());
        verify(syncRepository, times(1)).appendSyncHistory(eq("acme-corp"), eq("john-123"), any());
        verify(gson, times(1)).toJson(any());
    }

    @Test
    void testSyncUnsupportedSource() {
        // Arrange
        when(storageFactory.getProvider("invalid")).thenReturn(null);

        // Act
        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "invalid", "testfolder");
        String result = taskIdUni.await().indefinitely();

        // Assert
        assertEquals("Unsupported source: invalid", result, "Should return unsupported source message");
        verify(taskEmitter, never()).send(anyString());
        verify(syncRepository, never()).appendSyncHistory(anyString(), anyString(), any());
        verify(gson, never()).toJson(any());
    }

    @Test
    void testSyncTaskQueueFailure() {
        // Arrange
        StorageProvider provider = Mockito.mock(StorageProvider.class);
        when(storageFactory.getProvider("local")).thenReturn(provider);
        when(taskEmitter.send(anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Queue error")));
        when(syncRepository.appendSyncHistory(eq("acme-corp"), eq("john-123"), any()))
            .thenReturn(Uni.createFrom().nullItem());
        when(gson.toJson(any())).thenReturn("mocked-json-payload");

        // Act
        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "local", "testfolder");
        String result = taskIdUni.await().indefinitely();

        // Assert
        assertTrue(result.startsWith("Failed to queue task: "), "Should return failure message");
        verify(taskEmitter, times(1)).send(anyString());
        verify(syncRepository, never()).appendSyncHistory(anyString(), anyString(), any());
        verify(gson, times(1)).toJson(any());
    }
}
