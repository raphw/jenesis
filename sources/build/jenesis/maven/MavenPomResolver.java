package build.jenesis.maven;

import module java.base;
import module java.xml;
import build.jenesis.DependencyScope;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.License;
import build.jenesis.PathPlacement;
import build.jenesis.Platform;
import build.jenesis.Resolver;

public class MavenPomResolver implements MavenResolver {

    private static final String NAMESPACE_4_0_0 = "http://maven.apache.org/POM/4.0.0";
    private static final Set<String> IMPLICITS = Set.of("groupId", "artifactId", "version", "packaging");
    private static final Pattern PROPERTY = Pattern.compile("(\\$\\{([^}]+)})");
    public static final String CHECKSUM_PREFIX = "Checksum/";

    private final Supplier<MavenVersionNegotiator> negotiatorSupplier;
    private final transient DocumentBuilderFactory factory = MavenDefaultVersionNegotiator.toDocumentBuilderFactory();

    public MavenPomResolver() {
        negotiatorSupplier = MavenDefaultVersionNegotiator.maven();
    }

    public <S extends Supplier<MavenVersionNegotiator> & Serializable> MavenPomResolver(S negotiatorSupplier) {
        this.negotiatorSupplier = negotiatorSupplier;
    }

    @Override
    public Resolver.Resolution dependencies(Executor executor,
                                            String prefix,
                                            Map<String, Repository> repositories,
                                            SequencedMap<String, SequencedSet<String>> coordinates,
                                            SequencedMap<String, String> versions,
                                            DependencyScope scope) throws IOException {
        Map<MavenDependencyKey, MavenDependencyValue> managedDependencies = new LinkedHashMap<>();
        versions.forEach((coordinate, value) -> {
            MavenDependencyKey key = MavenDependencyKey.parseKey(coordinate);
            int split = value.indexOf(' ');
            String version = split < 0 ? value : value.substring(0, split);
            String checksum = split < 0 ? null : value.substring(split + 1).trim();
            managedDependencies.put(key, new MavenDependencyValue(
                    version, null, null, null, null, checksum));
        });
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
        coordinates.forEach((coordinate, excludes) -> {
            List<MavenDependencyName> exclusions = excludes.isEmpty()
                    ? null
                    : excludes.stream()
                            .map(entry -> {
                                int separator = entry.indexOf('/');
                                return new MavenDependencyName(
                                        entry.substring(0, separator), entry.substring(separator + 1));
                            })
                            .collect(Collectors.toList());
            MavenDependencyKey.Versioned parsed = MavenDependencyKey.tryParse(coordinate);
            MavenDependencyValue managed = managedDependencies.get(parsed.key());
            String declared = parsed.version();
            if (declared != null && !isFloating(declared)) {
                dependencies.put(parsed.key(),
                        new MavenDependencyValue(declared, MavenDependencyScope.COMPILE, null, exclusions, null));
            } else if (managed != null) {
                dependencies.put(parsed.key(), new MavenDependencyValue(
                        managed.version(), MavenDependencyScope.COMPILE, null, exclusions, null, managed.checksum()));
            } else if (declared != null) {
                dependencies.put(parsed.key(),
                        new MavenDependencyValue(declared, MavenDependencyScope.COMPILE, null, exclusions, null));
            } else {
                throw new IllegalStateException(
                        "No version pinned for " + coordinate + " (add to dependencyManagement)");
            }
        });
        Traversal traversal = dependencies(executor,
                MavenRepository.of(repositories.getOrDefault(Resolver.base(prefix), Repository.empty())),
                new ContextualPom(new ResolvedPom(managedDependencies, dependencies, List.of()), true, null, Set.of(), null, null),
                new HashMap<>(),
                new HashMap<>(),
                prefix);
        SequencedMap<String, String> resolved = new LinkedHashMap<>();
        traversal.dependencies().forEach((key, value) -> resolved.put(
                key.coordinate(prefix, value.version()),
                value.checksum() == null ? "" : value.checksum()));
        SequencedMap<String, Resolver.Resolved> artifacts = Resolver.materializeAll(executor, repositories, prefix, resolved);
        SequencedMap<String, Resolver.Vertex> nodes = new LinkedHashMap<>();
        traversal.dependencies().forEach((key, value) -> {
            String withVersion = key.coordinate(prefix, value.version());
            Resolver.Resolved artifact = artifacts.get(withVersion);
            ModuleDescriptor descriptor = artifact == null ? null : PathPlacement.moduleDescriptor(artifact.file());
            nodes.put(key.coordinate(prefix, null), new Resolver.Vertex(
                    value.version(),
                    descriptor == null ? null : descriptor.name(),
                    descriptor != null && descriptor.isAutomatic(),
                    traversal.licenses().getOrDefault(withVersion, List.of())));
        });
        return new Resolver.Resolution(artifacts, traversal.edges(), nodes);
    }

    public SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies(
            Executor executor,
            MavenRepository repository,
            Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies) throws IOException {
        return dependencies(executor,
                repository,
                new ContextualPom(new ResolvedPom(managedDependencies, dependencies, List.of()),
                        true,
                        null,
                        Set.of(),
                        null,
                        null),
                new HashMap<>(),
                new HashMap<>(),
                null).dependencies();
    }

