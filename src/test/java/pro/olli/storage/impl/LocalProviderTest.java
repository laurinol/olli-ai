package pro.olli.storage.impl;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.olli.models.StorageCredentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LocalProviderTest {

    private LocalProvider localProvider;
    private String testDir;

    @BeforeEach
    void setUp() {
        localProvider = new LocalProvider();
        testDir = Paths.get("./test/").toAbsolutePath().toString();
    }

    @Test
    void testAuthenticateSuccess() {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir);
        }});

        Uni<Boolean> authUni = localProvider.authenticate(creds);
        Boolean result = authUni.await().indefinitely();

        assertTrue(result, "Authentication should succeed with valid directory");
    }

    @Test
    void testAuthenticateFailureInvalidPath() {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("basePath", "/non/existent/path");
        }});

        Uni<Boolean> authUni = localProvider.authenticate(creds);
        Boolean result = authUni.await().indefinitely();

        assertFalse(result, "Authentication should fail with invalid directory");
    }

    @Test
    void testListFilesSuccess() {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir);
        }});

        localProvider.authenticate(creds).await().indefinitely();
        Uni<List<String>> filesUni = localProvider.listFiles("");
        List<String> files = filesUni.await().indefinitely();

        assertNotNull(files, "File list should not be null");
        assertTrue(files.contains("contract_acme.pdf"), "Should list contract_acme.pdf");
        assertTrue(files.contains("customer_list.csv"), "Should list customer_list.csv");
        assertEquals(6, files.size(), "Should list exactly 6 files");
    }

    @Test
    void testListFilesEmptyDirectory() {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir + "/empty"); // Assuming empty subdir exists or adjust
        }});

        localProvider.authenticate(creds).await().indefinitely();
        Uni<List<String>> filesUni = localProvider.listFiles("");
        List<String> files = filesUni.await().indefinitely();

        assertNotNull(files, "File list should not be null");
        assertTrue(files.isEmpty(), "Should return empty list for empty directory");
    }

    @Test
    void testDownloadFileSuccess() throws Exception {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir);
        }});

        localProvider.authenticate(creds).await().indefinitely();
        Uni<InputStream> streamUni = localProvider.downloadFile("meeting_notes.txt");
        InputStream stream = streamUni.await().indefinitely();

        assertNotNull(stream, "InputStream should not be null");
        byte[] content = stream.readAllBytes();
        assertEquals("Meeting Notes - Project Kickoff", new String(content).substring(0, 31), "File content should match");
        stream.close();
    }

    @Test
    void testDownloadFileNotFound() {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("basePath", testDir);
        }});

        localProvider.authenticate(creds).await().indefinitely();
        Uni<InputStream> streamUni = localProvider.downloadFile("nonexistent.txt");

        Exception exception = assertThrows(Exception.class, () -> streamUni.await().indefinitely());
        assertTrue(exception.getMessage().contains("No such file or directory"), "Should throw file not found exception");
    }
}
