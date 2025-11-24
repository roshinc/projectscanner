package dev.roshin.projectscanner.analysis.pom;

import com.google.common.collect.Sets;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.nio.file.Path;
import java.util.Set;

public class PomAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(PomAnalyzer.class);
    private static final String SERVICES_GROUP = "dev.myorg.services";
    private static final String FUNCTIONS_GROUP = "dev.myorg.functions";
    private static final String FUNC_CLIENT_SUFFIX = "-func-client";

    private Model model;

    public DependencyAnalysis scanPom(Path projectPath) {
        Path pomFile = projectPath.resolve("pom.xml");
        Set<DependantEntry> services = Sets.newHashSet();
        Set<DependantEntry> functions = Sets.newHashSet();

        if (!pomFile.toFile().exists()) {
            log.error("No pom.xml found at path: {}", projectPath);
            return new DependencyAnalysis(services, functions);
        }

        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (FileReader fileReader = new FileReader(pomFile.toFile())) {
            Model model = reader.read(fileReader);

            this.model = model;

            model.getDependencies().forEach(dep -> {
                if (SERVICES_GROUP.equals(dep.getGroupId())) {
                    // The artifact ID is the service ID
                    services.add(new DependantEntry(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getArtifactId()));
                    log.debug("Found Service ID: {}", dep.getArtifactId());
                } else if (FUNCTIONS_GROUP.equals(dep.getGroupId())
                        && dep.getArtifactId().endsWith(FUNC_CLIENT_SUFFIX)) {
                    // Extract function ID (prefix)
                    String artifactId = dep.getArtifactId();
                    String functionId = artifactId.substring(0, artifactId.length() - FUNC_CLIENT_SUFFIX.length());
                    functions.add(new DependantEntry(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), functionId));
                    log.debug("Found Function ID: {}", functionId);
                }
            });

        } catch (Exception e) {
            log.error("Failed to parse pom.xml", e);
        }

        return new DependencyAnalysis(services, functions);
    }

    public String getArtifactId() {
        if (model == null) throw new IllegalStateException("scanPom() must be called first.");
        return model.getArtifactId();
    }

    public record DependantEntry(String groupId, String artifactId, String version, String nameOfNote) {
    }

    public record DependencyAnalysis(Set<DependantEntry> serviceIds, Set<DependantEntry> functionIds) {
    }
}