package pro.olli.storage.impl;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import pro.olli.model.StorageCredentials;
import pro.olli.storage.StorageProvider;
import pro.olli.storage.StorageProviderType;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AWS S3 implementation of StorageProvider.
 */
@ApplicationScoped
public class S3Provider implements StorageProvider {

    private static final Logger LOG = Logger.getLogger(S3Provider.class);

    private S3Client s3Client;
    private StorageCredentials credentials; // Store creds for use in methods

    public S3Provider() {
        // Placeholder - real client set via authenticate
        this.s3Client = null;
        this.credentials = null;
    }

    @Override
    public Uni<Boolean> authenticate(StorageCredentials credentials) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String accessKey = (String) credentials.get("accessKey");
                String secretKey = (String) credentials.get("secretKey");
                String bucket = (String) credentials.get("bucket");
                this.credentials = credentials; // Store for later use
                this.s3Client = S3Client.builder()
                    .credentialsProvider(() -> AwsBasicCredentials.create(accessKey, secretKey))
                    .region(Region.US_EAST_1) // Configurable via creds if needed
                    .build();
                // Test authentication
                s3Client.listObjectsV2(builder -> builder.bucket(bucket).maxKeys(1));
                emitter.complete(true);
            } catch (Exception e) {
                LOG.errorf("S3 authentication failed: %s", e.getMessage());
                emitter.complete(false);
            }
        });
    }

    @Override
    public Uni<List<String>> listFiles(String directory) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String bucket = (String) credentials.get("bucket");
                List<String> keys = s3Client.listObjectsV2(builder -> builder.bucket(bucket).prefix(directory))
                    .contents()
                    .stream()
                    .map(obj -> obj.key())
                    .collect(Collectors.toList());
                emitter.complete(keys);
            } catch (Exception e) {
                LOG.errorf("Failed to list S3 files: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<InputStream> downloadFile(String filePath) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String bucket = (String) credentials.get("bucket");
                byte[] bytes = s3Client.getObject(builder -> builder.bucket(bucket).key(filePath))
                    .readAllBytes();
                emitter.complete(new ByteArrayInputStream(bytes));
            } catch (Exception e) {
                LOG.errorf("Failed to download S3 file %s: %s", filePath, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public StorageProviderType getProviderType() {
        return StorageProviderType.S3;
    }
}
