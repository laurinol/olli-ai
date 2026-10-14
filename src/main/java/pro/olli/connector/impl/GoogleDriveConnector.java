/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.connector.impl;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
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
 * Google Drive implementation of Connector.
 */
@ApplicationScoped
public class GoogleDriveConnector implements Connector {

    private static final Logger LOG = Logger.getLogger(GoogleDriveConnector.class);

    private Drive driveService;

    public GoogleDriveConnector() {
        this.driveService = null;
    }

    @Override
    public Uni<Boolean> authenticate(ConnectorCredentials credentials) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Fetch access token from ConnectorCredentials or .env for local dev
                String accessToken = (String) credentials.get("accessToken");
                if (accessToken == null) {
                    accessToken = System.getenv("GOOGLE_ACCESS_TOKEN"); // Local dev fallback
                    if (accessToken == null) {
                        emitter.fail(new IllegalArgumentException("No Google access token provided"));
                        return;
                    }
                }

                // Build GoogleCredentials with access token
                GoogleCredentials googleCreds = GoogleCredentials.create(new AccessToken(accessToken, null));
                HttpCredentialsAdapter initializer = new HttpCredentialsAdapter(googleCreds);
                this.driveService = new Drive.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    initializer)
                    .setApplicationName("Document AI")
                    .build();

                // Test authentication with a minimal API call
                driveService.files().list().setPageSize(1).execute();
                LOG.info("Google Drive authenticated successfully");
                emitter.complete(true);
            } catch (Exception e) {
                LOG.errorf("Google Drive authentication failed: %s", e.getMessage());
                emitter.complete(false);
            }
        });
    }

    @Override
    public Uni<List<String>> listFiles(String directory) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Use folderId from shared URL (e.g., 1aBcDeFgHiJkLmNopQrStUvWxYz)
                String folderId = directory != null ? directory : System.getenv("GOOGLE_TEST_FOLDER_ID"); // Local dev fallback
                if (folderId == null) {
                    emitter.fail(new IllegalArgumentException("No folder ID provided"));
                    return;
                }
                List<com.google.api.services.drive.model.File> files = driveService.files().list()
                    .setQ("'" + folderId + "' in parents")
                    .setFields("files(id)") // Optimize: only fetch IDs
                    .execute()
                    .getFiles();
                List<String> fileIds = files.stream()
                    .map(com.google.api.services.drive.model.File::getId)
                    .collect(Collectors.toList());
                emitter.complete(fileIds);
            } catch (Exception e) {
                LOG.errorf("Failed to list Google Drive files: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<InputStream> downloadFile(String filePath) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                InputStream stream = driveService.files().get(filePath)
                    .executeMediaAsInputStream();
                emitter.complete(stream);
            } catch (Exception e) {
                LOG.errorf("Failed to download Google Drive file %s: %s", filePath, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public ConnectorType getProviderType() {
        return ConnectorType.GOOGLE;
    }
}
