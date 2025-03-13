/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.storage.impl;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.MalformedURLException;
import jcifs.CIFSContext;
import jcifs.Configuration;
import jcifs.config.BaseConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import org.jboss.logging.Logger;
import pro.olli.models.StorageCredentials;
import pro.olli.storage.StorageProvider;
import pro.olli.storage.StorageProviderType;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class NetworkProvider implements StorageProvider {

    private static final Logger LOG = Logger.getLogger(NetworkProvider.class);

    private CIFSContext context;
    private String basePath;

    @Override
    public Uni<Boolean> authenticate(StorageCredentials credentials) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String username = (String) credentials.get("username");
                String password = (String) credentials.get("password");
                String host = (String) credentials.get("host");
                String share = (String) credentials.get("share");

                if (username == null || password == null || host == null || share == null) {
                    LOG.error("Missing Samba credentials: username, password, host, or share");
                    emitter.complete(false);
                    return;
                }

                NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(username, password);
                Configuration config = new BaseConfiguration(true);
                this.context = new BaseContext(config).withCredentials(auth);
                this.basePath = String.format("smb://%s/%s/", host, share);
                try (SmbFile root = new SmbFile(basePath, context)) {
                    root.listFiles(); // Test connection
                }  // Updated constructor
                LOG.info("Samba authenticated successfully");
                emitter.complete(true);
            } catch (Exception e) {
                LOG.errorf("Samba authentication failed: %s", e.getMessage());
                emitter.complete(false);
            }
        });
    }

    @Override
    public Uni<List<String>> listFiles(String directory) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String path = basePath + (directory != null ? directory + "/" : "");
                SmbFile dir = new SmbFile(path, context);
                SmbFile[] smbFiles = dir.listFiles();
                List<String> files = Arrays.stream(smbFiles)
                    .filter(file -> {
                        try {
                            return file.isFile(); // Explicit catch
                        } catch (SmbException e) {
                            LOG.warnf("Failed to check if %s is a file: %s", file.getPath(), e.getMessage());
                            return false; // Treat as non-file on error
                        }
                    })
                    .map(SmbFile::getName)
                    .collect(Collectors.toList());
                emitter.complete(files);
            } catch (SmbException e) {
                LOG.errorf("Failed to list Samba files: %s", e.getMessage());
                emitter.fail(e);
            } catch (MalformedURLException e) {
                LOG.errorf("Malformed URL: %s", directory);
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<InputStream> downloadFile(String filePath) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                SmbFile file = new SmbFile(basePath + filePath, context);
                emitter.complete(new SmbFileInputStream(file));
            } catch (SmbException e) {
                LOG.errorf("Failed to download Samba file %s: %s", filePath, e.getMessage());
                emitter.fail(e);
            } catch (MalformedURLException e) {
                LOG.errorf("Malformed URL: %s", filePath);
                emitter.fail(e);
            }
        });
    }

    @Override
    public StorageProviderType getProviderType() {
        return StorageProviderType.NETWORK;
    }
}
