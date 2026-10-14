/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.config;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.CloudTasksSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.jboss.logging.Logger;

import java.io.IOException;

@ApplicationScoped
public class CloudTasksClientProducer {

    private static final Logger LOG = Logger.getLogger(CloudTasksClientProducer.class);

    @Produces
    @ApplicationScoped
    public CloudTasksClient produceCloudTasksClient() {
        try {
            // Customize settings if needed (e.g., credentials, endpoint)
            CloudTasksSettings settings = CloudTasksSettings.newBuilder().build();
            CloudTasksClient client = CloudTasksClient.create(settings);
            LOG.info("CloudTasksClient created successfully");
            return client;
        } catch (IOException e) {
            LOG.errorf("Failed to create CloudTasksClient: %s", e.getMessage());
            throw new RuntimeException("Unable to initialize CloudTasksClient", e);
        }
    }
}
