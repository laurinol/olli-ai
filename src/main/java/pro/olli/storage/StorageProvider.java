/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.storage;

import io.smallrye.mutiny.Uni;
import pro.olli.models.StorageCredentials;

import java.io.InputStream;
import java.util.List;

/**
 * Interface defining the contract for interacting with storage providers.
 * Implementations (e.g., SftpProvider, GoogleDriveProvider) handle provider-specific logic.
 */
public interface StorageProvider {

    /**
     * Authenticates with the storage provider using the provided credentials.
     * @param credentials The credentials (e.g., OAuth token, SSH key) for authentication.
     * @return Uni<Boolean> indicating success (true) or failure (false) of authentication.
     */
    Uni<Boolean> authenticate(StorageCredentials credentials);

    /**
     * Lists files available in the specified directory or prefix.
     * @param directory The directory or prefix to list (e.g., "/path/to/files" for SFTP, folder ID for Drive).
     * @return Uni<List<String>> containing file paths or identifiers.
     */
    Uni<List<String>> listFiles(String directory);

    /**
     * Downloads a specific file from the storage provider.
     * @param filePath The path or identifier of the file to download.
     * @return Uni<InputStream> providing the file content as a stream for processing (e.g., with Tika).
     */
    Uni<InputStream> downloadFile(String filePath);

    /**
     * Returns the provider's unique identifier.
     * @return String representing the provider type.
     */
    StorageProviderType getProviderType();
}