    @Override
    public MavenResolver.Closure dependencies(
            Executor executor,
            MavenRepository repository,
            List<RootPom> rootPoms,
            List<RootPom> managedPoms,
            MavenDependencyScope scope,
            String prefix) throws IOException {
        Map<DependencyCoordinate, UnresolvedPom> unresolved = new HashMap<>();
        Map<DependencyCoordinate, ResolvedPom> resolved = new HashMap<>();
        Map<MavenDependencyKey, MavenDependencyValue> managedDependencies = new LinkedHashMap<>();
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
        SequencedMap<String, MavenDependencyKey> roots = new LinkedHashMap<>();
        for (RootPom managedPom : managedPoms) {
            UnresolvedPom assembled;
            try (InputStream stream = managedPom.pom()) {
                assembled = assemble(executor, repository, stream, false, true, null, null, new HashSet<>(), unresolved);
            } catch (SAXException | ParserConfigurationException e) {
                throw new IllegalStateException("Failed to parse provided managed POM", e);
            }
            ResolvedPom resolvedManaged = resolve(executor, repository, assembled, unresolved);
            String groupId = property(assembled.groupId(), assembled.properties());
            String artifactId = property(assembled.artifactId(), assembled.properties());
            String version = property(assembled.version(), assembled.properties());
            DependencyCoordinate coordinate = new DependencyCoordinate(groupId, artifactId, version);
            unresolved.putIfAbsent(coordinate, assembled);
            resolved.putIfAbsent(coordinate, resolvedManaged);
            managedDependencies.putIfAbsent(
                    new MavenDependencyKey(groupId, artifactId, "jar", null),
                    new MavenDependencyValue(version, null, null, null, null, managedPom.checksum()));
        }
        for (RootPom rootPom : rootPoms) {
            UnresolvedPom assembled;
            try (InputStream stream = rootPom.pom()) {
                assembled = assemble(executor, repository, stream, false, true, null, null, new HashSet<>(), unresolved);
            } catch (SAXException | ParserConfigurationException e) {
                throw new IllegalStateException("Failed to parse provided root POM", e);
            }
            ResolvedPom resolvedRoot = resolve(executor, repository, assembled, unresolved);
            String groupId = property(assembled.groupId(), assembled.properties());
            String artifactId = property(assembled.artifactId(), assembled.properties());
            String version = property(assembled.version(), assembled.properties());
            DependencyCoordinate coordinate = new DependencyCoordinate(groupId, artifactId, version);
            unresolved.putIfAbsent(coordinate, assembled);
            resolved.putIfAbsent(coordinate, resolvedRoot);
            MavenDependencyKey key = new MavenDependencyKey(groupId, artifactId, "jar", null);
            dependencies.put(key, new MavenDependencyValue(version, scope, null, null, null, rootPom.checksum()));
            if (rootPom.identifier() != null) {
                roots.put(rootPom.identifier(), key);
            }
        }
        Traversal traversal = dependencies(executor, repository,
                new ContextualPom(new ResolvedPom(managedDependencies, dependencies, List.of()), true, scope, Set.of(), null, null),
                unresolved, resolved, prefix);
        return new MavenResolver.Closure(traversal.dependencies(), roots, traversal.edges(), traversal.licenses());
    }

    public SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies(Executor executor,
                                                                               MavenRepository repository,
                                                                               String groupId,
                                                                               String artifactId,
                                                                               String version,
                                                                               MavenDependencyScope scope)
            throws IOException {
        Map<DependencyCoordinate, UnresolvedPom> unresolved = new HashMap<>();
        Map<DependencyCoordinate, ResolvedPom> resolved = new HashMap<>();
        return dependencies(executor,
                repository,
                new ContextualPom(resolveOrCached(executor,
                        repository,
                        groupId,
                        artifactId,
                        version,
                        resolved,
                        unresolved), true, scope, Set.of(), null, null),
                unresolved,
                resolved,
                null).dependencies();
    }

    private Traversal dependencies(
            Executor executor,
            MavenRepository repository,
            ContextualPom initial,
            Map<DependencyCoordinate, UnresolvedPom> unresolved,
            Map<DependencyCoordinate, ResolvedPom> resolved,
            String prefix) throws IOException {
        Map<MavenDependencyKey, DependencyResolution> resolutions = new HashMap<>();
        SequencedSet<MavenDependencyKey> dependencies = new LinkedHashSet<>(), conflicts;
        MavenVersionNegotiator negotiator = negotiatorSupplier.get();
        List<Resolver.Edge> edges = new ArrayList<>();
        do {
            dependencies.clear();
            edges.clear();
            conflicts = traverse(executor,
                    repository,
                    negotiator,
                    resolved,
                    unresolved,
                    resolutions,
                    initial.pom().managedDependencies(),
                    dependencies,
                    initial,
                    prefix,
                    edges);
            Iterator<MavenDependencyKey> it = conflicts.iterator();
            while (it.hasNext()) {
                MavenDependencyKey key = it.next();
                DependencyResolution resolution = resolutions.get(key);
                boolean converged = true;
                if (resolution.widestScope != resolution.currentScope) {
                    resolution.currentScope = resolution.widestScope;
                    converged = false;
                }
                if (resolution.observedVersions.size() > 1) {
                    String candidate = negotiator.resolve(executor,
                            repository,
                            key.groupId(),
                            key.artifactId(),
                            key.type(),
                            key.classifier(),
                            resolution.currentVersion,
                            resolution.observedVersions);
                    if (!resolution.currentVersion.equals(candidate)) {
                        resolution.currentVersion = candidate;
                        converged = false;
                    }
                }
                if (converged) {
                    it.remove();
                }
            }
        } while (!conflicts.isEmpty());
        SequencedMap<MavenDependencyKey, MavenDependencyValue> results = new LinkedHashMap<>();
        dependencies.forEach(key -> {
            DependencyResolution resolution = resolutions.get(key);
            results.put(key, new MavenDependencyValue(resolution.currentVersion,
                    resolution.widestScope,
                    resolution.systemPath,
                    resolution.exclusions,
                    resolution.optional,
                    selectChecksum(resolution)));
        });
        SequencedMap<String, List<License>> licenses = new LinkedHashMap<>();
        results.forEach((key, value) -> {
            ResolvedPom pom = resolved.get(new DependencyCoordinate(key.groupId(), key.artifactId(), value.version()));
            if (pom != null && !pom.licenses().isEmpty()) {
                licenses.put(key.coordinate(prefix, value.version()), pom.licenses());
            }
        });
        return new Traversal(results, edges, licenses);
    }

