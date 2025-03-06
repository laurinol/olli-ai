package pro.olli.storage;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import pro.olli.storage.impl.DropboxProvider;
import pro.olli.storage.impl.GoogleDriveProvider;
//import pro.olli.storage.impl.OneDriveProvider;
import pro.olli.storage.impl.LocalProvider;
import pro.olli.storage.impl.NetworkProvider;
import pro.olli.storage.impl.S3Provider;
import pro.olli.storage.impl.SftpProvider;

import java.util.EnumMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Factory for creating StorageProvider instances based on source type.
 */
// pro.olli.storage/StorageFactory.java
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    private final GoogleDriveProvider googleDriveProvider;
    private final DropboxProvider dropboxProvider;
    //private final OneDriveProvider oneDriveProvider;
    private final S3Provider s3Provider;
    private final SftpProvider sftpProvider;
    private final NetworkProvider networkProvider;  // New
    private final LocalProvider localProvider;      // New

    private final EnumMap<StorageProviderType, StorageProvider> providers;

    public StorageFactory(GoogleDriveProvider googleDriveProvider,
        DropboxProvider dropboxProvider,
        //OneDriveProvider oneDriveProvider,
        S3Provider s3Provider,
        SftpProvider sftpProvider,
        NetworkProvider networkProvider,
        LocalProvider localProvider) {
        this.googleDriveProvider = googleDriveProvider;
        this.dropboxProvider = dropboxProvider;
        //this.oneDriveProvider = oneDriveProvider;
        this.s3Provider = s3Provider;
        this.sftpProvider = sftpProvider;
        this.networkProvider = networkProvider;
        this.localProvider = localProvider;
        this.providers = new EnumMap<>(StorageProviderType.class);
        init();
    }

    private void init() {
        providers.put(StorageProviderType.GOOGLE, googleDriveProvider);
        providers.put(StorageProviderType.DROPBOX, dropboxProvider);
        //providers.put(StorageProviderType.ONEDRIVE, oneDriveProvider);
        providers.put(StorageProviderType.S3, s3Provider);
        providers.put(StorageProviderType.SFTP, sftpProvider);
        providers.put(StorageProviderType.NETWORK, networkProvider);
        providers.put(StorageProviderType.LOCAL, localProvider);
        LOG.info("StorageFactory initialized with providers: " + providers.keySet());
    }

    public StorageProvider getProvider(String source) {
        StorageProviderType type = StorageProviderType.fromString(source);
        if (type == null) {
            LOG.warnf("No provider found for source: %s", source);
            return null;
        }
        return providers.get(type);
    }

    public List<String> getSupportedProviders() {
        return providers.keySet().stream()
            .map(StorageProviderType::getValue)
            .collect(Collectors.toList());
    }
}
