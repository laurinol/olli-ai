# Tracing Setup for Olli Backend

This directory contains configuration for the distributed tracing setup using OpenTelemetry and Jaeger.

## Viewing Traces

1. Start the development environment with docker-compose:
   ```
   cd /Users/kai/Source/olli-ai/backend/test
   docker-compose up -d
   ```

2. Run the application with OpenTelemetry enabled:
   ```
   cd /Users/kai/Source/olli-ai/backend
   mvn quarkus:dev
   ```

3. Access the Jaeger UI at: http://localhost:16686

4. Make requests to your application to generate traces

5. In the Jaeger UI:
   - Select "olli-backend" from the service dropdown
   - Click "Find Traces" to see all traces
   - Click on any trace to explore it in detail

## Understanding Trace Data

- **Trace**: A complete request flow through the system, contains one or more spans
- **Span**: A unit of work within a trace (e.g., an API call, a database query)
- **Attributes**: Key-value information attached to spans (e.g., user ID, business ID)
- **Events**: Time-stamped markers within a span (e.g., "query_started", "task_enqueued")

## Injecting Context Manually

For asynchronous operations or message passing between services, use the `TracingService`:

```java
// Create a new span
tracingService.withSpan("operation-name", () -> {
    // Your operation code here
    return result;
}, attributes);

// Propagate context across async boundaries
Function<Input, Output> wrappedOperation = tracingService.withContext(originalOperation);
```