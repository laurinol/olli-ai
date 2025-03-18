/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.worker;

import com.google.cloud.aiplatform.v1beta1.IndexServiceClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import static org.mockito.Mockito.mock;

/**
 * Test alternative for IndexServiceClientFactory
 * This provides a mock implementation of IndexServiceClient for tests
 */
@ApplicationScoped
@Alternative
public class MockIndexServiceClientFactory {
    
    private static final Logger LOG = Logger.getLogger(MockIndexServiceClientFactory.class);
    
    @Produces
    @Singleton
    @Alternative
    public IndexServiceClient createMockIndexServiceClient() {
        LOG.info("Creating mock IndexServiceClient for tests");
        return mock(IndexServiceClient.class);
    }
}