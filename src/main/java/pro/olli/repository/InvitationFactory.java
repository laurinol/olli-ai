/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.repository.Factory;
import pro.olli.repository.firestore.FirestoreInvitationRepository;
import pro.olli.repository.mongodb.MongoInvitationRepository;

/**
 * Factory for producing InvitationRepository instances based on configuration.
 */
@ApplicationScoped
public class InvitationFactory {

    private static final Logger LOG = Logger.getLogger(InvitationFactory.class);

    private final String databaseProvider;

    @Inject
    public InvitationFactory(
            @ConfigProperty(name = "database.provider", defaultValue = "mongodb") String databaseProvider) {
        this.databaseProvider = databaseProvider;
        LOG.infof("Invitation factory initialized with database provider: %s", databaseProvider);
    }

    /**
     * Produces the appropriate InvitationRepository based on configuration.
     *
     * @return An implementation of InvitationRepository
     */
    @Produces
    @Factory
    @Singleton
    public InvitationRepository createInvitationRepository(
            @Named("mongodb-invitation") MongoInvitationRepository mongoInvitationRepository,
            @Named("firestore-invitation") FirestoreInvitationRepository firestoreInvitationRepository) {
        if ("mongodb".equalsIgnoreCase(databaseProvider)) {
            LOG.info("Creating MongoDB invitation repository");
            return mongoInvitationRepository;
        } else {
            LOG.info("Creating Firestore invitation repository");
            return firestoreInvitationRepository;
        }
    }
}