    private SequencedSet<MavenDependencyKey> traverse(Executor executor,
                                                      MavenRepository repository,
                                                      MavenVersionNegotiator negotiator,
                                                      Map<DependencyCoordinate, ResolvedPom> resolved,
                                                      Map<DependencyCoordinate, UnresolvedPom> unresolved,
                                                      Map<MavenDependencyKey, DependencyResolution> resolutions,
                                                      Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
                                                      SequencedSet<MavenDependencyKey> dependencies,
                                                      ContextualPom current,
                                                      String prefix,
                                                      List<Resolver.Edge> edges) throws IOException {
        SequencedSet<MavenDependencyKey> conflicting = new LinkedHashSet<>();
        Queue<ContextualPom> queue = new ArrayDeque<>();
        do {
            for (Map.Entry<MavenDependencyKey, MavenDependencyValue> entry : current.pom().dependencies().entrySet()) {
                if (current.exclusions().contains(MavenDependencyName.EXCLUDE_ALL)) {
                    break;
                } else if (current.exclusions().contains(new MavenDependencyName(entry.getKey().groupId(), entry.getKey().artifactId()))
                        || current.exclusions().contains(new MavenDependencyName(entry.getKey().groupId(), "*"))
                        || current.exclusions().contains(new MavenDependencyName("*", entry.getKey().artifactId()))) {
                    continue;
                }
                MavenDependencyValue override = managedDependencies.get(entry.getKey()), value;
                if (current.root()) {
                    value = merge(entry.getValue(), override);
                } else {
                    value = override == null ? entry.getValue() : merge(override, entry.getValue());
                    value = merge(value, current.pom().managedDependencies().get(entry.getKey()));
                }
                if (!current.root() && Objects.equals(Boolean.TRUE, value.optional())) {
                    continue;
                }
                DependencyResolution resolution = resolutions.computeIfAbsent(
                        entry.getKey(),
                        _ -> new DependencyResolution());
                MavenDependencyScope resolvedScope = switch (current.scope()) {
                    case null -> value.scope();
                    case COMPILE -> switch (value.scope()) {
                        case COMPILE, RUNTIME -> value.scope();
                        default -> null;
                    };
                    case PROVIDED, RUNTIME, TEST -> switch (value.scope()) {
                        case COMPILE, RUNTIME -> current.scope();
                        default -> null;
                    };
                    case SYSTEM, IMPORT -> null;
                }, scope = resolution.currentScope == null || resolution.currentScope.reduces(resolvedScope)
                        ? resolvedScope
                        : resolution.currentScope;
                if (scope == null) {
                    continue;
                }
                String version;
                bindChecksum(entry.getKey(), resolution, value.version(), value.checksum());
                if (resolution.currentVersion == null) {
                    version = resolution.currentVersion = negotiator.resolve(executor,
                            repository,
                            entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            entry.getKey().type(),
                            entry.getKey().classifier(),
                            value.version());
                    resolution.observedVersions.add(value.version());
                    resolution.currentScope = resolution.widestScope = scope;
                    resolution.systemPath = entry.getValue().systemPath();
                    resolution.exclusions = entry.getValue().exclusions();
                    resolution.optional = entry.getValue().optional();
                } else {
                    version = resolution.currentVersion;
                    if (resolution.observedVersions.add(value.version()) || resolution.widestScope.reduces(scope)) {
                        resolution.widestScope = scope;
                        conflicting.add(entry.getKey());
                    }
                }
                boolean followed = dependencies.add(entry.getKey());
                edges.add(new Resolver.Edge(
                        current.origin() == null
                                ? null
                                : current.origin().coordinate(prefix, current.originVersion()),
                        entry.getKey().coordinate(prefix, value.version()),
                        value.version(),
                        scope.name().toLowerCase(Locale.ROOT),
                        followed));
                if (followed) {
                    ResolvedPom pom = resolveOrCached(executor,
                            repository,
                            entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            version,
                            resolved,
                            unresolved);
                    Set<MavenDependencyName> exclusions = current.exclusions();
                    if (value.exclusions() != null) {
                        exclusions = new HashSet<>(exclusions);
                        exclusions.addAll(value.exclusions());
                    }
                    queue.add(new ContextualPom(pom, false, scope, exclusions, entry.getKey(), value.version()));
                }
            }
        } while ((current = queue.poll()) != null);
        return conflicting;
    }

