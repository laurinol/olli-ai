/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.repository;

import com.google.cloud.firestore.Firestore;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;
import pro.olli.config.AppConfig;
import pro.olli.repository.firestore.FirestoreBillingRepository;
import pro.olli.repository.firestore.FirestoreChatRepository;
import pro.olli.repository.firestore.FirestoreStatsRepository;
import pro.olli.repository.firestore.FirestoreSyncRepository;
import pro.olli.repository.firestore.FirestoreUserRepository;
import pro.olli.repository.memory.MemorySyncRepository;
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
    private final MongoClient mongoClient;
    private final Firestore firestore;

    @Inject
    public RepositoryProducer(AppConfig config, MongoClient mongoClient, Firestore firestore) {
        this.config = config;
        this.mongoClient = mongoClient;
        this.firestore = firestore;
        LOG.infof("Repository factory initialized with database provider: %s", config.getDatabaseProvider());
    }

    @Produces
    //@Factory
    @Singleton
    public UserRepository createUserRepository() {
        if (config.isDevMode()) {
            LOG.info("Creating MongoDB user repository");
            return new MongoUserRepository(config, mongoClient);
        } else {
            LOG.info("Creating Firestore user repository");
            return new FirestoreUserRepository(firestore);
        }
    }

    @Produces
    //@Factory
    @Singleton
    public ChatRepository createChatRepository() {
        if (config.isDevMode()) {
            LOG.info("Creating MongoDB chat repository");
            return new MongoChatRepository(mongoClient);
        } else {
            LOG.info("Creating Firestore chat repository");
            return new FirestoreChatRepository(firestore);
        }
    }

    @Produces
    //@Factory
    @Singleton
    public SyncRepository createSyncRepository() {
        LOG.info("Creating SyncRepository with mongoClient: " + (mongoClient != null ? "available" : "null"));

        // Check if we're using the memory repository for RAG testing
        String implType = System.getProperty("ollama.implementation", "");
        /*if ("rag".equalsIgnoreCase(implType) && Boolean.parseBoolean(System.getProperty("use.memory.repository", "true"))) {
            LOG.info("Using memory repository for RAG testing");
            return new MemorySyncRepository();
        } else*/
        if (config.isDevMode()) {
            LOG.info("Creating MongoDB sync repository");
            return new MongoSyncRepository(config, mongoClient);
        } else {
            LOG.info("Creating Firestore sync repository");
            return new FirestoreSyncRepository(firestore);
        }
    }

    @Produces
    //@Factory
    @Singleton
    public StatsRepository createStatsRepository() {
        if (config.isDevMode()) {
            LOG.info("Creating MongoDB stats repository");
            return new MongoStatsRepository(mongoClient);
        } else {
            LOG.info("Creating Firestore stats repository");
            return new FirestoreStatsRepository(firestore);
        }
    }

    @Produces
    //@Factory
    @Singleton
    public BillingRepository createBillingRepository() {
        if (config.isDevMode()) {
            LOG.info("Creating MongoDB billing repository");
            return new MongoBillingRepository(mongoClient);
        } else {
            LOG.info("Creating Firestore billing repository");
            return new FirestoreBillingRepository(firestore);
        }
    }
}
