/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.storage.impl;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import org.jboss.logging.Logger;
import pro.olli.models.StorageCredentials;
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

    public LocalProvider() {
        // Default to project root for testing if no credentials are provided
        this.basePath = Paths.get("test/").toAbsolutePath().toString();
        LOG.infof("LocalProvider initialized with default base path: %s", this.basePath);
    }

    @Override
    public Uni<Boolean> authenticate(StorageCredentials credentials) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                if (credentials != null && credentials.get("basePath") != null) {
                    this.basePath = (String) credentials.get("basePath");
                    LOG.infof("Using provided basePath: %s", this.basePath);
                } else {
                    // Keep using the default set in the constructor
                    LOG.infof("No basePath provided in credentials, keeping default: %s", basePath);
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
                // Handle path construction properly
                Path dirPath;
                if (directory == null || directory.isEmpty()) {
                    dirPath = Paths.get(basePath);
                } else if (directory.startsWith("/")) {
                    // If directory starts with /, treat it as a relative path from base
                    String relativePath = directory.substring(1); // Remove leading /
                    dirPath = Paths.get(basePath, relativePath);
                } else {
                    // Normal case
                    dirPath = Paths.get(basePath, directory);
                }

                LOG.infof("Listing files in directory: %s", dirPath);

                List<String> files = Files.list(dirPath)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return !Files.isHidden(p);  // Exclude hidden files
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());

                LOG.infof("Found %d files in %s", files.size(), dirPath);
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
                Path path;
                if (filePath.startsWith("/")) {
                    // If filePath starts with /, treat it as a relative path from base
                    String relativePath = filePath.substring(1); // Remove leading /
                    path = Paths.get(basePath, relativePath);
                } else {
                    path = Paths.get(basePath, filePath);
                }

                LOG.infof("Downloading file: %s", path);
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
