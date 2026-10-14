/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.service.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.service.payment.stripe.StripePaymentProvider;

/**
 * Factory for creating payment provider instances based on configuration.
 */
@ApplicationScoped
public class PaymentProviderFactory {

    private static final Logger LOG = Logger.getLogger(PaymentProviderFactory.class);

    private final String paymentProvider;
    private final StripePaymentProvider stripePaymentProvider;

    @Inject
    public PaymentProviderFactory(
            @ConfigProperty(name = "payment.provider", defaultValue = "stripe") String paymentProvider,
            StripePaymentProvider stripePaymentProvider) {
        this.paymentProvider = paymentProvider;
        this.stripePaymentProvider = stripePaymentProvider;

        LOG.infof("PaymentProviderFactory initialized with provider: %s", paymentProvider);
    }

    /**
     * Gets the appropriate payment provider based on configuration.
     *
     * @return The configured payment provider
     */
    public PaymentProvider getPaymentProvider() {
        switch (paymentProvider.toLowerCase()) {
            case "stripe":
                return stripePaymentProvider;
            // Add other providers as they become available
            default:
                LOG.warnf("Unknown payment provider: %s, falling back to Stripe", paymentProvider);
                return stripePaymentProvider;
        }
    }
}
