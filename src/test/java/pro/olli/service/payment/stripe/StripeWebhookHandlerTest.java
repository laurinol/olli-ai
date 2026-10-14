/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.service.payment.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.BillingTransaction;
import pro.olli.models.Business;
import pro.olli.repository.BillingRepository;
import pro.olli.repository.UserRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StripeWebhookHandlerTest {

    private TestStripeWebhookHandler webhookHandler;
    private BillingRepository billingRepository;
    private UserRepository userRepository;
    private String webhookSecret;

    @BeforeEach
    void setUp() {
        billingRepository = Mockito.mock(BillingRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        webhookSecret = "whsec_test_secret";
        webhookHandler = new TestStripeWebhookHandler(
                billingRepository,
                userRepository,
                webhookSecret);
    }

    @Test
    void testHandlePaymentIntentSucceeded() {
        // Arrange
        String payload = "{ \"type\": \"payment_intent.succeeded\" }";
        String signature = "test_signature";

        // Mock Event and PaymentIntent
        Event event = Mockito.mock(Event.class);
        EventDataObjectDeserializer deserializer = Mockito.mock(EventDataObjectDeserializer.class);
        PaymentIntent paymentIntent = Mockito.mock(PaymentIntent.class);

        // Configure event
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getId()).thenReturn("evt_123");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(paymentIntent));

        // Setup payment intent
        when(paymentIntent.getId()).thenReturn("pi_123");

        // Set the mocked event in our test handler
        webhookHandler.setMockedEvent(event);

        // Mock repository responses
        BillingTransaction transaction = new BillingTransaction(
                "business_123",
                BillingTransaction.TransactionType.SUBSCRIPTION_PAYMENT,
                BillingTransaction.TransactionStatus.PENDING,
                1000, // $10.00
                "Test payment",
                "pm_123",
                "pi_123",
                null
        );
        when(billingRepository.getTransactionByExternalId("pi_123")).thenReturn(
                Uni.createFrom().item(Optional.of(transaction))
        );
        when(billingRepository.updateTransaction(any())).thenReturn(
                Uni.createFrom().nullItem()
        );

        // Act
        Uni<Void> result = webhookHandler.handleWebhookEvent(payload, signature);
        result.await().indefinitely();

        // Assert
        verify(billingRepository).getTransactionByExternalId("pi_123");
        verify(billingRepository).updateTransaction(any());
        // Verify that the transaction status was updated to COMPLETED
        verify(billingRepository).updateTransaction(
                argThat(t -> t.getStatus() == BillingTransaction.TransactionStatus.COMPLETED)
        );
    }

    @Test
    void testHandlePaymentIntentFailed() {
        // Arrange
        String payload = "{ \"type\": \"payment_intent.payment_failed\" }";
        String signature = "test_signature";

        // Mock Event and PaymentIntent
        Event event = Mockito.mock(Event.class);
        EventDataObjectDeserializer deserializer = Mockito.mock(EventDataObjectDeserializer.class);
        PaymentIntent paymentIntent = Mockito.mock(PaymentIntent.class);

        // Configure event
        when(event.getType()).thenReturn("payment_intent.payment_failed");
        when(event.getId()).thenReturn("evt_123");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(paymentIntent));

        // Setup payment intent
        when(paymentIntent.getId()).thenReturn("pi_123");

        // Set the mocked event in our test handler
        webhookHandler.setMockedEvent(event);

        // Mock repository responses
        BillingTransaction transaction = new BillingTransaction(
                "business_123",
                BillingTransaction.TransactionType.SUBSCRIPTION_PAYMENT,
                BillingTransaction.TransactionStatus.PENDING,
                1000, // $10.00
                "Test payment",
                "pm_123",
                "pi_123",
                null
        );
        when(billingRepository.getTransactionByExternalId("pi_123")).thenReturn(
                Uni.createFrom().item(Optional.of(transaction))
        );
        when(billingRepository.updateTransaction(any())).thenReturn(
                Uni.createFrom().nullItem()
        );

        // Act
        Uni<Void> result = webhookHandler.handleWebhookEvent(payload, signature);
        result.await().indefinitely();

        // Assert
        verify(billingRepository).getTransactionByExternalId("pi_123");
        verify(billingRepository).updateTransaction(any());
        // Verify that the transaction status was updated to FAILED
        verify(billingRepository).updateTransaction(
                argThat(t -> t.getStatus() == BillingTransaction.TransactionStatus.FAILED)
        );
    }

    @Test
    void testHandleInvoicePaymentSucceeded() {
        // Arrange
        String payload = "{ \"type\": \"invoice.payment_succeeded\" }";
        String signature = "test_signature";

        // Mock Event and Invoice
        Event event = Mockito.mock(Event.class);
        EventDataObjectDeserializer deserializer = Mockito.mock(EventDataObjectDeserializer.class);
        Invoice invoice = Mockito.mock(Invoice.class);

        // Configure event
        when(event.getType()).thenReturn("invoice.payment_succeeded");
        when(event.getId()).thenReturn("evt_123");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(invoice));

        // Setup invoice
        when(invoice.getId()).thenReturn("in_123");
        when(invoice.getSubscription()).thenReturn("sub_123");
        when(invoice.getPaymentIntent()).thenReturn("pi_123");
        when(invoice.getAmountPaid()).thenReturn(1000L); // $10.00

        // Set the mocked event in our test handler
        webhookHandler.setMockedEvent(event);

        // Mock repository responses
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("stripeSubscriptionId", "sub_123");
        Business business = new Business(
                "business_123",
                "Test Business",
                "PRO",
                "active",
                Instant.now().minusSeconds(3600), // 1 hour ago
                Instant.now().plusSeconds(3600 * 24 * 30), // 30 days from now
                "pm_123",
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(3600),
                null,
                metadata,
                0L, 0L, 0L
        );

        when(userRepository.getBusinessBySubscriptionId("sub_123")).thenReturn(
                Uni.createFrom().item(Optional.of(business))
        );
        when(billingRepository.getTransactionByExternalId("pi_123")).thenReturn(
                Uni.createFrom().item(Optional.empty())
        );
        when(billingRepository.createTransaction(any())).thenReturn(
                Uni.createFrom().nullItem()
        );

        // Act
        Uni<Void> result = webhookHandler.handleWebhookEvent(payload, signature);
        result.await().indefinitely();

        // Assert
        verify(userRepository).getBusinessBySubscriptionId("sub_123");
        verify(billingRepository).getTransactionByExternalId("pi_123");
        verify(billingRepository).createTransaction(any());
        // Verify that a new COMPLETED transaction was created
        verify(billingRepository).createTransaction(
                argThat(t -> t.getStatus() == BillingTransaction.TransactionStatus.COMPLETED &&
                        t.getAmountInCents() == 1000 &&
                        t.getBusinessId().equals("business_123") &&
                        t.getType() == BillingTransaction.TransactionType.SUBSCRIPTION_PAYMENT)
        );
    }

    @Test
    void testHandleSubscriptionUpdated() {
        // Arrange
        String payload = "{ \"type\": \"customer.subscription.updated\" }";
        String signature = "test_signature";

        // Mock Event and Subscription
        Event event = Mockito.mock(Event.class);
        EventDataObjectDeserializer deserializer = Mockito.mock(EventDataObjectDeserializer.class);
        Subscription subscription = Mockito.mock(Subscription.class);

        // Configure event
        when(event.getType()).thenReturn("customer.subscription.updated");
        when(event.getId()).thenReturn("evt_123");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(subscription));

        // Setup subscription
        when(subscription.getId()).thenReturn("sub_123");
        when(subscription.getStatus()).thenReturn("active");
        when(subscription.getCancelAtPeriodEnd()).thenReturn(true); // Will be cancelled at end of period
        when(subscription.getCurrentPeriodEnd()).thenReturn(Instant.now().plusSeconds(3600 * 24 * 30).getEpochSecond());

        // Set the mocked event in our test handler
        webhookHandler.setMockedEvent(event);

        // Mock repository responses
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("stripeSubscriptionId", "sub_123");
        Business business = new Business(
                "business_123",
                "Test Business",
                "PRO",
                "active",
                Instant.now().minusSeconds(3600), // 1 hour ago
                Instant.now().plusSeconds(3600 * 24 * 30), // 30 days from now
                "pm_123",
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(3600),
                null,
                metadata,
                0L, 0L, 0L
        );

        when(userRepository.getBusinessBySubscriptionId("sub_123")).thenReturn(
                Uni.createFrom().item(Optional.of(business))
        );
        when(userRepository.saveBusiness(any())).thenReturn(
                Uni.createFrom().nullItem()
        );

        // Act
        Uni<Void> result = webhookHandler.handleWebhookEvent(payload, signature);
        result.await().indefinitely();

        // Assert
        verify(userRepository).getBusinessBySubscriptionId("sub_123");
        verify(userRepository).saveBusiness(any());
        // Verify that the business was updated with canceling status
        verify(userRepository).saveBusiness(
                argThat(b -> b.getSubscriptionStatus().equals("canceling") &&
                        b.getId().equals("business_123"))
        );
    }

    @Test
    void testHandleSubscriptionDeleted() {
        // Arrange
        String payload = "{ \"type\": \"customer.subscription.deleted\" }";
        String signature = "test_signature";

        // Mock Event and Subscription
        Event event = Mockito.mock(Event.class);
        EventDataObjectDeserializer deserializer = Mockito.mock(EventDataObjectDeserializer.class);
        Subscription subscription = Mockito.mock(Subscription.class);

        // Configure event
        when(event.getType()).thenReturn("customer.subscription.deleted");
        when(event.getId()).thenReturn("evt_123");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(subscription));

        // Setup subscription
        when(subscription.getId()).thenReturn("sub_123");

        // Set the mocked event in our test handler
        webhookHandler.setMockedEvent(event);

        // Mock repository responses
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("stripeSubscriptionId", "sub_123");
        Business business = new Business(
                "business_123",
                "Test Business",
                "PRO",
                "canceling",
                Instant.now().minusSeconds(3600), // 1 hour ago
                Instant.now().plusSeconds(3600 * 24 * 30), // 30 days from now
                "pm_123",
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(3600),
                null,
                metadata,
                0L, 0L, 0L
        );

        when(userRepository.getBusinessBySubscriptionId("sub_123")).thenReturn(
                Uni.createFrom().item(Optional.of(business))
        );
        when(userRepository.saveBusiness(any())).thenReturn(
                Uni.createFrom().nullItem()
        );

        // Act
        Uni<Void> result = webhookHandler.handleWebhookEvent(payload, signature);
        result.await().indefinitely();

        // Assert
        verify(userRepository).getBusinessBySubscriptionId("sub_123");
        verify(userRepository).saveBusiness(any());
        // Verify that the business was downgraded to FREE tier with canceled status
        verify(userRepository).saveBusiness(
                argThat(b -> b.getSubscriptionPlan().equals("FREE") &&
                        b.getSubscriptionStatus().equals("canceled") &&
                        b.getId().equals("business_123"))
        );
    }

    @Test
    void testInvalidSignature() {
        // Arrange
        String payload = "{ \"type\": \"payment_intent.succeeded\" }";
        String signature = "invalid_signature";

        // Configure our test handler to throw SignatureVerificationException
        StripeWebhookHandler realHandler = new StripeWebhookHandler(
                billingRepository,
                userRepository,
                webhookSecret
        ) {
            @Override
            protected Event verifyAndParseEvent(String payload, String sigHeader) throws SignatureVerificationException {
                throw new SignatureVerificationException("Invalid signature", signature);
            }
        };

        // Act & Assert
        Uni<Void> result = realHandler.handleWebhookEvent(payload, signature);

        // Verify the Uni fails with the right exception type
        AppException exception = assertThrows(AppException.class, () -> result.await().indefinitely());
        assertEquals(AppException.ErrorType.AUTHENTICATION_ERROR, exception.getErrorType());
    }

    @Test
    void testUnhandledEventType() {
        // Arrange
        String payload = "{ \"type\": \"charge.succeeded\" }";
        String signature = "test_signature";

        // Mock Event
        Event event = Mockito.mock(Event.class);

        // Configure event
        when(event.getType()).thenReturn("charge.succeeded");
        when(event.getId()).thenReturn("evt_123");

        // Set the mocked event in our test handler
        webhookHandler.setMockedEvent(event);

        // Act
        Uni<Void> result = webhookHandler.handleWebhookEvent(payload, signature);
        result.await().indefinitely();

        // Assert - no repository interactions should happen
        verifyNoInteractions(billingRepository);
        verifyNoInteractions(userRepository);
    }
}
