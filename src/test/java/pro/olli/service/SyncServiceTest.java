/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.service;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pro.olli.queue.TaskQueueService;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class SyncServiceTest {

    private SyncService syncService;
    private TaskQueueService taskQueueService;
    private StorageFactory storageFactory;
    private Firestore firestore;

    @BeforeEach
    void setUp() {
        taskQueueService = Mockito.mock(TaskQueueService.class);
        storageFactory = Mockito.mock(StorageFactory.class);
        firestore = Mockito.mock(Firestore.class);
        syncService = new SyncService(taskQueueService, storageFactory, firestore);
    }

    @Test
    void testSyncSuccess() throws Exception {
        // Arrange
        StorageProvider provider = Mockito.mock(StorageProvider.class);
        when(storageFactory.getProvider("local")).thenReturn(provider);
        when(taskQueueService.enqueueTask(anyString(), anyString()))
            .thenReturn(Uni.createFrom().nullItem());

        CompletableFuture<WriteResult> future = CompletableFuture.completedFuture(mock(WriteResult.class));
        when(firestore.collection("clients").document("acme-corp").collection("users").document("john-123")
            .update("syncHistory", FieldValue.arrayUnion(any()))).thenReturn((ApiFuture<WriteResult>) future);

        // Act
        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "local", "testfolder");
        String taskId = taskIdUni.await().indefinitely();

        // Assert
        assertEquals(36, taskId.length(), "Task ID should be a UUID"); // UUID length
        verify(taskQueueService, times(1)).enqueueTask(eq(taskId), anyString());
        verify(firestore.collection("clients").document("acme-corp").collection("users").document("john-123"), times(1))
            .update(eq("syncHistory"), any());
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
        verify(taskQueueService, never()).enqueueTask(anyString(), anyString());
        verify(firestore, never()).collection(anyString());
    }

    @Test
    void testSyncTaskQueueFailure() throws Exception {
        // Arrange
        StorageProvider provider = Mockito.mock(StorageProvider.class);
        when(storageFactory.getProvider("local")).thenReturn(provider);
        when(taskQueueService.enqueueTask(anyString(), anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Queue error")));

        CompletableFuture<WriteResult> future = CompletableFuture.completedFuture(mock(WriteResult.class));
        when(firestore.collection("clients").document("acme-corp").collection("users").document("john-123")
            .update("syncHistory", FieldValue.arrayUnion(any()))).thenReturn((ApiFuture<WriteResult>) future);

        // Act
        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "local", "testfolder");
        String result = taskIdUni.await().indefinitely();

        // Assert
        assertTrue(result.startsWith("Failed to queue task: "), "Should return failure message");
        verify(taskQueueService, times(1)).enqueueTask(anyString(), anyString());
        verify(firestore, never()).collection(anyString()); // History not updated on failure
    }
}
