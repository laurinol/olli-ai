package pro.olli.storage.impl;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import pro.olli.model.StorageCredentials;
import pro.olli.storage.StorageProvider;
import pro.olli.storage.StorageProviderType;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class LocalProvider implements StorageProvider {

    private static final Logger LOG = Logger.getLogger(LocalProvider.class);

    private String basePath;

    @Override
    public Uni<Boolean> authenticate(StorageCredentials credentials) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                this.basePath = (String) credentials.get("basePath");
                if (this.basePath == null) {
                    this.basePath = "/tmp/testfolder"; // Default for testing
                }
                Path path = Paths.get(basePath);
                if (Files.exists(path) && Files.isDirectory(path)) {
                    LOG.infof("Local path %s authenticated successfully", basePath);
                    emitter.complete(true);
                } else {
                    LOG.errorf("Local path %s does not exist or isn’t a directory", basePath);
                    emitter.complete(false);
                }
            } catch (Exception e) {
                LOG.errorf("Local authentication failed: %s", e.getMessage());
                emitter.complete(false);
            }
        });
    }

    @Override
    public Uni<List<String>> listFiles(String directory) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String path = directory != null ? basePath + "/" + directory : basePath;
                List<String> files = Files.list(Paths.get(path))
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
                emitter.complete(files);
            } catch (Exception e) {
                LOG.errorf("Failed to list local files: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<InputStream> downloadFile(String filePath) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                Path path = Paths.get(basePath, filePath);
                emitter.complete(new FileInputStream(path.toFile()));
            } catch (Exception e) {
                LOG.errorf("Failed to download local file %s: %s", filePath, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public StorageProviderType getProviderType() {
        return StorageProviderType.LOCAL;
    }
}
