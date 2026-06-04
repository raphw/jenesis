package build.jenesis.maven;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.ResolutionListener;
import build.jenesis.Resolver;

public class MavenModuleResolver implements Resolver {

    private final String mavenPrefix;
    private final MavenResolver delegate;
    private final transient Repository discovery;

    public MavenModuleResolver(String mavenPrefix, MavenResolver delegate, Repository discovery) {
        this.mavenPrefix = mavenPrefix;
        this.delegate = delegate;
        this.discovery = discovery;
    }

    @Override
    public SequencedSet<String> managedPrefixes() {
        return new LinkedHashSet<>(Set.of(mavenPrefix));
    }

    @Override
    public SequencedMap<String, String> dependencies(Executor executor,
                                                     String prefix,
                                                     Map<String, Repository> repositories,
                                                     SequencedMap<String, SequencedSet<String>> coordinates,
                                                     SequencedMap<String, String> versions,
                                                     boolean compile,
                                                     ResolutionListener listener) throws IOException {
        coordinates.forEach((coordinate, exclusions) -> {
            if (!exclusions.isEmpty()) {
                throw new IllegalArgumentException(
                        "Module system does not support exclusions, but " + coordinate + " declares " + exclusions);
            }
        });
        Repository repository = repositories.getOrDefault(Resolver.base(prefix), discovery);
        List<MavenResolver.RootPom> rootPoms = new ArrayList<>();
        for (String coordinate : coordinates.sequencedKeySet()) {
            rootPoms.add(toRootPom(executor, repository, coordinate, versions.get(coordinate)));
        }
        List<MavenResolver.RootPom> managedPoms = new ArrayList<>();
        SequencedMap<String, String> mavenPins = new LinkedHashMap<>();
        for (Map.Entry<String, String> pin : versions.entrySet()) {
            if (coordinates.containsKey(pin.getKey())) {
                continue;
            }
            if (pin.getKey().indexOf('/') < 0) {
                managedPoms.add(toRootPom(executor, repository, pin.getKey(), pin.getValue()));
            } else {
                mavenPins.put(pin.getKey(), pin.getValue());
            }
        }
        MavenRepository mavenRepo = MavenRepository.of(repositories.getOrDefault(mavenPrefix, Repository.empty()));
        SequencedMap<String, String> result = new LinkedHashMap<>();
        delegate.dependencies(executor, mavenRepo, rootPoms, managedPoms, MavenDependencyScope.COMPILE)
                .forEach((key, value) -> {
                    String checksum = value.checksum();
                    if (checksum == null) {
                        String pinned = mavenPins.get(key.coordinate(null, null));
                        if (pinned != null) {
                            int space = pinned.indexOf(' ');
                            if (space > 0 && pinned.substring(0, space).equals(value.version())) {
                                checksum = pinned.substring(space + 1).trim();
                            }
                        }
                    }
                    result.put(key.coordinate(mavenPrefix, value.version()), checksum == null ? "" : checksum);
                });
        return result;
    }

    private MavenResolver.RootPom toRootPom(Executor executor,
                                            Repository repository,
                                            String coordinate,
                                            String pinned) throws IOException {
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
        RepositoryItem item = repository.fetch(executor, fetchCoord)
                .orElseThrow(() -> new IllegalArgumentException("No POM found for " + coordinate));
        return new MavenResolver.RootPom(item.toInputStream(), checksum);
    }
}
