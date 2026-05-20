package build.jenesis.maven;

import module java.base;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Placement;

public class MavenRepositoryPlacement implements Placement {

    @Override
    public Optional<Path> apply(Path file,
                                SequencedProperties module,
                                SequencedProperties metadata) throws IOException {
        boolean test = module.getProperty("tests") != null;
        String suffix = switch (file.getFileName().toString()) {
            case "classes.jar" -> test ? "-tests.jar" : ".jar";
            case "sources.jar" -> test ? "-tests-sources.jar" : "-sources.jar";
            case "javadoc.jar" -> test ? "-tests-javadoc.jar" : "-javadoc.jar";
            case "pom.xml" -> test ? null : ".pom";
            default -> null;
        };
        if (suffix == null) {
            return Optional.empty();
        }
        if (!Files.exists(file.getParent().resolve("pom.xml"))) {
            return Optional.empty();
        }
        String groupId = metadata.getProperty("project");
        String artifactId = metadata.getProperty("artifact");
        String version = metadata.getProperty("version");
        if (groupId == null || artifactId == null || version == null) {
            throw new IllegalStateException(
                    "Missing maven coordinates in metadata.properties for "
                            + file
                            + " (expected 'project', 'artifact' and 'version'; got project="
                            + groupId
                            + ", artifact="
                            + artifactId
                            + ", version="
                            + version
                            + ")");
        }
        return Optional.of(Path.of(
                groupId.replace('.', '/'),
                artifactId,
                version,
                artifactId + "-" + version + suffix));
    }
}
