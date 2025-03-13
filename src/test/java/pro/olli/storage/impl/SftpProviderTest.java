/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.storage.impl;

import com.jcraft.jsch.*;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import java.util.HashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pro.olli.models.StorageCredentials;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class SftpProviderTest {

    @ConfigProperty(name = "sftp.username", defaultValue = "testuser")
    String userName;

    @ConfigProperty(name = "sftp.password", defaultValue = "")
    String password;

    @ConfigProperty(name = "sftp.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "sftp.port", defaultValue = "2222")
    String port;

    @ConfigProperty(name = "sftp.path", defaultValue = "/config/test-files")
    String path;

    private SftpProvider sftpProvider;
    private JSch mockJSch;
    private Session mockSession;
    private ChannelSftp mockChannel;

    @BeforeEach
    void setUp() throws JSchException {
        sftpProvider = new SftpProvider();

        // Mock JSch and its dependencies
        mockJSch = mock(JSch.class);
        mockSession = mock(Session.class);
        mockChannel = mock(ChannelSftp.class);

        // Stub JSch behavior
        when(mockJSch.getSession("testuser", "localhost", 2222)).thenReturn(mockSession);
        when(mockSession.openChannel("sftp")).thenReturn(mockChannel);
    }

    @Test
    void testAuthenticateSuccess() throws JSchException {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("username", userName);
            put("password", password);
            put("host", host);
            put("port", port);
        }});

        // Mock successful connection
        doNothing().when(mockSession).setPassword(password);
        doNothing().when(mockSession).connect();
        doNothing().when(mockChannel).connect();

        try (var mockedStatic = Mockito.mockStatic(JSch.class)) {
            mockedStatic.when(JSch::new).thenReturn(mockJSch);

            Uni<Boolean> authUni = sftpProvider.authenticate(creds);
            Boolean result = authUni.await().indefinitely();

            assertTrue(result);
            verify(mockSession, times(1)).connect();
            verify(mockChannel, times(1)).connect();
        }
    }

    @Test
    void testAuthenticateFailure() throws JSchException {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("username", userName);
            put("password", "incorrect_password");
            put("host", host);
            put("port", port);
        }});

        // Simulate connection failure
        doThrow(new JSchException("Connection failed")).when(mockSession).connect();

        try (var mockedStatic = Mockito.mockStatic(JSch.class)) {
            mockedStatic.when(JSch::new).thenReturn(mockJSch);

            Uni<Boolean> authUni = sftpProvider.authenticate(creds);
            Boolean result = authUni.await().indefinitely();

            assertFalse(result);
            verify(mockSession, times(1)).connect();
            verify(mockChannel, never()).connect();
        }
    }

    @Test
    void testListFilesSuccess() throws Exception {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("username", userName);
            put("password", password);
            put("host", host);
            put("port", port);
        }});

        // Mock authentication
        doNothing().when(mockSession).setPassword(password);
        doNothing().when(mockSession).connect();
        doNothing().when(mockChannel).connect();

        // Mock ls command
        Vector<ChannelSftp.LsEntry> mockFiles = new Vector<>();
        ChannelSftp.LsEntry mockEntry = mock(ChannelSftp.LsEntry.class);
        SftpATTRS mockAttrs = mock(SftpATTRS.class);
        when(mockEntry.getAttrs()).thenReturn(mockAttrs);
        when(mockAttrs.isDir()).thenReturn(false);
        when(mockEntry.getFilename()).thenReturn("test.txt");
        mockFiles.add(mockEntry);
        when(mockChannel.ls(path)).thenReturn(mockFiles);

        try (var mockedStatic = Mockito.mockStatic(JSch.class)) {
            mockedStatic.when(JSch::new).thenReturn(mockJSch);

            sftpProvider.authenticate(creds).await().indefinitely();
            Uni<List<String>> filesUni = sftpProvider.listFiles(path);
            List<String> files = filesUni.await().indefinitely();

            assertEquals(1, files.size());
            assertEquals("test.txt", files.get(0));
            verify(mockChannel, times(1)).ls(path);
        }
    }

    @Test
    void testDownloadFileSuccess() throws Exception {
        StorageCredentials creds = new StorageCredentials(new HashMap<String, Object>() {{
            put("username", userName);
            put("password", password);
            put("host", host);
            put("port", port);
        }});

        // Mock authentication
        doNothing().when(mockSession).setPassword(password);
        doNothing().when(mockSession).connect();
        doNothing().when(mockChannel).connect();

        // Mock file download
        InputStream mockStream = new ByteArrayInputStream("test content".getBytes());
        when(mockChannel.get("/testfolder/test.txt")).thenReturn(mockStream);

        try (var mockedStatic = Mockito.mockStatic(JSch.class)) {
            mockedStatic.when(JSch::new).thenReturn(mockJSch);

            sftpProvider.authenticate(creds).await().indefinitely();
            Uni<InputStream> streamUni = sftpProvider.downloadFile("/testfolder/test.txt");
            InputStream stream = streamUni.await().indefinitely();

            assertNotNull(stream);
            assertEquals(mockStream, stream);
            verify(mockChannel, times(1)).get("/testfolder/test.txt");
        }
    }
}
