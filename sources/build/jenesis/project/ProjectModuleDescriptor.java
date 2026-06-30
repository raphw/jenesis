package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutorModule;
import build.jenesis.PathPlacement;

public class ProjectModuleDescriptor implements ProjectModule {

    private final String name;
    private final SequencedSet<Path> configuration;
    private final SequencedSet<String> dependencies;
    private final SequencedSet<String> sources;
    private final SequencedSet<String> resources;
    private final SequencedSet<String> manifests;
    private final SequencedSet<String> coordinates;
    private final SequencedSet<String> artifacts;
    private final SequencedSet<String> content;
    private final boolean test;
    private final boolean source;
    private final boolean documentation;
    private final Pinning pinning;
    private final PathPlacement pathPlacement;

    public ProjectModuleDescriptor(ProjectModule base,
                                   SequencedSet<Path> configuration,
                                   boolean test,
                                   boolean source,
                                   boolean documentation,
                                   Pinning pinning,
                                   PathPlacement pathPlacement) {
        this(base.name(),
                configuration,
                immutable(base.dependencies()),
                immutable(base.sources()),
                immutable(base.resources()),
                immutable(base.manifests()),
                immutable(base.coordinates()),
                immutable(base.artifacts()),
                Collections.emptyNavigableSet(),
                test,
                source,
                documentation,
                pinning, pathPlacement);
    }

    private ProjectModuleDescriptor(String name,
                                    SequencedSet<Path> configuration,
                                    SequencedSet<String> dependencies,
                                    SequencedSet<String> sources,
                                    SequencedSet<String> resources,
                                    SequencedSet<String> manifests,
                                    SequencedSet<String> coordinates,
                                    SequencedSet<String> artifacts,
                                    SequencedSet<String> content,
                                    boolean test,
                                    boolean source,
                                    boolean documentation,
                                    Pinning pinning,
                                    PathPlacement pathPlacement) {
        this.name = name;
        this.configuration = configuration;
        this.dependencies = dependencies;
        this.sources = sources;
        this.resources = resources;
        this.manifests = manifests;
        this.coordinates = coordinates;
        this.artifacts = artifacts;
        this.content = content;
        this.test = test;
        this.source = source;
        this.documentation = documentation;
        this.pinning = pinning;
        this.pathPlacement = pathPlacement;
    }

    public SequencedSet<Path> configuration() {
        return configuration;
    }

    public ProjectModuleDescriptor toInherited() {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                prefix(sources),
                prefix(resources),
                prefix(manifests),
                prefix(coordinates),
                prefix(artifacts),
                prefix(content),
                test,
                source,
                documentation,
                pinning, pathPlacement);
    }

    @Override
    public String name() {
        return name;
    }

    public ProjectModuleDescriptor name(String name) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    @Override
    public SequencedSet<String> dependencies() {
        return dependencies;
    }

    public ProjectModuleDescriptor dependencies(SequencedSet<String> dependencies) {
        return new ProjectModuleDescriptor(name,
                configuration,
                immutable(dependencies),
                sources,
                resources,
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public ProjectModuleDescriptor dependencies(String... dependencies) {
        return dependencies(new LinkedHashSet<>(List.of(dependencies)));
    }

    @Override
    public SequencedSet<String> sources() {
        return sources;
    }

    public ProjectModuleDescriptor sources(SequencedSet<String> sources) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                immutable(sources),
                resources,
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public ProjectModuleDescriptor sources(String... sources) {
        return sources(new LinkedHashSet<>(List.of(sources)));
    }

    @Override
    public SequencedSet<String> resources() {
        return resources;
    }

    public ProjectModuleDescriptor resources(SequencedSet<String> resources) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                immutable(resources),
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public ProjectModuleDescriptor resources(String... resources) {
        return resources(new LinkedHashSet<>(List.of(resources)));
    }

    @Override
    public SequencedSet<String> manifests() {
        return manifests;
    }

    public ProjectModuleDescriptor manifests(SequencedSet<String> manifests) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                immutable(manifests),
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public ProjectModuleDescriptor manifests(String... manifests) {
        return manifests(new LinkedHashSet<>(List.of(manifests)));
    }

    @Override
    public SequencedSet<String> coordinates() {
        return coordinates;
    }

    public ProjectModuleDescriptor coordinates(SequencedSet<String> coordinates) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                immutable(coordinates),
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public ProjectModuleDescriptor coordinates(String... coordinates) {
        return coordinates(new LinkedHashSet<>(List.of(coordinates)));
    }

    @Override
    public SequencedSet<String> artifacts() {
        return artifacts;
    }

    public ProjectModuleDescriptor artifacts(SequencedSet<String> artifacts) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                coordinates,
                immutable(artifacts),
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public ProjectModuleDescriptor artifacts(String... artifacts) {
        return artifacts(new LinkedHashSet<>(List.of(artifacts)));
    }

    public SequencedSet<String> content() {
        return content;
    }

    public ProjectModuleDescriptor content(SequencedSet<String> content) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                coordinates,
                artifacts,
                immutable(content),
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public ProjectModuleDescriptor content(String... content) {
        return content(new LinkedHashSet<>(List.of(content)));
    }

    public boolean test() {
        return test;
    }

    public ProjectModuleDescriptor test(boolean test) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public boolean source() {
        return source;
    }

    public ProjectModuleDescriptor source(boolean source) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public boolean documentation() {
        return documentation;
    }

    public ProjectModuleDescriptor documentation(boolean documentation) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public Pinning pinning() {
        return pinning;
    }

    public ProjectModuleDescriptor pinning(Pinning pinning) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    public PathPlacement pathPlacement() {
        return pathPlacement;
    }

    public ProjectModuleDescriptor pathPlacement(PathPlacement pathPlacement) {
        return new ProjectModuleDescriptor(name,
                configuration,
                dependencies,
                sources,
                resources,
                manifests,
                coordinates,
                artifacts,
                content,
                test,
                source,
                documentation,
                pinning,
                pathPlacement);
    }

    private static SequencedSet<String> immutable(SequencedSet<String> values) {
        return Collections.unmodifiableSequencedSet(new LinkedHashSet<>(values));
    }

    private static SequencedSet<String> prefix(SequencedSet<String> values) {
        LinkedHashSet<String> prefixed = new LinkedHashSet<>();
        for (String value : values) {
            prefixed.add(BuildExecutorModule.PREVIOUS + value);
        }
        return Collections.unmodifiableSequencedSet(prefixed);
    }
}
