/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.connector;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import pro.olli.connector.impl.DropboxConnector;
import pro.olli.connector.impl.GoogleDriveConnector;
//import pro.olli.connector.impl.OneDriveProvider;
import pro.olli.connector.impl.LocalConnector;
import pro.olli.connector.impl.NetworkConnector;
import pro.olli.connector.impl.S3Connector;
import pro.olli.connector.impl.SftpConnector;

import java.util.EnumMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Factory for creating ConnectorProvider instances based on source type.
 */
@ApplicationScoped
public class ConnectorFactory {

    private static final Logger LOG = Logger.getLogger(ConnectorFactory.class);

    private final GoogleDriveConnector googleDriveConnector;
    private final DropboxConnector dropboxConnector;
    //private final OneDriveProvider oneDriveProvider;
    private final S3Connector s3Connector;
    private final SftpConnector sftpConnector;
    private final NetworkConnector networkConnector;  // New
    private final LocalConnector localConnector;      // New

    private final EnumMap<ConnectorType, Connector> providers;

    public ConnectorFactory(GoogleDriveConnector googleDriveConnector,
        DropboxConnector dropboxConnector,
        //OneDriveProvider oneDriveProvider,
        S3Connector s3Connector,
        SftpConnector sftpConnector,
        NetworkConnector networkConnector,
        LocalConnector localConnector) {
        this.googleDriveConnector = googleDriveConnector;
        this.dropboxConnector = dropboxConnector;
        //this.oneDriveProvider = oneDriveProvider;
        this.s3Connector = s3Connector;
        this.sftpConnector = sftpConnector;
        this.networkConnector = networkConnector;
        this.localConnector = localConnector;
        this.providers = new EnumMap<>(ConnectorType.class);
        init();
    }

    private void init() {
        providers.put(ConnectorType.GOOGLE, googleDriveConnector);
        providers.put(ConnectorType.DROPBOX, dropboxConnector);
        //providers.put(ConnectorType.ONEDRIVE, oneDriveProvider);
        providers.put(ConnectorType.S3, s3Connector);
        providers.put(ConnectorType.SFTP, sftpConnector);
        providers.put(ConnectorType.NETWORK, networkConnector);
        providers.put(ConnectorType.LOCAL, localConnector);
        LOG.info("ConnectorFactory initialized with providers: " + providers.keySet());
    }

    public Connector getProvider(String source) {
        ConnectorType type = ConnectorType.fromString(source);
        if (type == null) {
            LOG.warnf("No provider found for source: %s", source);
            return null;
        }
        return providers.get(type);
    }

    public List<String> getSupportedProviders() {
        return providers.keySet().stream()
            .map(ConnectorType::getValue)
            .collect(Collectors.toList());
    }
}
