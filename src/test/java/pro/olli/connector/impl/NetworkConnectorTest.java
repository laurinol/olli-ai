/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.connector.impl;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import java.util.HashMap;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import pro.olli.models.ConnectorCredentials;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for the NetworkConnector which connects to Samba/CIFS shares.
 * 
 * To run these tests, you need a Samba server running. You can start one with:
 * docker-compose up -d samba
 * 
 * Then run the tests with:
 * mvn test -Dtest=NetworkConnectorTest -Dsamba.host=localhost -Dsamba.share=share -Dsamba.username=testuser -Dsamba.password=testpass
 */
@QuarkusTest
class NetworkConnectorTest {

    @ConfigProperty(name = "samba.username", defaultValue = "testuser")
    String userName;

    @ConfigProperty(name = "samba.password", defaultValue = "testpass")
    String password;

    @ConfigProperty(name = "samba.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "samba.share", defaultValue = "share")
    String share;

    private NetworkConnector networkConnector;

    @BeforeEach
    void setUp() {
        networkConnector = new NetworkConnector();
        System.out.println("Testing NetworkConnector with: " + host + "/" + share + " (user: " + userName + ")");
    }

    /**
     * Test authentication with the Samba server.
     */
    @Test
    void testAuthenticateSuccess() {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("username", userName);
            put("password", password);
            put("host", host);
            put("share", share);
        }});

        Uni<Boolean> authUni = networkConnector.authenticate(creds);
        Boolean result = authUni.await().indefinitely();

        assertTrue(result, "Authentication with Samba server should succeed");
    }

    /**
     * Test listing files from the Samba share.
     */
    @Test
    void testListFiles() throws Exception {
        // Skip test if no Samba server is available - use the auth test as a check
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("username", userName);
            put("password", password);
            put("host", host);
            put("share", share);
        }});
        
        Boolean authResult = networkConnector.authenticate(creds).await().indefinitely();
        if (!authResult) {
            System.out.println("Skipping testListFiles because authentication failed - no Samba server available");
            return;
        }
        
        // Test with real Samba server instead of mocking
        Uni<List<String>> filesUni = networkConnector.listFiles("");
        List<String> files = filesUni.await().indefinitely();
        
        // Just verify we get some kind of list back - actual contents will depend on what's in the share
        System.out.println("Files found in share: " + files);
        assertTrue(files != null, "File list should not be null");
    }
    
    /**
     * Optional test for accessing a file from the Samba share.
     * Enabled only when explicitly requested with -Dtest.samba.download=true
     */
    @Test
    @EnabledIfSystemProperty(named = "test.samba.download", matches = "true")
    void testDownloadFile() throws Exception {
        // Authenticate first
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("username", userName);
            put("password", password);
            put("host", host);
            put("share", share);
        }});
        
        Boolean authResult = networkConnector.authenticate(creds).await().indefinitely();
        if (!authResult) {
            System.out.println("Skipping testDownloadFile because authentication failed");
            return;
        }
        
        // Try to download a file that should exist in the test-files directory
        String testFile = "meeting_notes.txt";
        var inputStreamUni = networkConnector.downloadFile(testFile);
        var inputStream = inputStreamUni.await().indefinitely();
        
        assertTrue(inputStream != null, "Downloaded file input stream should not be null");
        
        // Print first few bytes to verify it's working
        byte[] buffer = new byte[100];
        int bytesRead = inputStream.read(buffer);
        System.out.println("Downloaded " + bytesRead + " bytes from " + testFile);
        System.out.println("First bytes: " + new String(buffer, 0, Math.min(bytesRead, 50)) + "...");
        
        inputStream.close();
    }
}
