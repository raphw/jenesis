package build.jenesis.maven;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;

public class MavenModuleResolver implements Resolver {

    private final String mavenPrefix;
    private final MavenPomResolver delegate;
    private final transient Repository discovery;

    public MavenModuleResolver(String mavenPrefix, MavenPomResolver delegate, Repository discovery) {
        this.mavenPrefix = mavenPrefix;
        this.delegate = delegate;
        this.discovery = discovery;
    }

    @Override
    public SequencedMap<String, String> dependencies(Executor executor,
                                                     String prefix,
                                                     Map<String, Repository> repositories,
                                                     SequencedMap<String, SequencedSet<String>> coordinates,
                                                     SequencedMap<String, String> versions,
                                                     boolean compile) throws IOException {
        coordinates.forEach((coordinate, exclusions) -> {
            if (!exclusions.isEmpty()) {
                throw new IllegalArgumentException(
                        "Module system does not support exclusions, but " + coordinate + " declares " + exclusions);
            }
        });
        List<MavenPomResolver.RootPom> rootPoms = new ArrayList<>();
        for (String coordinate : coordinates.sequencedKeySet()) {
            String pinned = versions.get(coordinate);
            String fetchCoord;
            String checksum;
            if (pinned == null || pinned.isEmpty()) {
                fetchCoord = coordinate + ":pom";
                checksum = null;
            } else {
                int space = pinned.indexOf(' ');
                String version = space < 0 ? pinned : pinned.substring(0, space);
                checksum = space < 0 ? null : pinned.substring(space + 1).trim();
                fetchCoord = coordinate + "/" + version + ":pom";
            }
            RepositoryItem item = discovery.fetch(executor, fetchCoord)
                    .orElseThrow(() -> new IllegalArgumentException("No POM found for " + coordinate));
            rootPoms.add(new MavenPomResolver.RootPom(item.toInputStream(), checksum));
        }
        MavenRepository mavenRepo = MavenRepository.of(repositories.getOrDefault(mavenPrefix, Repository.empty()));
        SequencedMap<String, String> result = new LinkedHashMap<>();
        delegate.dependencies(executor, mavenRepo, rootPoms, MavenDependencyScope.COMPILE)
                .forEach((key, value) -> result.put(
                        key.coordinate(mavenPrefix, value.version()),
                        value.checksum() == null ? "" : value.checksum()));
        return result;
    }
}
