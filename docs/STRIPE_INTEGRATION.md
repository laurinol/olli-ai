# Stripe Integration

This document describes the Stripe integration for the Olli AI billing system.

## Overview

The Stripe integration allows Olli AI to process payments, manage subscriptions, and handle billing for businesses. It consists of:

1. **Webhook Handler**: Processes events from Stripe (payments, subscription changes)
2. **Billing Service**: Manages billing operations and interacts with the Stripe API
3. **Repository Layer**: Stores transaction records and business subscription details

## Configuration

### Required Environment Variables

```
STRIPE_API_KEY=sk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_PUBLIC_KEY=pk_test_...
```

These can also be configured in `application.properties`:

```properties
stripe.api.key=${STRIPE_API_KEY:}
stripe.webhook.secret=${STRIPE_WEBHOOK_SECRET:}
stripe.public.key=${STRIPE_PUBLIC_KEY:}
```

## Webhook Events

The system handles the following Stripe webhook events:

| Event Type | Description | Action |
|------------|-------------|--------|
| `payment_intent.succeeded` | Payment was successful | Update transaction status to COMPLETED |
| `payment_intent.payment_failed` | Payment failed | Update transaction status to FAILED |
| `invoice.payment_succeeded` | Subscription invoice paid | Create a new COMPLETED transaction record |
| `invoice.payment_failed` | Subscription invoice payment failed | Create a new FAILED transaction record |
| `customer.subscription.updated` | Subscription details changed | Update business subscription details |
| `customer.subscription.deleted` | Subscription canceled | Downgrade business to FREE tier |

## Testing

### Unit Tests

Run unit tests for the Stripe webhook handler:

```bash
mvn test -Dtest=StripeWebhookHandlerTest
```

### Manual Testing

The system includes a script for testing webhook events manually:

```bash
# Send a payment_intent.succeeded event
./test/test-stripe-webhook.sh -e payment_intent.succeeded

# Help for more options
./test/test-stripe-webhook.sh --help
```

## Security Considerations

1. **Webhook Signature Verification**: All webhook events are verified using the webhook secret
2. **Error Handling**: All errors are logged but not returned to Stripe to prevent event retries
3. **Idempotency**: Events with the same payment intent or subscription ID are processed idempotently

## Implementation Details

### StripeWebhookHandler

The `StripeWebhookHandler` class is responsible for processing webhooks from Stripe:

1. Verifies the webhook signature using `verifyAndParseEvent` method
2. Processes the event based on its type
3. Updates database records in the appropriate repository
4. Handles errors gracefully with appropriate recovery strategies

### Business Subscription Statuses

| Status | Description |
|--------|-------------|
| `active` | Subscription is active and will renew automatically |
| `canceling` | Subscription is active but set to cancel at period end |
| `canceled` | Subscription has been canceled and is no longer active |

### Transaction Processing

1. For payment intents, the system looks up existing transaction records by external ID
2. For invoices, the system first finds the business by subscription ID
3. Transactions are updated or created with appropriate status and metadata

## Local Development

For local development with Stripe webhooks, use the Stripe CLI:

```bash
# Install Stripe CLI (macOS)
brew install stripe/stripe-cli/stripe

# Forward events to your local environment
stripe listen --forward-to http://localhost:8080/stripe/webhook
```