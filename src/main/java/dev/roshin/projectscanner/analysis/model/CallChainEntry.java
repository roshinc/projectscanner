package dev.roshin.projectscanner.analysis.model;

import dev.roshin.projectscanner.analysis.model.enums.Visibility;

/**
 * Represents one entry in a call chain, tracking the path from a usage location
 * up to the highest reachable public method (entry point).
 *
 * @param className       Fully qualified class name
 * @param methodSignature Complete method signature including parameter types
 * @param visibility      Method visibility modifier
 * @param lineNumber      Line number where this method is defined
 * @param isEntryPoint    True if this is the highest public method in the call chain
 */
public record CallChainEntry(
        String className,
        String methodSignature,
        Visibility visibility,
        int lineNumber,
        boolean isEntryPoint
) {
    @Override
    public String toString() {
        String entryPoint = isEntryPoint ? " [ENTRY POINT]" : "";
        return String.format("%s.%s [%s, line %d]%s",
                className, methodSignature, visibility, lineNumber, entryPoint);
    }
}
