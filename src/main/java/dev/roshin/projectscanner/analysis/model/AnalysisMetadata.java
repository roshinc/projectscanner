package dev.roshin.projectscanner.analysis.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Contains metadata and statistics about the analysis run.
 * Provides diagnostic information, warnings, and summary counts.
 *
 * @param totalFilesScanned     Number of Java source files processed
 * @param totalClassesAnalyzed  Number of classes analyzed
 * @param totalMethodsAnalyzed  Number of methods analyzed
 * @param warnings              List of warnings generated during analysis (e.g., circular references)
 * @param usageCounts           Quick summary counts by category (e.g., "functionClients": 15)
 * @param analysisTime          Duration of the analysis
 * @param hasCircularReferences Whether any circular call chains were detected
 * @param hadErrors             Whether any errors occurred during analysis
 * @param skippedFiles          List of files that couldn't be parsed or analyzed
 */
public record AnalysisMetadata(
        int totalFilesScanned,
        int totalClassesAnalyzed,
        int totalMethodsAnalyzed,
        List<String> warnings,
        Map<String, Integer> usageCounts,
        Duration analysisTime,
        boolean hasCircularReferences,
        boolean hadErrors,
        List<String> skippedFiles
) {
    @Override
    public String toString() {
        return String.format(
                "Analysis: %d files, %d classes, %d methods in %s. " +
                        "Usages: %s. Warnings: %d, Errors: %s",
                totalFilesScanned, totalClassesAnalyzed, totalMethodsAnalyzed,
                analysisTime, usageCounts, warnings.size(), hadErrors
        );
    }
}
