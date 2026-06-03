package build;

import module java.base;
import build.jenesis.Project;
import build.jenesis.RepositoryItem;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenRepository;

public class Demo {

    static void main(String[] args) throws Exception {
        // Stage the release bundle twice, into two independent target trees (both
        // kept under the gitignored target/ folder). Configuration is explicit on
        // the Project builder rather than -D flags: emit source and javadoc jars,
        // stamp the release version, and feed the POM metadata from project.properties.
        Files.createDirectories(Path.of("target"));
        Path first = stage("target/first");
        Path second = stage("target/second");

        // Reproducibility: the two builds shared no output, yet a content-hashed,
        // deterministic build produces byte-identical artifacts. Compare them.
        System.out.println();
        System.out.println("Reproducibility: SHA-256 of the staged artifacts from two independent builds:");
        boolean identical = true;
        try (Stream<Path> walk = Files.walk(first).filter(Files::isRegularFile).sorted()) {
            for (Path fileFirst : (Iterable<Path>) walk::iterator) {
                Path relative = first.relativize(fileFirst);
                Path fileSecond = second.resolve(relative);
                String hashFirst = sha256(fileFirst);
                String hashSecond = Files.isRegularFile(fileSecond) ? sha256(fileSecond) : "<missing>";
                boolean match = hashFirst.equals(hashSecond);
                identical &= match;
                System.out.println("  " + (match ? "[identical] " : "[DIFFERS]   ")
                        + hashFirst.substring(0, 16) + "  " + relative);
            }
        }
        System.out.println(identical ? "  -> both builds are bit-for-bit identical" : "  -> builds DIFFER");

        // The staged tree is itself a Maven repository layout. Recover the
        // coordinate from that layout (so nothing about it is hard-coded) and
        // resolve it straight back out to prove the bundle is a complete,
        // consumable Maven artifact - the same bytes an upload carries.
        Path pom;
        try (Stream<Path> walk = Files.walk(first)) {
            pom = walk.filter(file -> file.getFileName().toString().endsWith(".pom")).findFirst().orElseThrow();
        }
        Path versionDirectory = pom.getParent();
        String version = versionDirectory.getFileName().toString();
        Path artifactDirectory = versionDirectory.getParent();
        String artifactId = artifactDirectory.getFileName().toString();
        String groupId = first.relativize(artifactDirectory.getParent()).toString().replace(File.separatorChar, '.');

        MavenRepository repository = new MavenDefaultRepository(first.toUri(), null, Map.of(), _ -> {});
        System.out.println();
        System.out.println("Resolving " + groupId + ":" + artifactId + ":" + version + " from the staged repository:");
        report(repository, groupId, artifactId, version, "jar", null);
        report(repository, groupId, artifactId, version, "pom", null);
        report(repository, groupId, artifactId, version, "jar", "sources");
        report(repository, groupId, artifactId, version, "jar", "javadoc");
    }

    private static Path stage(String target) throws IOException {
        // build returns each stage step's output folder; the Maven staging step
        // is keyed "stage/maven" and its output is itself a Maven repository.
        return new Project()
                .target(Path.of(target))
                .sources(true)
                .documentation(true)
                .version("1.0.0")
                .metadata(Path.of("project.properties"))
                .build("stage")
                .get("stage/maven");
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
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