    private static void bindChecksum(MavenDependencyKey key,
                                     DependencyResolution resolution,
                                     String version,
                                     String checksum) {
        if (checksum == null) {
            return;
        }
        String existing = resolution.checksums.putIfAbsent(version, checksum);
        if (existing != null && !existing.equals(checksum)) {
            throw new IllegalStateException("Conflicting checksums for "
                    + key.groupId() + ":" + key.artifactId() + ":" + version
                    + " (" + existing + " and " + checksum + ")");
        }
    }

    private static String selectChecksum(DependencyResolution resolution) {
        String checksum = resolution.checksums.get(resolution.currentVersion);
        if (checksum != null) {
            return checksum;
        }
        if (resolution.checksums.size() == 1) {
            return resolution.checksums.values().iterator().next();
        }
        return null;
    }

    public SequencedMap<Path, MavenLocalPom> local(Executor executor,
                                                   Repository repository,
                                                   Path root) throws IOException {
        SequencedSet<Path> modules = new LinkedHashSet<>();
        Map<DependencyCoordinate, UnresolvedPom> unresolved = new HashMap<>();
        Map<Path, UnresolvedPom> paths = new HashMap<>();
        Queue<Path> queue = new ArrayDeque<>();
        Path current = root;
        do {
            if (modules.add(current)) {
                UnresolvedPom pom;
                try {
                    pom = assemble(executor,
                            MavenRepository.of(repository),
                            Files.newInputStream(current.resolve("pom.xml")),
                            true,
                            true,
                            current,
                            paths,
                            new HashSet<>(),
                            unresolved);
                } catch (SAXException | ParserConfigurationException e) {
                    throw new RuntimeException(e);
                }
                if (pom.modules() != null) {
                    for (String module : pom.modules()) {
                        queue.add(current.resolve(module).normalize());
                    }
                }
                paths.put(current, pom);
            } else {
                throw new IllegalArgumentException("Circular POM module reference to " + current);
            }
        } while ((current = queue.poll()) != null);
        SequencedMap<Path, MavenLocalPom> results = new LinkedHashMap<>();
        for (Path module : modules) {
            UnresolvedPom pom = paths.get(module);
            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
            SequencedMap<MavenDependencyKey, MavenDependencyValue> managedDependencies = new LinkedHashMap<>();
            for (Map.Entry<DependencyKey, DependencyValue> entry : pom.managedDependencies().entrySet()) {
                MavenDependencyKey key = entry.getKey().resolve(pom.properties());
                MavenDependencyValue value = entry.getValue().resolveManaged(pom.properties());
                if (value.scope() == MavenDependencyScope.IMPORT) {
                    flattenImport(executor,
                            MavenRepository.of(repository),
                            key.groupId(),
                            key.artifactId(),
                            value.version(),
                            value.checksum(),
                            managedDependencies,
                            new HashSet<>(),
                            unresolved);
                } else {
                    managedDependencies.put(key, value);
                }
            }
            pom.dependencies().forEach((key, value) -> {
                MavenDependencyKey resolvedKey = key.resolve(pom.properties());
                dependencies.put(resolvedKey, merge(value.resolve(pom.properties()),
                        managedDependencies.get(resolvedKey)));
            });
            results.put(root.relativize(module), new MavenLocalPom(property(pom.groupId(), pom.properties()),
                    property(pom.artifactId(), pom.properties()),
                    property(pom.version(), pom.properties()),
                    property(pom.packaging(), pom.properties()),
                    property(pom.properties().get("maven.compiler.release"), pom.properties()),
                    property(pom.sourceDirectory(), pom.properties()),
                    pom.resourceDirectories() == null ? null : pom.resourceDirectories().stream()
                            .map(resource -> property(resource, pom.properties()))
                            .toList(),
                    property(pom.testSourceDirectory(), pom.properties()),
                    pom.testResourceDirectories() == null ? null : pom.testResourceDirectories().stream()
                            .map(resource -> property(resource, pom.properties()))
                            .toList(),
                    dependencies,
                    managedDependencies,
                    pom.qualifiedDependencies(),
                    property(pom.properties().get("mainClass"), pom.properties())));
        }
        return results;
    }

