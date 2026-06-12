package build.jenesis.maven;

import module java.base;
import build.jenesis.DependencyScope;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.PathPlacement;
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
    public Resolver.Resolution dependencies(Executor executor,
                                            String prefix,
                                            Map<String, Repository> repositories,
                                            SequencedMap<String, SequencedSet<String>> coordinates,
                                            SequencedMap<String, String> versions,
                                            DependencyScope scope) throws IOException {
        coordinates.forEach((coordinate, exclusions) -> {
            if (!exclusions.isEmpty()) {
                throw new IllegalArgumentException(
                        "Module system does not support exclusions, but " + coordinate + " declares " + exclusions);
            }
        });
        Repository repository = repositories.getOrDefault(Resolver.base(prefix), discovery);
        List<MavenResolver.RootPom> rootPoms = new ArrayList<>();
        for (String coordinate : coordinates.sequencedKeySet()) {
            rootPoms.add(toRootPom(executor, repository, coordinate, versions.get(coordinate), coordinate));
        }
        List<MavenResolver.RootPom> managedPoms = new ArrayList<>();
        SequencedMap<String, String> mavenPins = new LinkedHashMap<>();
        for (Map.Entry<String, String> pin : versions.entrySet()) {
            if (coordinates.containsKey(pin.getKey())) {
                continue;
            }
            if (pin.getKey().indexOf('/') < 0) {
                managedPoms.add(toRootPom(executor, repository, pin.getKey(), pin.getValue(), null));
            } else {
                mavenPins.put(pin.getKey(), pin.getValue());
            }
        }
        MavenRepository mavenRepo = MavenRepository.of(repositories.getOrDefault(mavenPrefix, Repository.empty()));
        MavenResolver.Closure resolution = delegate.dependencies(
                executor, mavenRepo, rootPoms, managedPoms, MavenDependencyScope.COMPILE, mavenPrefix);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> closure = resolution.dependencies();
        SequencedMap<String, String> result = new LinkedHashMap<>();
        closure.forEach((key, value) -> {
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
        SequencedMap<String, Resolver.Resolved> materialized = new LinkedHashMap<>(
                Resolver.materializeAll(executor, repositories, mavenPrefix, result));
        resolution.roots().forEach((module, key) -> {
            MavenDependencyValue value = closure.get(key);
            if (value == null) {
                return;
            }
            Resolver.Resolved root = materialized.get(key.coordinate(mavenPrefix, value.version()));
            // Internal siblings are built locally and resolved by coordinate, not pinned to a
            // version, so they get no module root entry (and no versioned discovery fetch).
            if (root != null && !root.internal()) {
                materialized.putIfAbsent("module/" + module + "/" + value.version(),
                        new Resolver.Resolved(root.file(), "", root.internal()));
            }
        });
        SequencedMap<String, Resolver.Vertex> nodes = new LinkedHashMap<>();
        closure.forEach((key, value) -> {
            String withVersion = key.coordinate(mavenPrefix, value.version());
            Resolver.Resolved artifact = materialized.get(withVersion);
            ModuleDescriptor descriptor = artifact == null ? null : PathPlacement.moduleDescriptor(artifact.file());
            nodes.put(key.coordinate(mavenPrefix, null), new Resolver.Vertex(
                    value.version(),
                    descriptor == null ? null : descriptor.name(),
                    descriptor != null && descriptor.isAutomatic(),
                    resolution.licenses().getOrDefault(withVersion, List.of())));
        });
        return new Resolver.Resolution(materialized, resolution.edges(), nodes);
    }

    private MavenResolver.RootPom toRootPom(Executor executor,
                                            Repository repository,
                                            String coordinate,
                                            String pinned,
                                            String identifier) throws IOException {
        String fetchCoord;
        String checksum;
        if (pinned == null || pinned.isEmpty()) {
            fetchCoord = coordinate + ":pom";
            checksum = null;
        } else {
            int space = pinned.indexOf(' ');
            String version = space < 0 ? pinned : pinned.substring(0, space);
            if (version.startsWith(":")) {
                throw new IllegalArgumentException("Module classifiers are not supported when resolving "
                        + coordinate + " through Maven: " + version);
            }
            checksum = space < 0 ? null : pinned.substring(space + 1).trim();
            fetchCoord = coordinate + "/" + version + ":pom";
        }
        RepositoryItem item = repository.fetch(executor, fetchCoord)
                .orElseThrow(() -> new IllegalArgumentException("No POM found for " + coordinate));
        return new MavenResolver.RootPom(item.toInputStream(), checksum, identifier);
    }
}
