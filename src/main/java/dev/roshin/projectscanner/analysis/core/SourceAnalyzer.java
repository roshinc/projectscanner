package dev.roshin.projectscanner.analysis.core;

import dev.roshin.projectscanner.analysis.config.AnalysisConfig;
import dev.roshin.projectscanner.analysis.model.*;
import dev.roshin.projectscanner.analysis.pom.PomAnalyzer;
import dev.roshin.projectscanner.analysis.spoon.UsageDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.support.compiler.SpoonPom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Main entry point for static code analysis of Maven projects.
 * Uses Spoon for AST parsing and analysis.
 */
public class SourceAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(SourceAnalyzer.class);

    private static final String POM_FILE = "pom.xml";
    private static final String SRC_MAIN_JAVA = "src/main/java";

    private final PomAnalyzer pomAnalyzer;

    public SourceAnalyzer() {
        this.pomAnalyzer = new PomAnalyzer();
    }

    /**
     * Performs static code analysis on the given Maven project.
     *
     * @param projectRoot Root directory of the Maven project to analyze
     * @param config      Analysis configuration parameters
     * @return Complete analysis report with detected usages and call chains
     * @throws IllegalArgumentException      if project structure is invalid
     * @throws UnsupportedOperationException if multi-module project detected
     * @throws RuntimeException              if project doesn't compile or other critical errors
     */
    public AnalysisReport getReport(Path projectRoot, AnalysisConfig config) {
        log.info("Starting analysis of project: {}", projectRoot);
        LocalDateTime startTime = LocalDateTime.now();
        long startMs = System.currentTimeMillis();

        // Phase 2 Validation and Setup
        validateProject(projectRoot);
        checkSingleModule(projectRoot);

        // Extract dependency information from POM
        PomAnalyzer.DependencyAnalysis pomAnalysis = pomAnalyzer.scanPom(projectRoot);
        Set<String> functionIds = extractFunctionIds(pomAnalysis);
        Set<String> serviceIds = extractServiceIds(pomAnalysis);

        log.info("Found {} function dependencies and {} service dependencies in pom.xml",
                functionIds.size(), serviceIds.size());

        // Build Spoon model
        CtModel model;
        List<String> warnings = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();

        try {
            model = buildSpoonModel(projectRoot, warnings, skippedFiles);
        } catch (Exception e) {
            log.error("Failed to build Spoon model", e);
            return createErrorReport(projectRoot, startTime, startMs,
                    "Failed to parse project: " + e.getMessage());
        }

        // Phase 3: Detect usages
        UsageDetector detector = new UsageDetector(model, config);

        List<FunctionClientUsage> functionClientUsages;
        List<ServiceUsage> serviceUsages;
        List<EdaPublishUsage> edaPublishUsages;

        try {
            functionClientUsages = detector.detectFunctionClientUsages(functionIds);
            serviceUsages = detector.detectServiceUsages(serviceIds);
            edaPublishUsages = detector.detectEdaPublishUsages();
        } catch (Exception e) {
            log.error("Failed to detect usages", e);
            warnings.add("Usage detection partially failed: " + e.getMessage());
            // Use empty lists for failed detections
            functionClientUsages = List.of();
            serviceUsages = List.of();
            edaPublishUsages = List.of();
        }

        // Gather metadata
        AnalysisMetadata metadata = buildMetadata(
                projectRoot, model, warnings, skippedFiles, startMs,
                functionClientUsages, serviceUsages, edaPublishUsages
        );

        log.info("Analysis completed: {}", metadata);

        return new AnalysisReport(
                projectRoot,
                startTime,
                functionClientUsages,
                serviceUsages,
                edaPublishUsages,
                metadata
        );
    }

    /**
     * Validates basic project structure requirements.
     */
    private void validateProject(Path projectRoot) {
        if (!Files.exists(projectRoot)) {
            throw new IllegalArgumentException("Project path does not exist: " + projectRoot);
        }

        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Project path is not a directory: " + projectRoot);
        }

        Path pomFile = projectRoot.resolve(POM_FILE);
        if (!Files.exists(pomFile)) {
            log.warn("No pom.xml found at: {}", pomFile);
            throw new IllegalArgumentException("No pom.xml found in project root");
        }

        Path srcPath = projectRoot.resolve(SRC_MAIN_JAVA);
        if (!Files.exists(srcPath)) {
            log.warn("No src/main/java directory found at: {}", srcPath);
            throw new IllegalArgumentException("No src/main/java directory found");
        }

        log.debug("Project validation passed: {}", projectRoot);
    }

    /**
     * Checks if this is a single-module project (multi-module not supported).
     */
    private void checkSingleModule(Path projectRoot) {
        try (Stream<Path> files = Files.list(projectRoot)) {
            boolean hasSubModules = files
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("src"))
                    .filter(p -> !p.getFileName().toString().equals("target"))
                    .anyMatch(p -> Files.exists(p.resolve(POM_FILE)));

            if (hasSubModules) {
                throw new UnsupportedOperationException(
                        "Multi-module Maven projects are not supported. " +
                                "Please analyze each module separately."
                );
            }
        } catch (IOException e) {
            log.warn("Could not check for multi-module project structure", e);
            // Continue anyway - validation will catch major issues
        }
    }

    /**
     * Builds the Spoon AST model for the project.
     */
    private CtModel buildSpoonModel(Path projectRoot, List<String> warnings,
                                    List<String> skippedFiles) {
        log.info("Building Spoon model for: {}", projectRoot);

        Launcher launcher = new Launcher();

        // Configure for Java 21
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.getEnvironment().setNoClasspath(true); // Work without full classpath
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);

        // Only analyze src/main/java
        Path srcPath = projectRoot.resolve(SRC_MAIN_JAVA);
        launcher.addInputResource(srcPath.toString());

        // Configure error handling
        launcher.getEnvironment().setShouldCompile(false);
        launcher.getEnvironment().setCommentEnabled(false); // Skip comments for performance

        // Set up source classpath from Maven
        try {
            configureMavenClasspath(launcher, projectRoot);
        } catch (Exception e) {
            log.warn("Could not configure Maven classpath, continuing with noclasspath mode", e);
            warnings.add("Maven classpath configuration failed: " + e.getMessage());
        }

        // Build the model
        try {
            CtModel model = launcher.buildModel();
            log.info("Spoon model built successfully. Root package: {}",
                    model.getRootPackage().getQualifiedName());

            int fileCount = countJavaFiles(srcPath);
            int classCount = model.getAllTypes().size();
            log.info("Parsed {} files, {} classes", fileCount, classCount);

            return model;
        } catch (Exception e) {
            log.error("Failed to build Spoon model", e);
            throw new RuntimeException("Project compilation/parsing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Attempts to configure Maven classpath for better type resolution.
     */
    private void configureMavenClasspath(Launcher launcher, Path projectRoot) {
        try {
            Path pomPath = projectRoot.resolve(POM_FILE);
            SpoonPom spoonPom = new SpoonPom(
                    pomPath.toString(),
                    null,
                    launcher.getEnvironment()
            );

            // Add source directories
            for (java.io.File srcDir : spoonPom.getSourceDirectories()) {
                if (srcDir.exists()) {
                    launcher.addInputResource(srcDir.getAbsolutePath());
                }
            }

            log.debug("Maven classpath configured from pom.xml");
        } catch (Exception e) {
            log.debug("SpoonPom configuration skipped: {}", e.getMessage());
            // Not critical - noclasspath mode will work
        }
    }

    /**
     * Counts Java source files in the given directory.
     */
    private int countJavaFiles(Path srcPath) {
        try (Stream<Path> files = Files.walk(srcPath)) {
            return (int) files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .count();
        } catch (IOException e) {
            log.warn("Could not count Java files", e);
            return 0;
        }
    }

    /**
     * Extracts function IDs from POM analysis.
     */
    private Set<String> extractFunctionIds(PomAnalyzer.DependencyAnalysis analysis) {
        return analysis.functionIds().stream()
                .map(PomAnalyzer.DependantEntry::nameOfNote)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Extracts service IDs from POM analysis.
     */
    private Set<String> extractServiceIds(PomAnalyzer.DependencyAnalysis analysis) {
        return analysis.serviceIds().stream()
                .map(PomAnalyzer.DependantEntry::nameOfNote)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Builds analysis metadata from collected information.
     */
    private AnalysisMetadata buildMetadata(
            Path projectRoot,
            CtModel model,
            List<String> warnings,
            List<String> skippedFiles,
            long startMs,
            List<FunctionClientUsage> functionUsages,
            List<ServiceUsage> serviceUsages,
            List<EdaPublishUsage> edaUsages) {


        Path srcPath = projectRoot.resolve(SRC_MAIN_JAVA);
        int totalFiles = countJavaFiles(srcPath);
        int totalClasses = model.getAllTypes().size();
        int totalMethods = model.getAllTypes().stream()
                .mapToInt(type -> type.getMethods().size())
                .sum();

        Map<String, Integer> usageCounts = new HashMap<>();
        usageCounts.put("functionClients", functionUsages.size());
        usageCounts.put("services", serviceUsages.size());
        usageCounts.put("edaPublish", edaUsages.size());

        Duration analysisTime = Duration.ofMillis(System.currentTimeMillis() - startMs);

        return new AnalysisMetadata(
                totalFiles,
                totalClasses,
                totalMethods,
                List.copyOf(warnings),
                Map.copyOf(usageCounts),
                analysisTime,
                false, // hasCircularReferences - Phase 4 will populate
                false, // hadErrors
                List.copyOf(skippedFiles)
        );
    }

    /**
     * Creates an error report when analysis fails.
     */
    private AnalysisReport createErrorReport(Path projectRoot, LocalDateTime startTime,
                                             long startMs, String errorMessage) {
        List<String> warnings = new ArrayList<>();
        warnings.add("CRITICAL: " + errorMessage);

        AnalysisMetadata metadata = new AnalysisMetadata(
                0, 0, 0,
                warnings,
                Map.of(),
                Duration.ofMillis(System.currentTimeMillis() - startMs),
                false,
                true, // hadErrors
                List.of()
        );

        return new AnalysisReport(
                projectRoot,
                startTime,
                List.of(),
                List.of(),
                List.of(),
                metadata
        );
    }
}