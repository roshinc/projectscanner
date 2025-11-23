package dev.roshin.projectscanner.analysis.config;

/**
 * Configuration parameters for the static code analysis.
 *
 * @param maxDepth Maximum depth for call chain traversal.
 *                 Default: 100, -1 for unlimited
 */
public record AnalysisConfig(
        int maxDepth
) {
    /**
     * Default configuration with maxDepth of 100.
     */
    public static final AnalysisConfig DEFAULT = new AnalysisConfig(100);

    /**
     * Configuration with unlimited depth traversal.
     */
    public static final AnalysisConfig UNLIMITED = new AnalysisConfig(-1);

    /**
     * Creates a config with validation.
     */
    public AnalysisConfig {
        if (maxDepth < -1 || maxDepth == 0) {
            throw new IllegalArgumentException(
                    "maxDepth must be positive or -1 for unlimited, got: " + maxDepth
            );
        }
    }

    /**
     * Creates a config with the specified max depth.
     */
    public static AnalysisConfig withMaxDepth(int depth) {
        return new AnalysisConfig(depth);
    }

    /**
     * Returns true if depth limit is unlimited.
     */
    public boolean isUnlimited() {
        return maxDepth == -1;
    }
}