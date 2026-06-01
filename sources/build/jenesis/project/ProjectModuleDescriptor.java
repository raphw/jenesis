package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.PathPlacement;

public class ProjectModuleDescriptor implements ModuleDescriptor {

    private final String name;
    private final SequencedSet<String> dependencies;
    private final SequencedSet<String> sources;
    private final SequencedSet<String> resources;
    private final SequencedSet<String> manifests;
    private final SequencedSet<String> coordinates;
    private final Map<DependencyScope, SequencedSet<String>> artifacts;
    private final Map<DependencyScope, SequencedSet<String>> resolved;
    private final boolean test;
    private final boolean source;
    private final boolean documentation;
    private final boolean strictPinning;
    private final PathPlacement modulePath;

    public ProjectModuleDescriptor(ModuleDescriptor base,
                                   boolean test,
                                   boolean source,
                                   boolean documentation,
                                   boolean strictPinning,
                                   PathPlacement modulePath) {
        this(base.name(),
                immutable(base.dependencies()),
                immutable(base.sources()),
                immutable(base.resources()),
                immutable(base.manifests()),
                immutable(base.coordinates()),
                scopes(base::artifacts),
                scopes(base::resolved),
                test,
                source,
                documentation,
                strictPinning, modulePath);
    }

    private ProjectModuleDescriptor(String name,
                                    SequencedSet<String> dependencies,
                                    SequencedSet<String> sources,
                                    SequencedSet<String> resources,
                                    SequencedSet<String> manifests,
                                    SequencedSet<String> coordinates,
                                    Map<DependencyScope, SequencedSet<String>> artifacts,
                                    Map<DependencyScope, SequencedSet<String>> resolved,
                                    boolean test,
                                    boolean source,
                                    boolean documentation,
                                    boolean strictPinning,
                                    PathPlacement modulePath) {
        this.name = name;
        this.dependencies = dependencies;
        this.sources = sources;
        this.resources = resources;
        this.manifests = manifests;
        this.coordinates = coordinates;
        this.artifacts = artifacts;
        this.resolved = resolved;
        this.test = test;
        this.source = source;
        this.documentation = documentation;
        this.strictPinning = strictPinning;
        this.modulePath = modulePath;
    }

    public ProjectModuleDescriptor toInherited() {
        return new ProjectModuleDescriptor(name,
                dependencies,
                prefix(sources),
                prefix(resources),
                prefix(manifests),
                prefix(coordinates),
                prefix(artifacts),
                prefix(resolved),
                test,
                source,
                documentation,
                strictPinning, modulePath);
    }

    @Override
    public String name() {
        return name;
    }

