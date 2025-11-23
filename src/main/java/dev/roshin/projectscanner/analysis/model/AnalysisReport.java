package dev.roshin.projectscanner.analysis.model;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The complete output of a static code analysis run.
 * Contains all detected usages, call chains, and metadata.
 *
 * @param projectPath          The root path of the analyzed Maven project
 * @param analysisTimestamp    When the analysis was performed
 * @param functionClientUsages All detected function client invocations
 * @param serviceUsages        All detected service dependency usages
 * @param edaPublishUsages     All detected EDA publish calls
 * @param metadata             Statistics, warnings, and diagnostic information
 */
public record AnalysisReport(
        Path projectPath,
        LocalDateTime analysisTimestamp,
        List<FunctionClientUsage> functionClientUsages,
        List<ServiceUsage> serviceUsages,
        List<EdaPublishUsage> edaPublishUsages,
        AnalysisMetadata metadata
) {
    /**
     * Returns the total count of all detected usages across all categories.
     */
    public int totalUsages() {
        return functionClientUsages.size() + serviceUsages.size() + edaPublishUsages.size();
    }

    @Override
    public String toString() {
        return String.format(
                "AnalysisReport[project=%s, timestamp=%s, total=%d usages (%d functions, %d services, %d eda)]",
                projectPath.getFileName(), analysisTimestamp, totalUsages(),
                functionClientUsages.size(), serviceUsages.size(), edaPublishUsages.size()
        );
    }
}