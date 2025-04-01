/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.config;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Centralized configuration management for the application.
 * This class provides a single point of access for all configuration properties.
 */
@Startup
@ApplicationScoped
public class AppConfig {
    private static final Logger LOG = Logger.getLogger(AppConfig.class);

    // Environment
    @ConfigProperty(name = "quarkus.profile", defaultValue = "dev")
    String profile;

    @ConfigProperty(name = "app.dev.mode", defaultValue = "false")
    boolean devMode;

    @ConfigProperty(name = "app.domain", defaultValue = "http://localhost:8080")
    String appDomain;

    @ConfigProperty(name = "app.worker.multiplier", defaultValue = "1")
    int workerMultiplier;

    // Database
    @ConfigProperty(name = "database.provider", defaultValue = "mongodb")
    String databaseProvider;

    // MongoDB
    @ConfigProperty(name = "quarkus.mongodb.connection-string")
    String mongoDbConnectionString;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "olli")
    String mongoDbName;

    // Firestore
    @ConfigProperty(name = "quarkus.google.cloud.project-id")
    String gcpProjectId;

    @ConfigProperty(name = "quarkus.google.cloud.location")
    String gcpLocation;

    @ConfigProperty(name = "quarkus.firestore.project-id")
    String firestoreProjectId;

    // AWS
    @ConfigProperty(name = "aws.s3.access-key")
    String awsAccessKey;

    @ConfigProperty(name = "aws.s3.secret-key")
    String awsSecretKey;

    // Resilience
    @ConfigProperty(name = "resilience.retry.max-retries", defaultValue = "3")
    int resilienceRetryMaxRetries;

    @ConfigProperty(name = "resilience.retry.delay-ms", defaultValue = "1000")
    int resilienceRetryDelayMs;

    @ConfigProperty(name = "resilience.timeout.default-ms", defaultValue = "5000")
    int resilienceTimeoutDefaultMs;

    @ConfigProperty(name = "resilience.circuit-breaker.request-volume-threshold", defaultValue = "20")
    int resilienceCircuitBreakerRequestVolumeThreshold;

    @ConfigProperty(name = "resilience.circuit-breaker.failure-ratio", defaultValue = "0.5")
    double resilienceCircuitBreakerFailureRatio;

    @ConfigProperty(name = "resilience.circuit-breaker.delay-ms", defaultValue = "30000")
    int resilienceCircuitBreakerDelayMs;

    // Queue
    @ConfigProperty(name = "queue.provider", defaultValue = "google")
    String queueProvider;

    @ConfigProperty(name = "queue.name", defaultValue = "sync-tasks")
    String queueName;

    @ConfigProperty(name = "rabbitmq.queue", defaultValue = "sync-queue")
    String rabbitmqQueue;

    @ConfigProperty(name = "rabbitmq.host", defaultValue = "localhost")
    String rabbitmqHost;

    @ConfigProperty(name = "rabbitmq.port", defaultValue = "5672")
    int rabbitmqPort;

    @ConfigProperty(name = "rabbitmq.username", defaultValue = "admin")
    String rabbitmqUsername;

    @ConfigProperty(name = "rabbitmq.password", defaultValue = "pass123")
    String rabbitmqPassword;

    // Worker
    @ConfigProperty(name = "worker.backoff.initial-ms", defaultValue = "1000")
    int workerBackoffInitialMs;

    @ConfigProperty(name = "worker.backoff.max-ms", defaultValue = "60000")
    int workerBackoffMaxMs;

    @ConfigProperty(name = "worker.backoff.multiplier", defaultValue = "1.5")
    double workerBackoffMultiplier;

    // AI
    @ConfigProperty(name = "gcp.vertex.location", defaultValue = "us-west1")
    String gcpVertexLocation;

    @ConfigProperty(name = "gcp.vertex.endpoint-id", defaultValue = "todo")
    String gcpVertexEndpoint;

    @ConfigProperty(name = "gcp.vertex.model", defaultValue = "text-embedding-004")
    String gcpVertexModel;

    @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434")
    String ollamaEndpoint;

    @ConfigProperty(name = "ollama.embedding.model", defaultValue = "tinyllama")
    String ollamaEmbeddingModel;

    @ConfigProperty(name = "ollama.embedding.dimension", defaultValue = "768")
    int ollamaEmbeddingDimension;

    @ConfigProperty(name = "ollama.chat.model", defaultValue = "tinyllama")
    String ollamaChatModel;

    @ConfigProperty(name = "ai.tokenizer.path", defaultValue = "./models/tokenizer")
    String aiTokenizerPath;

    // ONNX
    @ConfigProperty(name = "onnx.enabled", defaultValue = "false")
    boolean onnxEnabled;

    @ConfigProperty(name = "onnx.runtime", defaultValue = "ONNXRuntime")
    String onnxRuntime;

    @ConfigProperty(name = "onnx.model.path", defaultValue = "models/all-MiniLM-L6-v2.onnx")
    String onnxModelPath;

    @ConfigProperty(name = "onnx.tokenizer.path", defaultValue = "models/tokenizer.json")
    String onnxTokenizerPath;

    @ConfigProperty(name = "onnx.embedding.dimension", defaultValue = "384")
    int onnxEmbeddingDimension;

    @ConfigProperty(name = "onnx.max.sequence.length", defaultValue = "128")
    int onnxMaxSequenceLength;

    // Billing
    @ConfigProperty(name = "billing.enabled", defaultValue = "false")
    boolean billingEnabled;

    @ConfigProperty(name = "payment.provider", defaultValue = "stripe")
    String paymentProvider;

    // Stripe
    @ConfigProperty(name = "stripe.api.key")
    String stripeApiKey;

    @ConfigProperty(name = "stripe.price.basic")
    String stripePriceBasic;

    @ConfigProperty(name = "stripe.price.professional")
    String stripePriceProfessional;

    @ConfigProperty(name = "stripe.price.enterprise")
    String stripePriceEnterprise;

    @ConfigProperty(name = "stripe.webhook.secret")
    String stripeWebhookSecret;

    @ConfigProperty(name = "stripe.webhook.url")
    String stripeWebhookUrl;

    @ConfigProperty(name = "stripe.webhook.auto-register", defaultValue = "false")
    boolean stripeWebhookAutoRegister;

    // OAuth
    @ConfigProperty(name = "quarkus.oidc.google.client-id")
    String googleClientId;

    @ConfigProperty(name = "quarkus.oidc.google.client-secret")
    String googleClientSecret;

    // JWT
    @ConfigProperty(name = "mp.jwt.verify.issuer", defaultValue = "olli-ai-backend")
    String jwtIssuer;

    /**
     * Initializes the configuration and logs key settings.
     */
    @jakarta.annotation.PostConstruct
    public void initialize() {
        LOG.infof("AppConfig initialized with profile: %s", profile);
        LOG.infof("Database provider: %s", databaseProvider);
        LOG.infof("ONNX enabled: %s", onnxEnabled);
        LOG.infof("Billing enabled: %s", billingEnabled);
        LOG.infof("Queue provider: %s", queueProvider);
    }

    // Helper methods for derived properties

    /**
     * Determines if the application is running in development mode.
     *
     * @return true if running in dev mode, false otherwise
     */
    public boolean isDevMode() {
        return devMode || "dev".equals(profile);
    }

    /**
     * Determines if MongoDB is the active database provider.
     *
     * @return true if MongoDB is active, false otherwise
     */
    public boolean isMongoDb() {
        return "mongodb".equals(databaseProvider);
    }

    /**
     * Determines if Firestore is the active database provider.
     *
     * @return true if Firestore is active, false otherwise
     */
    public boolean isFirestore() {
        return "firestore".equals(databaseProvider);
    }

    /**
     * Determines if Google Cloud Tasks is the active queue provider.
     *
     * @return true if Google Cloud Tasks is active, false otherwise
     */
    public boolean isGoogleCloudTasks() {
        return "google".equals(queueProvider);
    }

    /**
     * Determines if RabbitMQ is the active queue provider.
     *
     * @return true if RabbitMQ is active, false otherwise
     */
    public boolean isRabbitMq() {
        return "rabbitmq".equals(queueProvider);
    }

    /**
     * Determines if Stripe is the active payment provider.
     *
     * @return true if Stripe is active, false otherwise
     */
    public boolean isStripe() {
        return "stripe".equals(paymentProvider);
    }

    // Getters

    public String getProfile() {
        return profile;
    }

    public String getAppDomain() {
        return appDomain;
    }

    public int getWorkerMultiplier() {
        return workerMultiplier;
    }

    public String getDatabaseProvider() {
        return databaseProvider;
    }

    public String getMongoDbConnectionString() {
        return mongoDbConnectionString;
    }

    public String getMongoDbName() {
        return mongoDbName;
    }

    public String getGcpProjectId() {
        return gcpProjectId;
    }

    public String getGcpLocation() {
        return gcpLocation;
    }

    public String getFirestoreProjectId() {
        return firestoreProjectId;
    }

    public String getAwsAccessKey() {
        return awsAccessKey;
    }

    public String getAwsSecretKey() {
        return awsSecretKey;
    }

    public int getResilienceRetryMaxRetries() {
        return resilienceRetryMaxRetries;
    }

    public int getResilienceRetryDelayMs() {
        return resilienceRetryDelayMs;
    }

    public int getResilienceTimeoutDefaultMs() {
        return resilienceTimeoutDefaultMs;
    }

    public int getResilienceCircuitBreakerRequestVolumeThreshold() {
        return resilienceCircuitBreakerRequestVolumeThreshold;
    }

    public double getResilienceCircuitBreakerFailureRatio() {
        return resilienceCircuitBreakerFailureRatio;
    }

    public int getResilienceCircuitBreakerDelayMs() {
        return resilienceCircuitBreakerDelayMs;
    }

    public String getQueueProvider() {
        return queueProvider;
    }

    public String getQueueName() {
        return queueName;
    }

    public String getRabbitmqQueue() {
        return rabbitmqQueue;
    }

    public String getRabbitmqHost() {
        return rabbitmqHost;
    }

    public int getRabbitmqPort() {
        return rabbitmqPort;
    }

    public String getRabbitmqUsername() {
        return rabbitmqUsername;
    }

    public String getRabbitmqPassword() {
        return rabbitmqPassword;
    }

    public int getWorkerBackoffInitialMs() {
        return workerBackoffInitialMs;
    }

    public int getWorkerBackoffMaxMs() {
        return workerBackoffMaxMs;
    }

    public double getWorkerBackoffMultiplier() {
        return workerBackoffMultiplier;
    }

    public String getGcpVertexLocation() {
        return gcpVertexLocation;
    }

    public String getGcpVertexEndpoint() { return gcpVertexEndpoint; }

    public String getGcpVertexModel() {
        return gcpVertexModel;
    }

    public String getOllamaEndpoint() {
        return ollamaEndpoint;
    }

    public String getOllamaEmbeddingModel() {
        return ollamaEmbeddingModel;
    }

    public int getOllamaEmbeddingDimension() {
        return ollamaEmbeddingDimension;
    }

    public String getOllamaChatModel() {
        return ollamaChatModel;
    }

    public String getAiTokenizerPath() {
        return aiTokenizerPath;
    }

    public boolean isOnnxEnabled() {
        return onnxEnabled;
    }

    public String getOnnxRuntime() {
        return onnxRuntime;
    }

    public String getOnnxModelPath() {
        return onnxModelPath;
    }

    public String getOnnxTokenizerPath() {
        return onnxTokenizerPath;
    }

    public int getOnnxEmbeddingDimension() {
        return onnxEmbeddingDimension;
    }

    public int getOnnxMaxSequenceLength() {
        return onnxMaxSequenceLength;
    }

    public boolean isBillingEnabled() {
        return billingEnabled;
    }

    public String getPaymentProvider() {
        return paymentProvider;
    }

    public String getStripeApiKey() {
        return stripeApiKey;
    }

    public String getStripePriceBasic() {
        return stripePriceBasic;
    }

    public String getStripePriceProfessional() {
        return stripePriceProfessional;
    }

    public String getStripePriceEnterprise() {
        return stripePriceEnterprise;
    }

    public String getStripeWebhookSecret() {
        return stripeWebhookSecret;
    }

    public String getStripeWebhookUrl() {
        return stripeWebhookUrl;
    }

    public boolean isStripeWebhookAutoRegister() {
        return stripeWebhookAutoRegister;
    }

    public String getGoogleClientId() {
        return googleClientId;
    }

    public String getGoogleClientSecret() {
        return googleClientSecret;
    }

    public String getJwtIssuer() {
        return jwtIssuer;
    }
}
