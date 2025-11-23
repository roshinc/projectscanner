package dev.roshin.projectscanner.analysis.model;

/**
 * Captures the precise location in source code where a usage or call occurs.
 *
 * @param filePath   Relative path to the source file from project root
 * @param className  Fully qualified class name
 * @param methodName Name of the method containing the usage
 * @param lineNumber Line number in the source file
 */
public record SourceLocation(
        String filePath,
        String className,
        String methodName,
        int lineNumber
) {
    @Override
    public String toString() {
        return String.format("%s.%s() [%s:%d]", className, methodName, filePath, lineNumber);
    }
}
