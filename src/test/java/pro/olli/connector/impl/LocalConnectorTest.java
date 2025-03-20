package pro.olli.connector.impl;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.olli.models.ConnectorCredentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LocalConnectorTest {

    private LocalConnector localConnector;
    private String testDir;

    @BeforeEach
    void setUp() {
        localConnector = new LocalConnector();
        testDir = Paths.get("./test/").toAbsolutePath().toString();
    }

    @Test
    void testAuthenticateSuccess() {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir);
        }});

        Uni<Boolean> authUni = localConnector.authenticate(creds);
        Boolean result = authUni.await().indefinitely();

        assertTrue(result, "Authentication should succeed with valid directory");
    }

    @Test
    void testAuthenticateFailureInvalidPath() {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("basePath", "/non/existent/path");
        }});

        Uni<Boolean> authUni = localConnector.authenticate(creds);
        Boolean result = authUni.await().indefinitely();

        assertFalse(result, "Authentication should fail with invalid directory");
    }

    @Test
    void testListFilesSuccess() {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir);
        }});

        localConnector.authenticate(creds).await().indefinitely();
        Uni<List<String>> filesUni = localConnector.listFiles("");
        List<String> files = filesUni.await().indefinitely();

        assertNotNull(files, "File list should not be null");
        assertTrue(files.contains("contract_acme.pdf"), "Should list contract_acme.pdf");
        assertTrue(files.contains("customer_list.csv"), "Should list customer_list.csv");
        assertEquals(6, files.size(), "Should list exactly 6 files");
    }

    @Test
    void testListFilesEmptyDirectory() {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir + "/empty"); // Assuming empty subdir exists or adjust
        }});

        localConnector.authenticate(creds).await().indefinitely();
        Uni<List<String>> filesUni = localConnector.listFiles("");
        List<String> files = filesUni.await().indefinitely();

        assertNotNull(files, "File list should not be null");
        assertTrue(files.isEmpty(), "Should return empty list for empty directory");
    }

    @Test
    void testDownloadFileSuccess() throws Exception {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir);
        }});

        localConnector.authenticate(creds).await().indefinitely();
        Uni<InputStream> streamUni = localConnector.downloadFile("meeting_notes.txt");
        InputStream stream = streamUni.await().indefinitely();

        assertNotNull(stream, "InputStream should not be null");
        byte[] content = stream.readAllBytes();
        assertEquals("Meeting Notes - Project Kickoff", new String(content).substring(0, 31), "File content should match");
        stream.close();
    }

    @Test
    void testDownloadFileNotFound() {
        ConnectorCredentials creds = new ConnectorCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir);
        }});

        localConnector.authenticate(creds).await().indefinitely();
        Uni<InputStream> streamUni = localConnector.downloadFile("nonexistent.txt");

        Exception exception = assertThrows(Exception.class, () -> streamUni.await().indefinitely());
        assertTrue(exception.getMessage().contains("No such file or directory"), "Should throw file not found exception");
    }
}
