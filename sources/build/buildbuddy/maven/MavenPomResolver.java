package build.buildbuddy.maven;

import build.buildbuddy.RepositoryItem;
import build.buildbuddy.Resolver;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class MavenPomResolver implements Resolver {

    private static final String NAMESPACE_4_0_0 = "http://maven.apache.org/POM/4.0.0";
    private static final Set<String> IMPLICITS = Set.of("groupId", "artifactId", "version", "packaging");
    private static final Pattern PROPERTY = Pattern.compile("(\\$\\{([\\w.]+)})");

    private final MavenRepository repository;
    private final Supplier<MavenVersionNegotiator> negotiatorSupplier;
    private final DocumentBuilderFactory factory = MavenDefaultVersionNegotiator.toDocumentBuilderFactory();

    public MavenPomResolver(MavenRepository repository, Supplier<MavenVersionNegotiator> negotiatorSupplier) {
        this.repository = repository;
        this.negotiatorSupplier = negotiatorSupplier;
    }

    @Override
    public SequencedMap<String, String> dependencies(Executor executor, SequencedSet<String> coordinates)
            throws IOException {
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
        coordinates.forEach(coordinate -> {
            String[] elements = coordinate.split("/");
            switch (elements.length) {
                case 3 -> dependencies.put(
                        new MavenDependencyKey(elements[0], elements[1], "jar", null),
                        new MavenDependencyValue(elements[2], MavenDependencyScope.COMPILE, null, null, null));
                case 4 -> dependencies.put(
                        new MavenDependencyKey(elements[0], elements[1], elements[2], null),
                        new MavenDependencyValue(elements[3], MavenDependencyScope.COMPILE, null, null, null));
                case 5 -> dependencies.put(
                        new MavenDependencyKey(elements[0], elements[1], elements[2], elements[3]),
                        new MavenDependencyValue(elements[4], MavenDependencyScope.COMPILE, null, null, null));
                default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + coordinate);
            }
        });
        SequencedMap<String, String> resolved = new LinkedHashMap<>();
        dependencies(Map.of(), dependencies).entrySet().stream().map(dependency -> dependency.getKey().groupId()
                + "/" + dependency.getKey().artifactId()
                + (Objects.equals(dependency.getKey().type(), "jar") ? "" : "/" + dependency.getKey().type())
                + (dependency.getKey().classifier() == null ? "" : "/" + dependency.getKey().classifier())
                + "/" + dependency.getValue().version()).forEach(coordinate -> resolved.put(coordinate, ""));
        return resolved;
    }

    public SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies(
            Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies) throws IOException {
        return dependencies(new ContextualPom(new ResolvedPom(managedDependencies, dependencies),
                true,
                null,
                Set.of()), new HashMap<>(), new HashMap<>());
    }

    public SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies(String groupId,
                                                                               String artifactId,
                                                                               String version,
                                                                               MavenDependencyScope scope) throws IOException {
        Map<DependencyCoordinate, UnresolvedPom> unresolved = new HashMap<>();
        Map<DependencyCoordinate, ResolvedPom> resolved = new HashMap<>();
        return dependencies(new ContextualPom(resolveOrCached(groupId, artifactId, version, resolved, unresolved),
                true,
                scope,
                Set.of()), unresolved, resolved);

    }

    private SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies(
            ContextualPom initial,
            Map<DependencyCoordinate, UnresolvedPom> unresolved,
            Map<DependencyCoordinate, ResolvedPom> resolved) throws IOException {
        Map<MavenDependencyKey, DependencyResolution> resolutions = new HashMap<>();
        SequencedSet<MavenDependencyKey> dependencies = new LinkedHashSet<>(), conflicts;
        MavenVersionNegotiator negotiator = negotiatorSupplier.get();
        do {
            dependencies.clear();
            conflicts = traverse(negotiator,
                    resolved,
                    unresolved,
                    resolutions,
                    initial.pom().managedDependencies(),
                    dependencies,
                    initial);
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
                    String candidate = negotiator.resolve(key.groupId(),
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
                    resolution.optional));
        });
        return results;
    }

    private SequencedSet<MavenDependencyKey> traverse(MavenVersionNegotiator negotiator,
                                                      Map<DependencyCoordinate, ResolvedPom> resolved,
                                                      Map<DependencyCoordinate, UnresolvedPom> unresolved,
                                                      Map<MavenDependencyKey, DependencyResolution> resolutions,
                                                      Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
                                                      SequencedSet<MavenDependencyKey> dependencies,
                                                      ContextualPom current) throws IOException {
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
                if (resolution.currentVersion == null) {
                    version = resolution.currentVersion = negotiator.resolve(entry.getKey().groupId(),
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
                if (dependencies.add(entry.getKey())) {
                    ResolvedPom pom = resolveOrCached(entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            version,
                            resolved,
                            unresolved);
                    Set<MavenDependencyName> exclusions = current.exclusions();
                    if (value.exclusions() != null) {
                        exclusions = new HashSet<>(exclusions);
                        exclusions.addAll(value.exclusions());
                    }
                    queue.add(new ContextualPom(pom, false, scope, exclusions));
                }
            }
        } while ((current = queue.poll()) != null);
        return conflicting;
    }

    public SequencedMap<Path, MavenLocalPom> resolve(Path root) throws IOException {
        SequencedSet<Path> modules = new LinkedHashSet<>();
        Map<DependencyCoordinate, UnresolvedPom> unresolved = new HashMap<>();
        Map<Path, UnresolvedPom> paths = new HashMap<>();
        Queue<Path> queue = new ArrayDeque<>();
        Path current = root;
        do {
            if (modules.add(current)) {
                UnresolvedPom pom;
                try {
                    pom = assemble(Files.newInputStream(current.resolve("pom.xml")),
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
        modules.forEach(module -> {
            UnresolvedPom pom = paths.get(module);
            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
            SequencedMap<MavenDependencyKey, MavenDependencyValue> managedDependencies = new LinkedHashMap<>();
            pom.dependencies().forEach((key, value) -> dependencies.put(
                    key.resolve(pom.properties()),
                    value.resolve(pom.properties())));
            pom.managedDependencies().forEach((key, value) -> managedDependencies.put(
                    key.resolve(pom.properties()),
                    value.resolve(pom.properties())));
            results.put(root.relativize(module), new MavenLocalPom(property(pom.groupId(), pom.properties()),
                    property(pom.artifactId(), pom.properties()),
                    property(pom.version(), pom.properties()),
                    property(pom.sourceDirectory(), pom.properties()),
                    pom.resourceDirectories() == null ? null : pom.resourceDirectories().stream()
                            .map(resource -> property(resource, pom.properties()))
                            .toList(),
                    property(pom.testSourceDirectory(), pom.properties()),
                    pom.testResourceDirectories() == null ? null : pom.testResourceDirectories().stream()
                            .map(resource -> property(resource, pom.properties()))
                            .toList(),
                    dependencies,
                    managedDependencies));
        });
        return results;
    }

    private UnresolvedPom assemble(InputStream inputStream,
                                   boolean extended,
                                   Path path,
                                   Map<Path, UnresolvedPom> paths,
                                   Set<DependencyCoordinate> children,
                                   Map<DependencyCoordinate, UnresolvedPom> unresolved)
            throws IOException, SAXException, ParserConfigurationException {
        Document document;
        try (inputStream) {
            document = factory.newDocumentBuilder().parse(inputStream);
        }
        return switch (document.getDocumentElement().getNamespaceURI()) {
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
                                resolution = assemble(Files.newInputStream(pom),
                                        false,
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
                        resolution = assembleOrCached(parent.groupId(),
                                parent.artifactId(),
                                parent.version(),
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
                }
                IMPLICITS.forEach(property -> toChildren400(document.getDocumentElement(), property)
                        .findFirst()
                        .ifPresent(node -> {
                            properties.put(property, node.getTextContent());
                            properties.put("project." + property, node.getTextContent());
                        }));
                toChildren400(document.getDocumentElement(), "properties")
                        .limit(1)
                        .flatMap(MavenPomResolver::toChildren)
                        .filter(node -> node.getNodeType() == Node.ELEMENT_NODE)
                        .forEach(node -> properties.put(node.getLocalName(), node.getTextContent()));
                toChildren400(document.getDocumentElement(), "dependencyManagement")
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependencies"))
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependency"))
                        .map(MavenPomResolver::toDependency400)
                        .forEach(entry -> managedDependencies.put(entry.getKey(), entry.getValue()));
                toChildren400(document.getDocumentElement(), "dependencies")
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependency"))
                        .map(MavenPomResolver::toDependency400)
                        .forEach(entry -> dependencies.putLast(entry.getKey(), entry.getValue()));
                Node build = extended
                        ? toChildren400(document.getDocumentElement(), "build").findFirst().orElse(null)
                        : null;
                Node modules = extended
                        ? toChildren400(document.getDocumentElement(), "modules").findFirst().orElse(null)
                        : null;
                yield new UnresolvedPom(
                        toTextChild400(document.getDocumentElement(), "groupId").orElse(groupId),
                        toTextChild400(document.getDocumentElement(), "artifactId").orElse(artifactId),
                        toTextChild400(document.getDocumentElement(), "version").orElse(version),
                        build == null ? null : toTextChild400(build, "sourceDirectory").orElse(null),
                        build == null ? null : toChildren400(build, "resources").findFirst()
                                .map(node -> toChildren400(node, "resource")
                                        .map(Node::getTextContent)
                                        .toList())
                                .orElse(null),
                        build == null ? null : toTextChild400(build, "testSourceDirectory").orElse(null),
                        build == null ? null : toChildren400(build, "testResources").findFirst()
                                .map(node -> toChildren400(node, "testResource")
                                        .map(Node::getTextContent)
                                        .toList())
                                .orElse(null),
                        modules == null ? null : toChildren400(modules, "module")
                                .map(Node::getTextContent)
                                .toList(),
                        properties,
                        managedDependencies,
                        dependencies);
            }
            case null, default -> throw new IllegalArgumentException(
                    "Unknown namespace: " + document.getDocumentElement().getNamespaceURI());
        };
    }

    private UnresolvedPom assembleOrCached(String groupId,
                                           String artifactId,
                                           String version,
                                           Set<DependencyCoordinate> children,
                                           Map<DependencyCoordinate, UnresolvedPom> poms) throws IOException {
        DependencyCoordinate coordinates = new DependencyCoordinate(groupId, artifactId, version);
        UnresolvedPom pom = poms.get(coordinates);
        if (pom == null) {
            try {
                RepositoryItem candidate = repository.fetch(groupId,
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
                            Map.of(),
                            Map.of(),
                            Collections.emptyNavigableMap());
                } else {
                    pom = assemble(candidate.toInputStream(), false, null, null, children, poms);
                }
            } catch (RuntimeException | SAXException | ParserConfigurationException e) {
                throw new IllegalStateException("Failed to resolve " + groupId + ":" + artifactId + ":" + version, e);
            }
            poms.put(coordinates, pom);
        }
        return pom;
    }

    private ResolvedPom resolve(UnresolvedPom pom,
                                Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException {
        Map<MavenDependencyKey, MavenDependencyValue> managedDependencies = new HashMap<>();
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
        for (Map.Entry<DependencyKey, DependencyValue> entry : pom.managedDependencies().entrySet()) {
            MavenDependencyKey key = entry.getKey().resolve(pom.properties());
            MavenDependencyValue value = entry.getValue().resolve(pom.properties());
            if (value.scope() == MavenDependencyScope.IMPORT) {
                UnresolvedPom imported = assembleOrCached(key.groupId(),
                        key.artifactId(),
                        value.version(),
                        new HashSet<>(),
                        unresolved);
                imported.managedDependencies().forEach((importKey, importValue) -> {
                    MavenDependencyValue resolved = importValue.resolve(imported.properties());
                    if (resolved.scope() != MavenDependencyScope.IMPORT) {
                        managedDependencies.putIfAbsent(importKey.resolve(imported.properties()), resolved);
                    }
                });
            } else {
                managedDependencies.put(key, value);
            }
        }
        pom.dependencies().forEach((key, value) -> dependencies.put(
                key.resolve(pom.properties()),
                value.resolve(pom.properties())));
        return new ResolvedPom(managedDependencies, dependencies);
    }

    private ResolvedPom resolveOrCached(String groupId,
                                        String artifactId,
                                        String version,
                                        Map<DependencyCoordinate, ResolvedPom> resolved,
                                        Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException {
        DependencyCoordinate coordinates = new DependencyCoordinate(groupId, artifactId, version);
        ResolvedPom pom = resolved.get(coordinates);
        if (pom == null) {
            try {
                pom = resolve(assembleOrCached(groupId,
                        artifactId,
                        version,
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
                && Objects.equals(child.getNamespaceURI(), NAMESPACE_4_0_0));
    }

    private static Optional<String> toTextChild400(Node node, String localName) {
        return toChildren400(node, localName).map(Node::getTextContent).findFirst();
    }

    private static Map.Entry<DependencyKey, DependencyValue> toDependency400(Node node) {
        return Map.entry(
                new DependencyKey(
                        toTextChild400(node, "groupId").orElseThrow(missing("groupId")),
                        toTextChild400(node, "artifactId").orElseThrow(missing("artifactId")),
                        toTextChild400(node, "type").orElse("jar"),
                        toTextChild400(node, "classifier").orElse(null)),
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
                        toTextChild400(node, "optional").orElse(null)));
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
                    throw new IllegalStateException("Property not defined: " + property);
                } else {
                    HashSet<String> duplicates = new HashSet<>(previous);
                    if (!duplicates.add(property)) {
                        throw new IllegalStateException("Circular property definition of: " + property);
                    }
                    matcher.appendReplacement(sb, property(replacement, properties, duplicates));
                }
            }
            return matcher.appendTail(sb).toString();
        } else {
            return text;
        }
    }

    private static MavenDependencyScope toScope(String scope) {
        return switch (scope) {
            case "compile" -> MavenDependencyScope.COMPILE;
            case "provided" -> MavenDependencyScope.PROVIDED;
            case "runtime" -> MavenDependencyScope.RUNTIME;
            case "test" -> MavenDependencyScope.TEST;
            case "system" -> MavenDependencyScope.SYSTEM;
            case "import" -> MavenDependencyScope.IMPORT;
            case null -> MavenDependencyScope.COMPILE;
            default -> throw new IllegalArgumentException("");
        };
    }

    private static MavenDependencyValue merge(MavenDependencyValue left, MavenDependencyValue right) {
        return right == null ? left : new MavenDependencyValue(
                left.version() == null ? right.version() : left.version(),
                left.scope() == null ? right.scope() : left.scope(),
                left.systemPath() == null ? right.systemPath() : left.systemPath(),
                left.exclusions() == null ? right.exclusions() : left.exclusions(),
                left.optional() == null ? right.optional() : left.optional());
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
                                   String optional) {
        private MavenDependencyValue resolve(Map<String, String> properties) {
            return new MavenDependencyValue(property(version, properties),
                    toScope(property(scope, properties)),
                    systemPath == null ? null : Path.of(property(systemPath, properties)),
                    exclusions == null ? null : exclusions.stream().map(exclusion -> new MavenDependencyName(
                            property(exclusion.groupId(), properties),
                            property(exclusion.artifactId(), properties))).toList(),
                    optional == null ? null : Boolean.valueOf(property(optional, properties))
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
                                 String sourceDirectory,
                                 List<String> resourceDirectories,
                                 String testSourceDirectory,
                                 List<String> testResourceDirectories,
                                 List<String> modules,
                                 Map<String, String> properties,
                                 Map<DependencyKey, DependencyValue> managedDependencies,
                                 SequencedMap<DependencyKey, DependencyValue> dependencies) {
    }

    private record ResolvedPom(Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
                               SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies) {
    }

    private record ContextualPom(ResolvedPom pom,
                                 boolean root,
                                 MavenDependencyScope scope,
                                 Set<MavenDependencyName> exclusions) {
    }

    private static class DependencyResolution {
        private final SequencedSet<String> observedVersions = new LinkedHashSet<>();
        private String currentVersion;
        private MavenDependencyScope currentScope, widestScope;
        private Path systemPath;
        private List<MavenDependencyName> exclusions;
        private Boolean optional;
    }
}
