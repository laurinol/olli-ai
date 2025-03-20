/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.connector.impl;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.ConnectorCredentials;
import pro.olli.connector.Connector;
import pro.olli.connector.ConnectorType;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AWS S3 implementation of Connector.
 */
@ApplicationScoped
public class S3Connector implements Connector {

    private static final Logger LOG = Logger.getLogger(S3Connector.class);

    private S3Client s3Client;
    private ConnectorCredentials credentials; // Store creds for use in methods

    public S3Connector() {
        // Placeholder - real client set via authenticate
        this.s3Client = null;
        this.credentials = null;
    }

    @Override
    @Retry(maxRetries = 3,
        delay = 1000,
        jitter = 200,
        maxDuration = 10000,
        retryOn = Exception.class,
        abortOn = {AppException.class})
    @Timeout(value = 10, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 30000,
        successThreshold = 2)
    @CircuitBreakerName("s3-auth")
    public Uni<Boolean> authenticate(ConnectorCredentials credentials) {
        if (credentials == null) {
            LOG.error("S3 credentials cannot be null");
            return Uni.createFrom().item(false);
        }

        String accessKey = (String) credentials.get("accessKey");
        String secretKey = (String) credentials.get("secretKey");
        String bucket = (String) credentials.get("bucket");

        if (accessKey == null || accessKey.isBlank() ||
            secretKey == null || secretKey.isBlank() ||
            bucket == null || bucket.isBlank()) {
            LOG.errorf("S3 missing required credentials: accessKey=%s, secretKey=%s, bucket=%s",
                accessKey != null, secretKey != null, bucket);
            return Uni.createFrom().item(false);
        }

        LOG.infof("Authenticating to S3 with bucket: %s", bucket);

        return Uni.createFrom().emitter(emitter -> {
            try {
                this.credentials = credentials; // Store for later use
                this.s3Client = S3Client.builder()
                    .credentialsProvider(() -> AwsBasicCredentials.create(accessKey, secretKey))
                    .region(Region.US_EAST_1) // Configurable via creds if needed
                    .build();

                // Test authentication
                s3Client.listObjectsV2(builder -> builder.bucket(bucket).maxKeys(1));
                LOG.info("S3 authentication successful");
                emitter.complete(true);
            } catch (Exception e) {
                LOG.errorf("S3 authentication failed: %s", e.getMessage());
                this.s3Client = null;
                this.credentials = null;
                emitter.complete(false);
            }
        });
    }

    @Override
    @Retry(maxRetries = 3,
        delay = 1000,
        jitter = 200,
        maxDuration = 10000,
        retryOn = Exception.class,
        abortOn = {AppException.class})
    @Timeout(value = 15, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 30000,
        successThreshold = 2)
    @CircuitBreakerName("s3-list")
    public Uni<List<String>> listFiles(String directory) {
        if (s3Client == null || credentials == null) {
            LOG.error("S3 client not authenticated. Please call authenticate() first");
            return Uni.createFrom().failure(new AppException("S3 client not authenticated",
                AppException.ErrorType.AUTHENTICATION_ERROR));
        }

        if (directory == null) {
            directory = ""; // Use root if null
        }

        final String dirPrefix = directory; // For lambda
        LOG.infof("Listing S3 files with prefix: %s", dirPrefix);

        return Uni.createFrom().emitter(emitter -> {
            try {
                String bucket = (String) credentials.get("bucket");
                List<String> keys = s3Client.listObjectsV2(builder -> builder.bucket(bucket).prefix(dirPrefix))
                    .contents()
                    .stream()
                    .map(obj -> obj.key())
                    .collect(Collectors.toList());

                LOG.infof("Listed %d S3 files with prefix: %s", keys.size(), dirPrefix);
                emitter.complete(keys);
            } catch (Exception e) {
                LOG.errorf("Failed to list S3 files: %s", e.getMessage());
                emitter.fail(new AppException("Failed to list S3 files", e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
            }
        });
    }

    @Override
    @Retry(maxRetries = 3,
        delay = 1000,
        jitter = 200,
        maxDuration = 15000,
        retryOn = Exception.class,
        abortOn = {AppException.class})
    @Timeout(value = 20, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 30000,
        successThreshold = 2)
    @CircuitBreakerName("s3-download")
    @org.eclipse.microprofile.faulttolerance.Fallback(fallbackMethod = "fallbackDownload")
    public Uni<InputStream> downloadFile(String filePath) {
        if (s3Client == null || credentials == null) {
            LOG.error("S3 client not authenticated. Please call authenticate() first");
            return Uni.createFrom().failure(new AppException("S3 client not authenticated",
                AppException.ErrorType.AUTHENTICATION_ERROR));
        }

        if (filePath == null || filePath.isBlank()) {
            LOG.error("S3 file path cannot be null or empty");
            return Uni.createFrom().failure(new AppException("File path cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        LOG.infof("Downloading S3 file: %s", filePath);

        return Uni.createFrom().emitter(emitter -> {
            try {
                String bucket = (String) credentials.get("bucket");
                byte[] bytes = s3Client.getObject(builder -> builder.bucket(bucket).key(filePath))
                    .readAllBytes();

                LOG.infof("Successfully downloaded S3 file %s (%d bytes)", filePath, bytes.length);
                emitter.complete(new ByteArrayInputStream(bytes));
            } catch (Exception e) {
                LOG.errorf("Failed to download S3 file %s: %s", filePath, e.getMessage());
                emitter.fail(new AppException("Failed to download S3 file: " + filePath, e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
            }
        });
    }

    /**
     * Fallback method for downloading S3 files when the primary method fails.
     * Returns an empty stream as a fallback.
     */
    public Uni<InputStream> fallbackDownload(String filePath) {
        LOG.warnf("Using fallback for S3 file download: %s", filePath);
        return Uni.createFrom().item(new ByteArrayInputStream(new byte[0]));
    }

    @Override
    public ConnectorType getProviderType() {
        return ConnectorType.S3;
    }
}
