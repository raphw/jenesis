package build;

import module java.base;
import build.jenesis.Project;
import build.jenesis.RepositoryItem;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenRepository;

public class Demo {

    static void main(String[] args) throws Exception {
        // Configure publication explicitly on the Project builder rather than
        // through -D flags: emit source and javadoc jars, stamp the release
        // version, and feed the descriptive POM metadata from project.properties.
        // Then stage the release tree under target/stage/maven/output.
        SequencedMap<String, Path> outputs = new Project()
                .sources(true)
                .documentation(true)
                .version("1.0.0")
                .metadata(Path.of("project.properties"))
                .build("stage");

        // build returns each stage step's output folder; the Maven staging step
        // is keyed "stage/maven" and its output is itself a Maven repository
        // layout. Recover the coordinate from that layout (so nothing about it is
        // hard-coded) and resolve it straight back out to prove the bundle is a
        // complete, consumable Maven artifact - the same bytes an upload carries.
        Path output = outputs.get("stage/maven");
        Path pom;
        try (Stream<Path> walk = Files.walk(output)) {
            pom = walk.filter(file -> file.getFileName().toString().endsWith(".pom")).findFirst().orElseThrow();
        }
        Path versionDirectory = pom.getParent();
        String version = versionDirectory.getFileName().toString();
        Path artifactDirectory = versionDirectory.getParent();
        String artifactId = artifactDirectory.getFileName().toString();
        String groupId = output.relativize(artifactDirectory.getParent()).toString().replace(File.separatorChar, '.');

        MavenRepository repository = new MavenDefaultRepository(output.toUri(), null, Map.of(), _ -> {});
        System.out.println();
        System.out.println("Resolving " + groupId + ":" + artifactId + ":" + version + " from the staged repository:");
        report(repository, groupId, artifactId, version, "jar", null);
        report(repository, groupId, artifactId, version, "pom", null);
        report(repository, groupId, artifactId, version, "jar", "sources");
        report(repository, groupId, artifactId, version, "jar", "javadoc");
    }

    private static void report(MavenRepository repository,
                               String groupId,
                               String artifactId,
                               String version,
                               String type,
                               String classifier) throws IOException {
        Optional<RepositoryItem> item = repository.fetch(Runnable::run,
                groupId, artifactId, version, type, classifier, null);
        String name = artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + "." + type;
        System.out.println("  " + (item.isPresent() ? "[resolved] " : "[MISSING]  ") + name);
    }
}
