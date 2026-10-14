/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.repository;

import com.google.cloud.firestore.Firestore;
import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;
import pro.olli.config.AppConfig;
import pro.olli.repository.firestore.FirestoreInvitationRepository;
import pro.olli.repository.mongodb.MongoInvitationRepository;

/**
 * Factory for producing InvitationRepository instances based on configuration.
 */
@ApplicationScoped
public class InvitationProducer {

    private static final Logger LOG = Logger.getLogger(InvitationProducer.class);

    private final AppConfig config;
    private final MongoClient mongoClient;
    private final Firestore firestore;

    @Inject
    public InvitationProducer(AppConfig config, MongoClient mongoClient, Firestore firestore) {
        this.config = config;
        this.mongoClient = mongoClient;
        this.firestore = firestore;
        LOG.infof("Invitation factory initialized with database provider: %s", config.getDatabaseProvider());
    }

    /**
     * Produces the appropriate InvitationRepository based on configuration.
     *
     * @return An implementation of InvitationRepository
     */
    @Produces
    //@Factory
    @Singleton
    public InvitationRepository createInvitationRepository() {
        if (config.isDevMode()) {
            LOG.info("Creating MongoDB invitation repository");
            return new MongoInvitationRepository(config, mongoClient);
        } else {
            LOG.info("Creating Firestore invitation repository");
            return new FirestoreInvitationRepository(firestore);
        }
    }
}
