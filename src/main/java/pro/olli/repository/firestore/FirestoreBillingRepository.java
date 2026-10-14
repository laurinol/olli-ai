/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.repository.firestore;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.gson.Gson;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;
import pro.olli.models.BillingTransaction;
import pro.olli.models.Business;
import pro.olli.repository.BillingRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FirestoreBillingRepository implements BillingRepository {

    private static final Logger LOG = Logger.getLogger(FirestoreBillingRepository.class);
    private static final String TRANSACTIONS_COLLECTION = "billing_transactions";
    private static final String BUSINESSES_COLLECTION = "businesses";

    private final Firestore firestore;

    @Inject
    public FirestoreBillingRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Uni<BillingTransaction> createTransaction(BillingTransaction transaction) {
        Map<String, Object> data = transactionToMap(transaction);

        return Uni.createFrom().future(
            firestore.collection(TRANSACTIONS_COLLECTION)
                .document(transaction.getId())
                .set(data)
        )
        .map(writeResult -> {
            LOG.infof("Created billing transaction %s for business %s",
                    transaction.getId(), transaction.getBusinessId());
            return transaction;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to create billing transaction: %s", e.getMessage()));
    }

    @Override
    public Uni<BillingTransaction> updateTransaction(BillingTransaction transaction) {
        Map<String, Object> data = transactionToMap(transaction);

        return Uni.createFrom().future(
            firestore.collection(TRANSACTIONS_COLLECTION)
                .document(transaction.getId())
                .update(data)
        )
        .map(writeResult -> {
            LOG.infof("Updated billing transaction %s", transaction.getId());
            return transaction;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to update billing transaction: %s", e.getMessage()));
    }

    @Override
    public Uni<Optional<BillingTransaction>> getTransaction(String transactionId) {
        return Uni.createFrom().future(
            firestore.collection(TRANSACTIONS_COLLECTION)
                .document(transactionId)
                .get()
        )
        .map(doc -> {
            if (!doc.exists()) {
                LOG.infof("Billing transaction %s not found", transactionId);
                return Optional.<BillingTransaction>empty();
            }

            try {
                BillingTransaction transaction = mapToTransaction(doc);
                return Optional.of(transaction);
            } catch (Exception e) {
                LOG.errorf("Error mapping transaction data: %s", e.getMessage());
                throw e;
            }
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to get billing transaction: %s", e.getMessage()));
    }

    @Override
    public Uni<List<BillingTransaction>> getBusinessTransactions(String businessId, Instant fromDate, Instant toDate) {
        Query query = firestore
            .collection(TRANSACTIONS_COLLECTION)
            .whereEqualTo("businessId", businessId);

        if (fromDate != null) {
            query = query.whereGreaterThanOrEqualTo("createdAt", fromDate);
        }

        if (toDate != null) {
            query = query.whereLessThanOrEqualTo("createdAt", toDate);
        }

        query = query.orderBy("createdAt", Query.Direction.DESCENDING);

        return Uni.createFrom().future(query.get())
            .map(querySnapshot -> {
                List<BillingTransaction> transactions = new ArrayList<>();
                for (QueryDocumentSnapshot doc : querySnapshot.getDocuments()) {
                    transactions.add(mapToTransaction(doc));
                }
                LOG.infof("Retrieved %d transactions for business %s", transactions.size(), businessId);
                return transactions;
            })
            .onFailure().invoke(e -> LOG.errorf("Failed to get business transactions: %s", e.getMessage()));
    }

    @Override
    public Uni<List<BillingTransaction>> getBusinessTransactionsByType(
            String businessId, BillingTransaction.TransactionType type, Instant fromDate, Instant toDate) {
        Query query = firestore
            .collection(TRANSACTIONS_COLLECTION)
            .whereEqualTo("businessId", businessId)
            .whereEqualTo("type", type.name());

        if (fromDate != null) {
            query = query.whereGreaterThanOrEqualTo("createdAt", fromDate);
        }

        if (toDate != null) {
            query = query.whereLessThanOrEqualTo("createdAt", toDate);
        }

        query = query.orderBy("createdAt", Query.Direction.DESCENDING);

        return Uni.createFrom().future(query.get())
            .map(querySnapshot -> {
                List<BillingTransaction> transactions = new ArrayList<>();
                for (QueryDocumentSnapshot doc : querySnapshot.getDocuments()) {
                    transactions.add(mapToTransaction(doc));
                }
                LOG.infof("Retrieved %d transactions of type %s for business %s",
                        transactions.size(), type, businessId);
                return transactions;
            })
            .onFailure().invoke(e -> LOG.errorf("Failed to get business transactions by type: %s", e.getMessage()));
    }

    @Override
    public Uni<Optional<BillingTransaction>> getLatestBusinessTransaction(
            String businessId, BillingTransaction.TransactionType type) {
        Query query = firestore
            .collection(TRANSACTIONS_COLLECTION)
            .whereEqualTo("businessId", businessId)
            .whereEqualTo("type", type.name())
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1);

        return Uni.createFrom().future(query.get())
            .map(querySnapshot -> {
                if (querySnapshot.isEmpty()) {
                    LOG.infof("No transactions of type %s found for business %s", type, businessId);
                    return Optional.<BillingTransaction>empty();
                }

                BillingTransaction transaction = mapToTransaction(querySnapshot.getDocuments().get(0));
                return Optional.of(transaction);
            })
            .onFailure().invoke(e -> LOG.errorf("Failed to get latest business transaction: %s", e.getMessage()));
    }

    @Override
    public Uni<List<BillingTransaction>> getFailedPayments(Instant afterDate) {
        Query query = firestore
            .collection(TRANSACTIONS_COLLECTION)
            .whereEqualTo("status", BillingTransaction.TransactionStatus.FAILED.name())
            .whereGreaterThanOrEqualTo("createdAt", afterDate)
            .orderBy("createdAt", Query.Direction.DESCENDING);

        return Uni.createFrom().future(query.get())
            .map(querySnapshot -> {
                List<BillingTransaction> transactions = new ArrayList<>();
                for (QueryDocumentSnapshot doc : querySnapshot.getDocuments()) {
                    transactions.add(mapToTransaction(doc));
                }
                LOG.infof("Retrieved %d failed payments after %s",
                        transactions.size(), afterDate);
                return transactions;
            })
            .onFailure().invoke(e -> LOG.errorf("Failed to get failed payments: %s", e.getMessage()));
    }

    @Override
    public Uni<Optional<BillingTransaction>> getTransactionByExternalId(String externalPaymentId) {
        if (externalPaymentId == null || externalPaymentId.isBlank()) {
            LOG.warn("Attempt to get transaction with null or blank external payment ID");
            return Uni.createFrom().item(Optional.empty());
        }

        Query query = firestore
            .collection(TRANSACTIONS_COLLECTION)
            .whereEqualTo("externalPaymentId", externalPaymentId)
            .limit(1);

        return Uni.createFrom().future(query.get())
            .map(querySnapshot -> {
                if (querySnapshot.isEmpty()) {
                    LOG.infof("No transaction found with external payment ID: %s", externalPaymentId);
                    return Optional.<BillingTransaction>empty();
                }

                BillingTransaction transaction = mapToTransaction(querySnapshot.getDocuments().get(0));
                LOG.infof("Found transaction %s with external payment ID: %s",
                        transaction.getId(), externalPaymentId);
                return Optional.of(transaction);
            })
            .onFailure().invoke(e -> LOG.errorf("Failed to get transaction by external ID: %s, Error: %s",
                    externalPaymentId, e.getMessage()));
    }

    @Override
    public Uni<Void> updateBusinessUsage(String businessId, int connectorBytes, int documentsProcessed, int queriesExecuted) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("currentConnectorUsageBytes", connectorBytes);
        updates.put("documentsProcessedThisMonth", documentsProcessed);
        updates.put("queriesThisMonth", queriesExecuted);
        updates.put("updatedAt", Instant.now());

        return Uni.createFrom().future(
            firestore
                .collection(BUSINESSES_COLLECTION)
                .document(businessId)
                .update(updates)
        )
        .onItem().ignore().andContinueWithNull()
        .onFailure().invoke(e -> LOG.errorf("Failed to update business usage metrics: %s", e.getMessage()));
    }

    private Map<String, Object> transactionToMap(BillingTransaction transaction) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", transaction.getId());
        data.put("businessId", transaction.getBusinessId());
        data.put("type", transaction.getType().name());
        data.put("status", transaction.getStatus().name());
        data.put("amountInCents", transaction.getAmountInCents());
        data.put("description", transaction.getDescription());
        data.put("paymentMethodId", transaction.getPaymentMethodId());
        data.put("externalPaymentId", transaction.getExternalPaymentId());
        data.put("createdAt", transaction.getCreatedAt());

        if (transaction.getMetadata() != null) {
            data.put("metadata", transaction.getMetadata());
        }

        return data;
    }

    private BillingTransaction mapToTransaction(DocumentSnapshot doc) {
        String id = doc.getId();
        String businessId = doc.getString("businessId");
        BillingTransaction.TransactionType type = BillingTransaction.TransactionType.valueOf(doc.getString("type"));
        BillingTransaction.TransactionStatus status = BillingTransaction.TransactionStatus.valueOf(doc.getString("status"));
        int amountInCents = doc.getLong("amountInCents").intValue();
        String description = doc.getString("description");
        String paymentMethodId = doc.getString("paymentMethodId");
        String externalPaymentId = doc.getString("externalPaymentId");
        Instant createdAt = doc.getTimestamp("createdAt").toDate().toInstant();

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");

        return new BillingTransaction(
                id, businessId, type, status, amountInCents,
                description, paymentMethodId, externalPaymentId, createdAt, metadata);
    }
}
