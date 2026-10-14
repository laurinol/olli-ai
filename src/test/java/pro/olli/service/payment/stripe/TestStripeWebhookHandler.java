/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.service.payment.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import pro.olli.repository.BillingRepository;
import pro.olli.repository.UserRepository;

/**
 * Test double for StripeWebhookHandler that allows mocking the event verification.
 */
public class TestStripeWebhookHandler extends StripeWebhookHandler {

    private Event mockedEvent;

    public TestStripeWebhookHandler(
            BillingRepository billingRepository,
            UserRepository userRepository,
            String webhookSecret) {
        super(billingRepository, userRepository, webhookSecret);
    }

    /**
     * Set the mocked event to be returned by verifyAndParseEvent.
     *
     * @param event The event to return
     */
    public void setMockedEvent(Event event) {
        this.mockedEvent = event;
    }

    /**
     * Override the verification method to return the mocked event.
     * This avoids needing to use static mocking of Webhook.constructEvent.
     */
    @Override
    protected Event verifyAndParseEvent(String payload, String sigHeader) throws SignatureVerificationException, Exception {
        if (mockedEvent == null) {
            throw new IllegalStateException("Mocked event not set. Call setMockedEvent before using this handler in tests.");
        }
        return mockedEvent;
    }
}
