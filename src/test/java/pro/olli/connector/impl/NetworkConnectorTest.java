/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.connector.impl;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import java.util.HashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jcifs.smb.SmbFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.olli.models.ConnectorCredentials;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@QuarkusTest
class NetworkConnectorTest {

    @ConfigProperty(name = "samba.username", defaultValue = "testuser")
    String userName;

    @ConfigProperty(name = "samba.password", defaultValue = "")
    String password;

    @ConfigProperty(name = "samba.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "samba.share", defaultValue = "public")
    String share;

    private NetworkConnector networkConnector;
    private SmbFile mockSmbFile;

    @BeforeEach
    void setUp() throws Exception {
        networkConnector = new NetworkConnector();
        mockSmbFile = mock(SmbFile.class);

        when(mockSmbFile.listFiles()).thenReturn(new SmbFile[]{mock(SmbFile.class)});
        when(mockSmbFile.isFile()).thenReturn(true);
        when(mockSmbFile.getName()).thenReturn("test.txt");
    }

    @Test
    void testAuthenticateSuccess() {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("username", userName);
            put("password", password);
            put("host", host);
            put("share",share);
        }});

        Uni<Boolean> authUni = networkConnector.authenticate(creds);
        Boolean result = authUni.await().indefinitely();

        assertTrue(result);
    }

    @Test
    void testListFiles() throws Exception {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("username", "guest");
            put("password", "");
            put("host", "127.0.0.1");
            put("share", "public");
        }});
        networkConnector.authenticate(creds).await().indefinitely();

        try (var mockedStatic = mockStatic(SmbFile.class)) {
            mockedStatic.when(() -> new SmbFile(anyString(), any())).thenReturn(mockSmbFile);

            Uni<List<String>> filesUni = networkConnector.listFiles("testfolder");
            List<String> files = filesUni.await().indefinitely();

            assertEquals(1, files.size());
            assertEquals("test.txt", files.get(0));
        }
    }
}
