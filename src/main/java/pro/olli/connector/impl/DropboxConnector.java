/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.connector.impl;

import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import pro.olli.models.ConnectorCredentials;
import pro.olli.connector.Connector;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;
import pro.olli.connector.ConnectorType;

/**
 * Dropbox implementation of Connector.
 */
@ApplicationScoped
public class DropboxConnector implements Connector {

    private static final Logger LOG = Logger.getLogger(DropboxConnector.class);

    private final DbxClientV2 client;

    public DropboxConnector() {
        // Placeholder - real client set via authenticate
        this.client = new DbxClientV2(DbxRequestConfig.newBuilder("Document AI").build(), "placeholder");
    }

    @Override
    public Uni<Boolean> authenticate(ConnectorCredentials credentials) {
        try {
            String accessToken = (String) credentials.get("accessToken");
            DbxClientV2 testClient = new DbxClientV2(DbxRequestConfig.newBuilder("Document AI").build(), accessToken);
            testClient.files().listFolder("").getEntries(); // Test call
            return Uni.createFrom().item(true);
        } catch (Exception e) {
            LOG.errorf("Dropbox authentication failed: %s", e.getMessage());
            return Uni.createFrom().item(false);
        }
    }

    @Override
    public Uni<List<String>> listFiles(String directory) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                List<String> paths = client.files().listFolder(directory)
                    .getEntries()
                    .stream()
                    .map(entry -> entry.getPathLower())
                    .collect(Collectors.toList());
                emitter.complete(paths);
            } catch (Exception e) {
                LOG.errorf("Failed to list Dropbox files: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<InputStream> downloadFile(String filePath) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                InputStream stream = client.files().download(filePath).getInputStream();
                emitter.complete(stream);
            } catch (Exception e) {
                LOG.errorf("Failed to download Dropbox file %s: %s", filePath, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public ConnectorType getProviderType() {
        return ConnectorType.DROPBOX;
    }
}
