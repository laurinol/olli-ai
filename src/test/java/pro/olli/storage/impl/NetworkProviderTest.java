/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.storage.impl;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import java.util.HashMap;
import jcifs.smb.SmbFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.olli.model.StorageCredentials;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@QuarkusTest
class NetworkProviderTest {

    private NetworkProvider networkProvider;
    private SmbFile mockSmbFile;

    @BeforeEach
    void setUp() throws Exception {
        networkProvider = new NetworkProvider();
        mockSmbFile = mock(SmbFile.class);

        when(mockSmbFile.listFiles()).thenReturn(new SmbFile[]{mock(SmbFile.class)});
        when(mockSmbFile.isFile()).thenReturn(true);
        when(mockSmbFile.getName()).thenReturn("test.txt");
    }

    @Test
    void testAuthenticateSuccess() {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("username", "guest");
            put("password", "");
            put("host", "192.168.1.100");
            put("share", "public");
        }});

        Uni<Boolean> authUni = networkProvider.authenticate(creds);
        Boolean result = authUni.await().indefinitely();

        assertTrue(result);
    }

    @Test
    void testListFiles() throws Exception {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("username", "guest");
            put("password", "");
            put("host", "127.08.0.1");
            put("share", "public");
        }});
        networkProvider.authenticate(creds).await().indefinitely();

        try (var mockedStatic = mockStatic(SmbFile.class)) {
            mockedStatic.when(() -> new SmbFile(anyString(), any())).thenReturn(mockSmbFile);

            Uni<List<String>> filesUni = networkProvider.listFiles("testfolder");
            List<String> files = filesUni.await().indefinitely();

            assertEquals(1, files.size());
            assertEquals("test.txt", files.get(0));
        }
    }
}
