/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.core.resilience.exception;

public class AppException extends RuntimeException {

    private final ErrorType errorType;

    public enum ErrorType {
        TRANSIENT_ERROR(true),      // Network glitches, timeouts
        AUTHENTICATION_ERROR(true), // Auth token expired
        AUTHORIZATION_ERROR(false), // Lack of permissions
        RESOURCE_ERROR(false),      // Missing resources
        VALIDATION_ERROR(false),    // Invalid input
        SYSTEM_ERROR(true),         // Internal server errors
        EXTERNAL_SERVICE_ERROR(true), // Third-party service failures
        DATA_ACCESS_ERROR(true);     // Data access errors

        private final boolean retryable;

        ErrorType(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    public AppException(String message, ErrorType errorType) {
        super(message);
        this.errorType = errorType;
    }

    public AppException(String message, Throwable cause, ErrorType errorType) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public boolean isRetryable() {
        return errorType.isRetryable();
    }
}
