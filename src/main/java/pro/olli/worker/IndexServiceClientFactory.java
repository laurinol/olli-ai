/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.worker;

import com.google.cloud.aiplatform.v1beta1.IndexServiceClient;
import com.google.cloud.aiplatform.v1beta1.IndexServiceSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.io.IOException;

@ApplicationScoped
public class IndexServiceClientFactory {

    @Produces
    @ApplicationScoped
    public IndexServiceClient createIndexServiceClient() {
        // Only create a real client if not in development mode
        if (!"dev".equals(System.getProperty("quarkus.profile"))) {
            try {
                IndexServiceSettings settings = IndexServiceSettings.newBuilder().build();
                return IndexServiceClient.create(settings);
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize IndexServiceClient", e);
            }
        }
        
        // Return null for development mode
        // The SyncWorker will handle this case with its own checks
        return null;
    }
}
