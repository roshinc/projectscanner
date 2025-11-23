package dev.roshin.projectscanner.analysis.core;

import dev.roshin.projectscanner.analysis.config.AnalysisConfig;
import dev.roshin.projectscanner.analysis.model.AnalysisReport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SourceAnalyzerTest {

    private final Path PROJECT_PATH = Path.of("F:\\development\\work\\XTSTCLFNJ-dev\\XTSTCLFNJ");

    @Test
    void getReport() {
        SourceAnalyzer analyzer = new SourceAnalyzer();
        assertNotNull(analyzer.getReport(PROJECT_PATH, AnalysisConfig.UNLIMITED));
    }

    @Test
    public void getReport_cleaner() {
        SourceAnalyzer analyzer = new SourceAnalyzer();

        try {
            AnalysisReport report = analyzer.getReport(
                    PROJECT_PATH,
                    AnalysisConfig.DEFAULT
            );

            System.out.println("✓ Project validated");
            System.out.println("✓ Spoon model built");
            System.out.println("  Files: " + report.metadata().totalFilesScanned());
            System.out.println("  Classes: " + report.metadata().totalClassesAnalyzed());
            System.out.println("  Methods: " + report.metadata().totalMethodsAnalyzed());
            System.out.println("  Time: " + report.metadata().analysisTime());

        } catch (IllegalArgumentException e) {
            System.err.println("✗ Invalid project: " + e.getMessage());
        } catch (UnsupportedOperationException e) {
            System.err.println("✗ Multi-module not supported: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("✗ Analysis failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}