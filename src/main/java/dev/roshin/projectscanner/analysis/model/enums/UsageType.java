package dev.roshin.projectscanner.analysis.model.enums;

/**
 * Categorizes different types of service usage patterns in the analyzed code.
 */
public enum UsageType {
    /**
     * Direct instantiation: new ServiceClass()
     */
    INSTANTIATION,

    /**
     * Instance method call: instance.method()
     */
    METHOD_CALL,

    /**
     * Constructor invocation
     */
    CONSTRUCTOR,

    /**
     * Static method call: ServiceClass.staticMethod()
     */
    STATIC_METHOD
}
