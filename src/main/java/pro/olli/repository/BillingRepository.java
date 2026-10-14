/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.repository;

import io.smallrye.mutiny.Uni;
import pro.olli.models.BillingTransaction;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for billing operations.
 */
public interface BillingRepository {

    /**
     * Creates a new billing transaction
     */
    Uni<BillingTransaction> createTransaction(BillingTransaction transaction);

    /**
     * Updates an existing billing transaction
     */
    Uni<BillingTransaction> updateTransaction(BillingTransaction transaction);

    /**
     * Gets a billing transaction by ID
     */
    Uni<Optional<BillingTransaction>> getTransaction(String transactionId);

    /**
     * Gets all transactions for a business, optionally filtered by date range
     */
    Uni<List<BillingTransaction>> getBusinessTransactions(String businessId, Instant fromDate, Instant toDate);

    /**
     * Gets all transactions of a specific type for a business
     */
    Uni<List<BillingTransaction>> getBusinessTransactionsByType(
            String businessId, BillingTransaction.TransactionType type, Instant fromDate, Instant toDate);

    /**
     * Gets the most recent transaction for a business by type
     */
    Uni<Optional<BillingTransaction>> getLatestBusinessTransaction(
            String businessId, BillingTransaction.TransactionType type);
            
    /**
     * Gets all failed payment transactions after a specific date
     */
    Uni<List<BillingTransaction>> getFailedPayments(Instant afterDate);
    
    /**
     * Gets a transaction by its external payment ID (e.g., Stripe PaymentIntent ID)
     */
    Uni<Optional<BillingTransaction>> getTransactionByExternalId(String externalPaymentId);

    /**
     * Updates business usage metrics
     */
    Uni<Void> updateBusinessUsage(String businessId, int connectorBytes, int documentsProcessed, int queriesExecuted);
}