    private UnresolvedPom assemble(Executor executor,
                                   MavenRepository repository,
                                   InputStream inputStream,
                                   boolean extended,
                                   boolean trusted,
                                   Path path,
                                   Map<Path, UnresolvedPom> paths,
                                   Set<DependencyCoordinate> children,
                                   Map<DependencyCoordinate, UnresolvedPom> unresolved)
            throws IOException, SAXException, ParserConfigurationException {
        Document document;
        try (inputStream) {
            document = factory.newDocumentBuilder().parse(inputStream);
        }
        String namespace = document.getDocumentElement().getNamespaceURI();
        return switch (namespace == null ? NAMESPACE_4_0_0 : namespace) {
            case NAMESPACE_4_0_0 -> {
                ParentCoordinate parent = toChildren400(document.getDocumentElement(), "parent")
                        .findFirst()
                        .map(node -> new ParentCoordinate(
                                toTextChild400(node, "groupId").orElseThrow(missing("parent.groupId")),
                                toTextChild400(node, "artifactId").orElseThrow(missing("parent.artifactId")),
                                toTextChild400(node, "version").orElseThrow(missing("parent.version")),
                                path != null ? toTextChild400(node, "relativePath").map(value -> value.endsWith("/pom.xml")
                                        ? value.substring(0, value.length() - 7)
                                        : value).orElse("../") : null))
                        .orElse(null);
                Map<String, String> properties = new HashMap<>();
                Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
                SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
                List<License> parentLicenses = List.of();
                String groupId = null, artifactId = null, version = null;
                if (parent != null) {
                    if (!children.add(new DependencyCoordinate(parent.groupId(),
                            parent.artifactId(),
                            parent.version()))) {
                        throw new IllegalStateException("Circular dependency to "
                                + parent.groupId() + ":" + parent.artifactId() + ":" + parent.version());
                    }
                    UnresolvedPom resolution = null;
                    if (path != null && !parent.relativePath().isEmpty()) {
                        resolution = paths.get(path);
                        if (resolution == null) {
                            Path candidate = path.resolve(parent.relativePath()), pom = candidate.resolve("pom.xml");
                            if (Files.exists(pom)) {
                                resolution = assemble(executor,
                                        repository,
                                        Files.newInputStream(pom),
                                        false,
                                        trusted,
                                        candidate,
                                        paths,
                                        children,
                                        unresolved);
                                paths.put(path, resolution);
                            }
                        }
                        if (resolution != null) {
                            groupId = property(resolution.groupId(), resolution.properties());
                            artifactId = property(resolution.artifactId(), resolution.properties());
                            version = property(resolution.version(), resolution.properties());
                            if (!parent.groupId().equals(groupId)
                                    || !parent.artifactId().equals(artifactId)
                                    || !parent.version().equals(version)) {
                                resolution = null;
                            }
                        }
                    }
                    if (resolution == null) {
                        resolution = assembleOrCached(executor,
                                repository,
                                parent.groupId(),
                                parent.artifactId(),
                                parent.version(),
                                null,
                                children,
                                unresolved);
                        groupId = property(resolution.groupId(), resolution.properties());
                        artifactId = property(resolution.artifactId(), resolution.properties());
                        version = property(resolution.version(), resolution.properties());
                    }
                    properties.putAll(resolution.properties());
                    for (String property : IMPLICITS) {
                        String value = resolution.properties().get(property);
                        if (value != null) {
                            properties.put("parent." + property, value);
                            properties.put("project.parent." + property, value);
                        }
                    }
                    managedDependencies.putAll(resolution.managedDependencies());
                    dependencies.putAll(resolution.dependencies());
                    parentLicenses = resolution.licenses();
                }
                IMPLICITS.forEach(property -> toChildren400(document.getDocumentElement(), property)
                        .findFirst()
                        .ifPresent(node -> {
                            String value = node.getTextContent().trim();
                            properties.put(property, value);
                            properties.put("project." + property, value);
                        }));
                toChildren400(document.getDocumentElement(), "properties")
                        .limit(1)
                        .flatMap(MavenPomResolver::toChildren)
                        .filter(node -> node.getNodeType() == Node.ELEMENT_NODE)
                        .forEach(node -> properties.put(node.getLocalName(), node.getTextContent().trim()));
                toChildren400(document.getDocumentElement(), "dependencyManagement")
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependencies"))
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependency"))
                        .map(node -> toDependency400(node, trusted))
                        .forEach(entry -> managedDependencies.put(entry.getKey(), entry.getValue()));
                toChildren400(document.getDocumentElement(), "dependencies")
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependency"))
                        .map(node -> toDependency400(node, false))
                        .forEach(entry -> dependencies.putLast(entry.getKey(), entry.getValue()));
                Node build = extended
                        ? toChildren400(document.getDocumentElement(), "build").findFirst().orElse(null)
                        : null;
                Node modules = extended
                        ? toChildren400(document.getDocumentElement(), "modules").findFirst().orElse(null)
                        : null;
                List<License> ownLicenses = toChildren400(document.getDocumentElement(), "licenses")
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "license"))
                        .map(node -> new License(
                                null,
                                null,
                                toTextChild400(node, "name").orElse(null),
                                toTextChild400(node, "url").orElse(null)))
                        .toList();
                yield new UnresolvedPom(
                        toTextChild400(document.getDocumentElement(), "groupId").orElse(groupId),
                        toTextChild400(document.getDocumentElement(), "artifactId").orElse(artifactId),
                        toTextChild400(document.getDocumentElement(), "version").orElse(version),
                        extended ? toTextChild400(document.getDocumentElement(), "packaging").orElse(null) : null,
                        build == null ? null : toTextChild400(build, "sourceDirectory").orElse(null),
                        build == null ? null : toChildren400(build, "resources").findFirst()
                                .map(node -> toChildren400(node, "resource")
                                        .map(child -> toTextChild400(child, "directory").orElse(null))
                                        .filter(Objects::nonNull)
                                        .toList())
                                .orElse(null),
                        build == null ? null : toTextChild400(build, "testSourceDirectory").orElse(null),
                        build == null ? null : toChildren400(build, "testResources").findFirst()
                                .map(node -> toChildren400(node, "testResource")
                                        .map(child -> toTextChild400(child, "directory").orElse(null))
                                        .filter(Objects::nonNull)
                                        .toList())
                                .orElse(null),
                        modules == null ? null : toChildren400(modules, "module")
                                .map(Node::getTextContent)
                                .toList(),
                        properties,
                        managedDependencies,
                        dependencies,
                        extended
                                ? toQualifiedDependencies(document.getDocumentElement())
                                : new LinkedHashMap<>(),
                        ownLicenses.isEmpty() ? parentLicenses : ownLicenses);
            }
            default -> throw new IllegalArgumentException("Unknown namespace: " + namespace);
        };
    }

    private UnresolvedPom assembleOrCached(Executor executor,
                                           MavenRepository repository,
                                           String groupId,
                                           String artifactId,
                                           String version,
                                           String checksum,
                                           Set<DependencyCoordinate> children,
                                           Map<DependencyCoordinate, UnresolvedPom> poms) throws IOException {
        DependencyCoordinate coordinates = new DependencyCoordinate(groupId, artifactId, version);
        UnresolvedPom pom = poms.get(coordinates);
        if (pom == null) {
            try {
                RepositoryItem candidate = repository.fetch(executor,
                        groupId,
                        artifactId,
                        version,
                        "pom",
                        null,
                        null).orElse(null);
                if (candidate == null) {
                    pom = new UnresolvedPom(groupId,
                            artifactId,
                            version,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Map.of(),
                            Map.of(),
                            Collections.emptyNavigableMap(),
                            new LinkedHashMap<>(),
                            List.of());
                } else {
                    InputStream stream = candidate.toInputStream();
                    Path localPath = candidate.file().map(Path::getParent).orElse(null);
                    Map<Path, UnresolvedPom> localPaths = localPath == null ? null : new HashMap<>();
                    if (checksum != null) {
                        int separator = checksum.indexOf('/');
                        if (separator < 0) {
                            throw new IllegalArgumentException(
                                    "Malformed POM checksum for " + groupId + ":" + artifactId + ":" + version
                                            + " (expected <algorithm>/<hex>): " + checksum);
                        }
                        String algorithm = checksum.substring(0, separator);
                        String expected = checksum.substring(separator + 1);
                        MessageDigest digest;
                        try {
                            digest = MessageDigest.getInstance(algorithm);
                        } catch (NoSuchAlgorithmException e) {
                            throw new IllegalStateException(e);
                        }
                        DigestInputStream digestStream = new DigestInputStream(stream, digest);
                        pom = assemble(executor, repository, drainAndValidate(digestStream, digest, expected,
                                groupId, artifactId, version), false, false, localPath, localPaths, children, poms);
                    } else {
                        pom = assemble(executor, repository, stream, false, false, localPath, localPaths, children, poms);
                    }
                }
            } catch (RuntimeException | SAXException | ParserConfigurationException e) {
                throw new IllegalStateException("Failed to resolve " + groupId + ":" + artifactId + ":" + version, e);
            }
            poms.put(coordinates, pom);
        }
        return pom;
    }

    private static InputStream drainAndValidate(DigestInputStream stream,
                                                MessageDigest digest,
                                                String expectedHex,
                                                String groupId,
                                                String artifactId,
                                                String version) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (stream) {
            stream.transferTo(buffer);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedHex)) {
            throw new IllegalStateException("Mismatched POM checksum for "
                    + groupId + ":" + artifactId + ":" + version
                    + " (expected " + expectedHex + ", got " + actual + ")");
        }
        return new ByteArrayInputStream(buffer.toByteArray());
    }

    private ResolvedPom resolve(Executor executor,
                                MavenRepository repository,
                                UnresolvedPom pom,
                                Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException {
        Map<MavenDependencyKey, MavenDependencyValue> managedDependencies = new HashMap<>();
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
        for (Map.Entry<DependencyKey, DependencyValue> entry : pom.managedDependencies().entrySet()) {
            MavenDependencyKey key = entry.getKey().resolve(pom.properties());
            MavenDependencyValue value = entry.getValue().resolveManaged(pom.properties());
            if (value.scope() == MavenDependencyScope.IMPORT) {
                flattenImport(executor,
                        repository,
                        key.groupId(),
                        key.artifactId(),
                        value.version(),
                        value.checksum(),
                        managedDependencies,
                        new HashSet<>(),
                        unresolved);
            } else {
                managedDependencies.put(key, value);
            }
        }
        pom.dependencies().forEach((key, value) -> dependencies.put(
                key.resolve(pom.properties()),
                value.resolve(pom.properties())));
        return new ResolvedPom(managedDependencies, dependencies, pom.licenses());
    }

    private void flattenImport(Executor executor,
                               MavenRepository repository,
                               String groupId,
                               String artifactId,
                               String version,
                               String checksum,
                               Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
                               Set<DependencyCoordinate> imports,
                               Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException {
        if (!imports.add(new DependencyCoordinate(groupId, artifactId, version))) {
            return;
        }
        UnresolvedPom imported = assembleOrCached(executor,
                repository,
                groupId,
                artifactId,
                version,
                checksum,
                new HashSet<>(),
                unresolved);
        for (Map.Entry<DependencyKey, DependencyValue> entry : imported.managedDependencies().entrySet()) {
            MavenDependencyKey importKey = entry.getKey().resolve(imported.properties());
            MavenDependencyValue importValue = entry.getValue().resolveManaged(imported.properties());
            if (importValue.scope() == MavenDependencyScope.IMPORT) {
                flattenImport(executor,
                        repository,
                        importKey.groupId(),
                        importKey.artifactId(),
                        importValue.version(),
                        importValue.checksum(),
                        managedDependencies,
                        imports,
                        unresolved);
            } else {
                managedDependencies.putIfAbsent(importKey, importValue);
            }
        }
    }

    private ResolvedPom resolveOrCached(Executor executor,
                                        MavenRepository repository,
                                        String groupId,
                                        String artifactId,
                                        String version,
                                        Map<DependencyCoordinate, ResolvedPom> resolved,
                                        Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException {
        DependencyCoordinate coordinates = new DependencyCoordinate(groupId, artifactId, version);
        ResolvedPom pom = resolved.get(coordinates);
        if (pom == null) {
            try {
                pom = resolve(executor, repository, assembleOrCached(executor,
                        repository,
                        groupId,
                        artifactId,
                        version,
                        null,
                        new HashSet<>(),
                        unresolved), unresolved);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to resolve " + groupId + ":" + artifactId + ":" + version, e);
            }
            resolved.put(coordinates, pom);
        }
        return pom;
    }

    static Stream<Node> toChildren(Node node) {
        NodeList children = node.getChildNodes();
        return IntStream.iterate(0,
                index -> index < children.getLength(),
                index -> index + 1).mapToObj(children::item);
    }

    private static Stream<Node> toChildren400(Node node, String localName) {
        return toChildren(node).filter(child -> Objects.equals(child.getLocalName(), localName)
                && (child.getNamespaceURI() == null
                        || NAMESPACE_4_0_0.equals(child.getNamespaceURI())));
    }

    private static Optional<String> toTextChild400(Node node, String localName) {
        return toChildren400(node, localName).map(child -> child.getTextContent().trim()).findFirst();
    }

    private static Map.Entry<DependencyKey, DependencyValue> toDependency400(Node node, boolean trusted) {
        String type = toTextChild400(node, "type").orElse("jar");
        String classifier = toTextChild400(node, "classifier").orElse(null);
        String aliased = switch (type) {
            case "test-jar" -> "tests";
            case "ejb-client" -> "client";
            case "javadoc" -> "javadoc";
            case "java-source" -> "sources";
            default -> null;
        };
        if (aliased != null) {
            type = "jar";
            if (classifier == null) {
                classifier = aliased;
            }
        }
        return Map.entry(
                new DependencyKey(
                        toTextChild400(node, "groupId").orElseThrow(missing("groupId")),
                        toTextChild400(node, "artifactId").orElseThrow(missing("artifactId")),
                        type,
                        classifier),
                new DependencyValue(
                        toTextChild400(node, "version").orElse(null),
                        toTextChild400(node, "scope").orElse(null),
                        toTextChild400(node, "systemPath").orElse(null),
                        toChildren400(node, "exclusions")
                                .findFirst()
                                .map(exclusions -> toChildren400(exclusions, "exclusion")
                                        .map(child -> new MavenDependencyName(
                                                toTextChild400(child, "groupId").orElseThrow(missing("exclusion.groupId")),
                                                toTextChild400(child, "artifactId").orElseThrow(missing("exclusion.artifactId"))))
                                        .toList())
                                .orElse(null),
                        toTextChild400(node, "optional").orElse(null),
                        trusted ? toCommentChecksum(node).orElse(null) : null));
    }

    private static Optional<String> toCommentChecksum(Node node) {
        List<String> matches = toChildren(node)
                .filter(child -> child.getNodeType() == Node.COMMENT_NODE)
                .map(Node::getNodeValue)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> text.startsWith(CHECKSUM_PREFIX))
                .map(text -> text.substring(CHECKSUM_PREFIX.length()).trim())
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("Multiple " + CHECKSUM_PREFIX + "* comments on dependency "
                    + toTextChild400(node, "groupId").orElse("?")
                    + ":" + toTextChild400(node, "artifactId").orElse("?")
                    + ":" + toTextChild400(node, "version").orElse("?")
                    + ": " + matches);
        }
        return matches.stream().findFirst();
    }

    private static SequencedMap<String, String> toQualifiedDependencies(Node node) {
        SequencedMap<String, String> entries = new LinkedHashMap<>();
        toChildren(node)
                .filter(child -> child.getNodeType() == Node.COMMENT_NODE)
                .map(Node::getNodeValue)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> text.startsWith("jenesis.pin"))
                .forEach(text -> {
                    for (String line : text.substring("jenesis.pin".length()).replace("&#45;", "-").split("\n")) {
                        String trimmed = line.trim().replaceAll("\\s+", " ");
                        if (trimmed.isEmpty()) {
                            continue;
                        }
                        int space = trimmed.indexOf(' ');
                        if (space < 1) {
                            continue;
                        }
                        String token = trimmed.substring(0, space).trim();
                        String value = trimmed.substring(space + 1).trim().replaceAll("\\s+", " ");
                        String guard = null;
                        if (value.endsWith("]")) {
                            int bracket = value.lastIndexOf('[');
                            if (bracket < 0) {
                                throw new IllegalArgumentException("Malformed jenesis.pin guard '"
                                        + value
                                        + "': expected <value> [<token>,<token>...]");
                            }
                            guard = Platform.of(
                                    value.substring(bracket + 1, value.length() - 1)).canonical();
                            value = value.substring(0, bracket).trim();
                        }
                        if (value.isEmpty()) {
                            continue;
                        }
                        String key;
                        int firstSlash = token.indexOf('/');
                        int secondSlash = firstSlash < 0 ? -1 : token.indexOf('/', firstSlash + 1);
                        if (firstSlash < 0) {
                            key = "main/module/" + token;
                        } else if (secondSlash < 0) {
                            if (firstSlash < 1 || firstSlash == token.length() - 1) {
                                throw new IllegalArgumentException("Malformed jenesis.pin token '"
                                        + token
                                        + "': expected <module>, <groupId>/<artifactId>,"
                                        + " or <group>/<repository>/<coordinate>");
                            }
                            key = "main/maven/" + token;
                        } else {
                            if (firstSlash < 1 || secondSlash == firstSlash + 1
                                    || secondSlash == token.length() - 1) {
                                throw new IllegalArgumentException("Malformed jenesis.pin token '"
                                        + token
                                        + "': expected <module>, <groupId>/<artifactId>,"
                                        + " or <group>/<repository>/<coordinate>");
                            }
                            key = token;
                        }
                        entries.put(guard == null ? key : key + "[" + guard + "]", value);
                    }
                });
        return entries;
    }

    private static String property(String text, Map<String, String> properties) {
        return property(text, properties, Set.of());
    }

    private static String property(String text, Map<String, String> properties, Set<String> previous) {
        if (text != null && text.contains("$")) {
            Matcher matcher = PROPERTY.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String property = matcher.group(2);
                String replacement = properties.get(property);
                if (replacement == null) {
                    replacement = System.getProperty(property);
                }
                if (replacement == null) {
                    // Maven leaves an undefined property as a literal rather than failing model assembly;
                    // it only errors if the value is actually used to fetch an artifact.
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                } else {
                    HashSet<String> duplicates = new HashSet<>(previous);
                    if (!duplicates.add(property)) {
                        throw new IllegalStateException("Circular property definition of: " + property);
                    }
                    // Quote the resolved value: a property may expand to text containing '$' or '\'
                    // (or a bare '${'), which appendReplacement would otherwise read as a group
                    // reference. Mirrors the undefined-property branch above.
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(property(replacement, properties, duplicates)));
                }
            }
            return matcher.appendTail(sb).toString();
        } else {
            return text;
        }
    }

    private static boolean isFloating(String version) {
        return version.equals("RELEASE")
                || version.equals("LATEST")
                || MavenDefaultVersionNegotiator.isRange(version);
    }

    private static MavenDependencyValue merge(MavenDependencyValue left, MavenDependencyValue right) {
        return right == null ? left : new MavenDependencyValue(
                left.version() == null ? right.version() : left.version(),
                left.scope() == null ? right.scope() : left.scope(),
                left.systemPath() == null ? right.systemPath() : left.systemPath(),
                left.exclusions() == null ? right.exclusions() : left.exclusions(),
                left.optional() == null ? right.optional() : left.optional(),
                left.checksum() == null ? right.checksum() : left.checksum());
    }

    static Supplier<IllegalStateException> missing(String property) {
        return () -> new IllegalStateException("Property not defined: " + property);
    }

    private record DependencyKey(String groupId,
                                 String artifactId,
                                 String type,
                                 String classifier) {
        private MavenDependencyKey resolve(Map<String, String> properties) {
            return new MavenDependencyKey(property(groupId, properties),
                    property(artifactId, properties),
                    property(type, properties),
                    property(classifier, properties));
        }
    }

    private record DependencyValue(String version,
                                   String scope,
                                   String systemPath,
                                   List<MavenDependencyName> exclusions,
                                   String optional,
                                   String checksum) {
        private MavenDependencyValue resolve(Map<String, String> properties) {
            return resolve(properties, true);
        }

        private MavenDependencyValue resolveManaged(Map<String, String> properties) {
            return resolve(properties, false);
        }

        private MavenDependencyValue resolve(Map<String, String> properties, boolean defaultScope) {
            String resolvedScope = property(scope, properties);
            String resolvedVersion = property(version, properties);
            MavenDependencyKey.validate("version", resolvedVersion);
            return new MavenDependencyValue(resolvedVersion,
                    resolvedScope == null && !defaultScope ? null : MavenDependencyScope.of(resolvedScope),
                    systemPath == null ? null : Path.of(property(systemPath, properties)),
                    exclusions == null ? null : exclusions.stream().map(exclusion -> new MavenDependencyName(
                            property(exclusion.groupId(), properties),
                            property(exclusion.artifactId(), properties))).toList(),
                    optional == null ? null : Boolean.valueOf(property(optional, properties)),
                    checksum
            );
        }
    }

    private record DependencyCoordinate(String groupId, String artifactId, String version) {
    }

    private record ParentCoordinate(String groupId, String artifactId, String version, String relativePath) {
    }

    private record UnresolvedPom(String groupId,
                                 String artifactId,
                                 String version,
                                 String packaging,
                                 String sourceDirectory,
                                 List<String> resourceDirectories,
                                 String testSourceDirectory,
                                 List<String> testResourceDirectories,
                                 List<String> modules,
                                 Map<String, String> properties,
                                 Map<DependencyKey, DependencyValue> managedDependencies,
                                 SequencedMap<DependencyKey, DependencyValue> dependencies,
                                 SequencedMap<String, String> qualifiedDependencies,
                                 List<License> licenses) {
    }

    private record ResolvedPom(Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
                               SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies,
                               List<License> licenses) {
    }

    private record ContextualPom(ResolvedPom pom,
                                 boolean root,
                                 MavenDependencyScope scope,
                                 Set<MavenDependencyName> exclusions,
                                 MavenDependencyKey origin,
                                 String originVersion) {
    }

    private record Traversal(SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies,
                             List<Resolver.Edge> edges,
                             SequencedMap<String, List<License>> licenses) {
    }

    private static class DependencyResolution {
        private final SequencedSet<String> observedVersions = new LinkedHashSet<>();
        private final Map<String, String> checksums = new HashMap<>();
        private String currentVersion;
        private MavenDependencyScope currentScope, widestScope;
        private Path systemPath;
        private List<MavenDependencyName> exclusions;
        private Boolean optional;
    }
}
