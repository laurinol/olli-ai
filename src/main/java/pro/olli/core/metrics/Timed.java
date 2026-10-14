/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.core.metrics;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for methods that should be timed and have metrics recorded.
 * Use this on repository methods, business logic, or any operation where
 * you want to track performance.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Timed {
    /**
     * The name of the metric. If not provided, the class and method name will be used.
     */
    @Nonbinding
    String value() default "";

    /**
     * The type of operation being performed. This helps categorize metrics.
     */
    @Nonbinding
    OperationType type() default OperationType.OTHER;

    /**
     * Types of operations that can be timed.
     */
    enum OperationType {
        DATABASE,
        CONNECTOR,
        AI_SERVICE,
        API_CALL,
        EXTERNAL_SERVICE,
        SYNC_TASK,
        OTHER
    }
}
