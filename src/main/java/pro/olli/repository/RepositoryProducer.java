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
import pro.olli.config.AppConfig;
import pro.olli.repository.firestore.FirestoreBillingRepository;
import pro.olli.repository.firestore.FirestoreChatRepository;
import pro.olli.repository.firestore.FirestoreStatsRepository;
import pro.olli.repository.firestore.FirestoreSyncRepository;
import pro.olli.repository.firestore.FirestoreUserRepository;
import pro.olli.repository.mongodb.MongoBillingRepository;
import pro.olli.repository.mongodb.MongoChatRepository;
import pro.olli.repository.mongodb.MongoStatsRepository;
import pro.olli.repository.mongodb.MongoSyncRepository;
import pro.olli.repository.mongodb.MongoUserRepository;

/**
 * Factory for creating repository instances based on configuration. This allows switching between different database
 * implementations.
 */
@ApplicationScoped
public class RepositoryProducer {

    private static final Logger LOG = Logger.getLogger(RepositoryProducer.class);

    private final AppConfig config;

    @Inject
    public RepositoryProducer(AppConfig config) {
        this.config = config;
        LOG.infof("Repository factory initialized with database provider: %s", config.getDatabaseProvider());
    }

    @Produces
    @Factory
    @Singleton
    public UserRepository createUserRepository(
        @Named("mongodb-user") MongoUserRepository mongoUserRepository,
        @Named("firestore-user") FirestoreUserRepository firestoreUserRepository) {
        if ("mongodb".equalsIgnoreCase(config.getDatabaseProvider())) {
            LOG.info("Creating MongoDB user repository");
            return mongoUserRepository;
        } else {
            LOG.info("Creating Firestore user repository");
            return firestoreUserRepository;
        }
    }

    @Produces
    @Factory
    @Singleton
    public ChatRepository createChatRepository(
        @Named("mongodb-chat") MongoChatRepository mongoChatRepository,
        @Named("firestore-chat") FirestoreChatRepository firestoreChatRepository) {
        if ("mongodb".equalsIgnoreCase(config.getDatabaseProvider())) {
            LOG.info("Creating MongoDB chat repository");
            return mongoChatRepository;
        } else {
            LOG.info("Creating Firestore chat repository");
            return firestoreChatRepository;
        }
    }

    @Produces
    @Factory
    @Singleton
    public SyncRepository createSyncRepository(
        @Named("mongodb-sync") MongoSyncRepository mongoSyncRepository,
        @Named("firestore-sync") FirestoreSyncRepository firestoreSyncRepository) {
        if ("mongodb".equalsIgnoreCase(config.getDatabaseProvider())) {
            LOG.info("Creating MongoDB sync repository");
            return mongoSyncRepository;
        } else {
            LOG.info("Creating Firestore sync repository");
            return firestoreSyncRepository;
        }
    }

    @Produces
    @Factory
    @Singleton
    public StatsRepository createStatsRepository(
        @Named("mongodb-stats") MongoStatsRepository mongoStatsRepository,
        @Named("firestore-stats") FirestoreStatsRepository firestoreStatsRepository) {
        if ("mongodb".equalsIgnoreCase(config.getDatabaseProvider())) {
            LOG.info("Creating MongoDB stats repository");
            return mongoStatsRepository;
        } else {
            LOG.info("Creating Firestore stats repository");
            return firestoreStatsRepository;
        }
    }

    @Produces
    @Factory
    @Singleton
    public BillingRepository createBillingRepository(
        @Named("mongodb-billing") MongoBillingRepository mongoBillingRepository,
        @Named("firestore-billing") FirestoreBillingRepository firestoreBillingRepository) {
        if ("mongodb".equalsIgnoreCase(config.getDatabaseProvider())) {
            LOG.info("Creating MongoDB billing repository");
            return mongoBillingRepository;
        } else {
            LOG.info("Creating Firestore billing repository");
            return firestoreBillingRepository;
        }
    }
}
