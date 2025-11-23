package dev.roshin.projectscanner.analysis.model.enums;

/**
 * Indicates whether a topic name in an EDA publish call could be resolved to a string literal.
 */
public enum TopicResolutionStatus {
    /**
     * Topic name was successfully extracted as a string literal
     */
    RESOLVED,

    /**
     * Topic name is a variable but couldn't be resolved to a value
     */
    UNKNOWN_VARIABLE,

    /**
     * Topic name is a complex expression that couldn't be resolved
     */
    UNKNOWN_COMPLEX
}