    public ProjectModuleDescriptor withName(String name) {
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    @Override
    public SequencedSet<String> dependencies() {
        return dependencies;
    }

    public ProjectModuleDescriptor withDependencies(SequencedSet<String> dependencies) {
        return new ProjectModuleDescriptor(name, immutable(dependencies), sources, resources, manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public ProjectModuleDescriptor withDependencies(String... dependencies) {
        return withDependencies(new LinkedHashSet<>(List.of(dependencies)));
    }

    @Override
    public SequencedSet<String> sources() {
        return sources;
    }

    public ProjectModuleDescriptor withSources(SequencedSet<String> sources) {
        return new ProjectModuleDescriptor(name, dependencies, immutable(sources), resources, manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public ProjectModuleDescriptor withSources(String... sources) {
        return withSources(new LinkedHashSet<>(List.of(sources)));
    }

    @Override
    public SequencedSet<String> resources() {
        return resources;
    }

    public ProjectModuleDescriptor withResources(SequencedSet<String> resources) {
        return new ProjectModuleDescriptor(name, dependencies, sources, immutable(resources), manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public ProjectModuleDescriptor withResources(String... resources) {
        return withResources(new LinkedHashSet<>(List.of(resources)));
    }

    @Override
    public SequencedSet<String> manifests() {
        return manifests;
    }

    public ProjectModuleDescriptor withManifests(SequencedSet<String> manifests) {
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, immutable(manifests), coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public ProjectModuleDescriptor withManifests(String... manifests) {
        return withManifests(new LinkedHashSet<>(List.of(manifests)));
    }

    @Override
    public SequencedSet<String> coordinates() {
        return coordinates;
    }

    public ProjectModuleDescriptor withCoordinates(SequencedSet<String> coordinates) {
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, immutable(coordinates),
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public ProjectModuleDescriptor withCoordinates(String... coordinates) {
        return withCoordinates(new LinkedHashSet<>(List.of(coordinates)));
    }

    @Override
    public SequencedSet<String> artifacts(DependencyScope scope) {
        return artifacts.getOrDefault(scope, Collections.emptyNavigableSet());
    }

    public ProjectModuleDescriptor withArtifacts(DependencyScope scope, SequencedSet<String> artifacts) {
        Map<DependencyScope, SequencedSet<String>> replaced = new LinkedHashMap<>(this.artifacts);
        replaced.put(scope, immutable(artifacts));
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, coordinates,
                Collections.unmodifiableMap(replaced), resolved, test, source, documentation, strictPinning, modulePath);
    }

    public ProjectModuleDescriptor withArtifacts(DependencyScope scope, String... artifacts) {
        return withArtifacts(scope, new LinkedHashSet<>(List.of(artifacts)));
    }

    @Override
    public SequencedSet<String> resolved(DependencyScope scope) {
        return resolved.getOrDefault(scope, Collections.emptyNavigableSet());
    }

    public ProjectModuleDescriptor withResolved(DependencyScope scope, SequencedSet<String> resolved) {
        Map<DependencyScope, SequencedSet<String>> replaced = new LinkedHashMap<>(this.resolved);
        replaced.put(scope, immutable(resolved));
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, coordinates,
                artifacts, Collections.unmodifiableMap(replaced), test, source, documentation, strictPinning, modulePath);
    }

    public ProjectModuleDescriptor withResolved(DependencyScope scope, String... resolved) {
        return withResolved(scope, new LinkedHashSet<>(List.of(resolved)));
    }

    public boolean test() {
        return test;
    }

    public ProjectModuleDescriptor withTest(boolean test) {
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public boolean source() {
        return source;
    }

    public ProjectModuleDescriptor withSource(boolean source) {
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public boolean documentation() {
        return documentation;
    }

    public ProjectModuleDescriptor withDocumentation(boolean documentation) {
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public boolean strictPinning() {
        return strictPinning;
    }

    public ProjectModuleDescriptor withStrictPinning(boolean strictPinning) {
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    public PathPlacement modulePath() {
        return modulePath;
    }

    public ProjectModuleDescriptor withModulePath(PathPlacement modulePath) {
        return new ProjectModuleDescriptor(name, dependencies, sources, resources, manifests, coordinates,
                artifacts, resolved, test, source, documentation, strictPinning, modulePath);
    }

    private static SequencedSet<String> immutable(SequencedSet<String> values) {
        return Collections.unmodifiableSequencedSet(new LinkedHashSet<>(values));
    }

    private static Map<DependencyScope, SequencedSet<String>> scopes(
            Function<DependencyScope, SequencedSet<String>> accessor) {
        Map<DependencyScope, SequencedSet<String>> values = new LinkedHashMap<>();
        for (DependencyScope scope : DependencyScope.values()) {
            values.put(scope, immutable(accessor.apply(scope)));
        }
        return Collections.unmodifiableMap(values);
    }

    private static SequencedSet<String> prefix(SequencedSet<String> values) {
        LinkedHashSet<String> prefixed = new LinkedHashSet<>();
        for (String value : values) {
            prefixed.add(BuildExecutorModule.PREVIOUS + value);
        }
        return Collections.unmodifiableSequencedSet(prefixed);
    }

    private static Map<DependencyScope, SequencedSet<String>> prefix(Map<DependencyScope, SequencedSet<String>> values) {
        Map<DependencyScope, SequencedSet<String>> prefixed = new LinkedHashMap<>();
        values.forEach((scope, set) -> prefixed.put(scope, prefix(set)));
        return Collections.unmodifiableMap(prefixed);
    }
}
