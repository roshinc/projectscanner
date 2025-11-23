package dev.roshin.projectscanner.analysis.model;

import java.util.List;

/**
 * Represents a detected usage of a function client in the analyzed codebase.
 * Captures function invocations (execute, executeAsync, executeAsyncOnOrAfter).
 *
 * @param functionId           The identifier of the function being invoked
 * @param methodName           The method called: "execute", "executeAsync", or "executeAsyncOnOrAfter"
 * @param usageLocation        Where in the code this invocation occurs
 * @param callChain            The complete call chain from usage to the highest public method (entry point)
 * @param inputType            The type of the input parameter (String or model class name)
 * @param hasScheduling        Whether this is a scheduled async execution
 * @param schedulingExpression The LocalDateTime expression if detectable, null otherwise
 */
public record FunctionClientUsage(
        String functionId,
        String methodName,
        SourceLocation usageLocation,
        List<CallChainEntry> callChain,
        String inputType,
        boolean hasScheduling,
        String schedulingExpression
) {
    @Override
    public String toString() {
        return String.format("FunctionClient[%s.%s(%s)] at %s",
                functionId, methodName, inputType, usageLocation);
    }
}
