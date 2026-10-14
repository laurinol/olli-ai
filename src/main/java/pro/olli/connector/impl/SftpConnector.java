/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.connector.impl;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import pro.olli.models.ConnectorCredentials;
import pro.olli.connector.Connector;

import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Vector;
import java.util.stream.Collectors;
import pro.olli.connector.ConnectorType;

/**
 * SFTP implementation of Connector with SSH key auth.
 */
@ApplicationScoped
public class SftpConnector implements Connector {

    private static final Logger LOG = Logger.getLogger(SftpConnector.class);

    private final JSch jsch;
    private ConnectorCredentials credentials;

    public SftpConnector() {
        this.jsch = new JSch();
        this.credentials = null;
    }

    @Override
    public Uni<Boolean> authenticate(ConnectorCredentials credentials) {
        return Uni.createFrom().emitter(emitter -> {
            Session session = null; // Declare outside try for cleanup
            try {
                this.credentials = credentials;
                String sshKeyBase64 = (String) credentials.get("sshKey");
                byte[] sshKeyBytes = Base64.getDecoder().decode(sshKeyBase64);
                jsch.addIdentity("client-key", sshKeyBytes, null, null);
                session = jsch.getSession((String) credentials.get("username"),
                    (String) credentials.get("host"), 22);
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect(5000); // Line ~45 - void return, no chaining
                emitter.complete(true);
            } catch (Exception e) {
                LOG.errorf("SFTP authentication failed: %s", e.getMessage());
                emitter.complete(false);
            } finally {
                if (session != null && session.isConnected()) {
                    session.disconnect(); // Cleanup separately
                }
            }
        });
    }

    @Override
    public Uni<List<String>> listFiles(String directory) {
        return Uni.createFrom().emitter(emitter -> {
            Session session = null;
            ChannelSftp sftp = null;
            try {
                session = jsch.getSession((String) credentials.get("username"),
                    (String) credentials.get("host"), 22);
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect();

                sftp = (ChannelSftp) session.openChannel("sftp");
                sftp.connect();
                sftp.cd(directory);

                Vector<ChannelSftp.LsEntry> files = sftp.ls("*");
                List<String> fileNames = files.stream()
                    .filter(entry -> !entry.getAttrs().isDir())
                    .map(ChannelSftp.LsEntry::getFilename)
                    .collect(Collectors.toList());

                emitter.complete(fileNames);
            } catch (Exception e) {
                LOG.errorf("Failed to list SFTP files: %s", e.getMessage());
                emitter.fail(e);
            } finally {
                if (sftp != null && sftp.isConnected()) {
                    sftp.disconnect();
                }
                if (session != null && session.isConnected()) {
                    session.disconnect();
                }
            }
        });
    }

    @Override
    public Uni<InputStream> downloadFile(String filePath) {
        return Uni.createFrom().emitter(emitter -> {
            Session session = null;
            ChannelSftp sftp = null;
            try {
                session = jsch.getSession((String) credentials.get("username"),
                    (String) credentials.get("host"), 22);
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect();

                sftp = (ChannelSftp) session.openChannel("sftp");
                sftp.connect();
                InputStream stream = sftp.get(filePath);

                emitter.complete(stream); // Caller must close
            } catch (Exception e) {
                LOG.errorf("Failed to download SFTP file %s: %s", filePath, e.getMessage());
                emitter.fail(e);
            } finally {
                if (sftp != null && sftp.isConnected()) {
                    sftp.disconnect();
                }
                if (session != null && session.isConnected()) {
                    session.disconnect();
                }
            }
        });
    }

    @Override
    public ConnectorType getProviderType() {
        return ConnectorType.SFTP;
    }
}
