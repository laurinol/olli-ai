#!/bin/bash
# Test script for Stripe webhook handling

# Check if we have the required stripe CLI
if ! command -v stripe &> /dev/null
then
    echo "Stripe CLI not found. Please install it first:"
    echo "https://stripe.com/docs/stripe-cli"
    exit 1
fi

# Defaults
WEBHOOK_URL="http://localhost:8080/stripe/webhook"
EVENT_TYPE="payment_intent.succeeded"

# Parse arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        -e|--event) EVENT_TYPE="$2"; shift ;;
        -u|--url) WEBHOOK_URL="$2"; shift ;;
        -h|--help) 
            echo "Usage: $0 [-e|--event EVENT_TYPE] [-u|--url WEBHOOK_URL]"
            echo ""
            echo "Options:"
            echo "  -e, --event EVENT_TYPE    Stripe event type to send (default: payment_intent.succeeded)"
            echo "  -u, --url WEBHOOK_URL     URL to send the webhook to (default: http://localhost:8080/stripe/webhook)"
            echo "  -h, --help                Show this help message"
            echo ""
            echo "Supported event types:"
            echo "  - payment_intent.succeeded"
            echo "  - payment_intent.payment_failed"
            echo "  - invoice.payment_succeeded"
            echo "  - invoice.payment_failed"
            echo "  - customer.subscription.updated"
            echo "  - customer.subscription.deleted"
            exit 0
            ;;
        *) echo "Unknown parameter: $1"; exit 1 ;;
    esac
    shift
done

echo "Sending $EVENT_TYPE webhook event to $WEBHOOK_URL..."

# Use the Stripe CLI to trigger a webhook event
stripe trigger $EVENT_TYPE --webhook-url=$WEBHOOK_URL

echo "Done!"