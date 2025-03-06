/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.service;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import com.google.cloud.tasks.v2.CloudTasksClient;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class SyncServiceTest {

    private SyncService syncService;
    private CloudTasksClient cloudTasksClient;
    private StorageFactory storageFactory;
    private Firestore firestore;

    @BeforeEach
    void setUp() {
        cloudTasksClient = Mockito.mock(CloudTasksClient.class);
        storageFactory = Mockito.mock(StorageFactory.class);
        firestore = Mockito.mock(Firestore.class);
        syncService = new SyncService(firestore,  storageFactory, cloudTasksClient);
        syncService.projectId = "olli-ai-2025"; // Mock config
    }

    @Test
    void testSyncSuccess() throws Exception {
        StorageProvider provider = Mockito.mock(StorageProvider.class);
        when(storageFactory.getProvider("local")).thenReturn(provider);

        when(cloudTasksClient.createTask("task", any())).thenReturn(null); // Mock success
        CompletableFuture<WriteResult> future = CompletableFuture.completedFuture(mock(WriteResult.class));
        when(firestore.collection("clients").document("acme-corp").collection("users").document("john-123")
            .update("syncHistory", FieldValue.arrayUnion(any()))).thenReturn((ApiFuture<WriteResult>) future);

        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "local", "testfolder");
        String taskId = taskIdUni.await().indefinitely();

        assertEquals(36, taskId.length()); // UUID length
        verify(cloudTasksClient, times(1)).createTask("task", any());
        verify(firestore.collection("clients").document("acme-corp").collection("users").document("john-123"), times(1))
            .update(eq("syncHistory"), any());
    }

    @Test
    void testSyncUnsupportedSource() {
        when(storageFactory.getProvider("invalid")).thenReturn(null);

        Uni<String> taskIdUni = syncService.sync("acme-corp", "john-123", "invalid", "testfolder");
        String result = taskIdUni.await().indefinitely();

        assertEquals("Unsupported source: invalid", result);
        verify(cloudTasksClient, never()).createTask("task", any());
    }
}
