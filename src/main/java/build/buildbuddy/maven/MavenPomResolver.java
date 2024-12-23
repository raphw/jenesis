package build.buildbuddy.maven;

import build.buildbuddy.RepositoryItem;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class MavenPomResolver {

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

    public List<MavenDependency> dependencies(String groupId,
                                              String artifactId,
                                              String version,
                                              MavenDependencyScope scope) throws IOException {
        Map<DependencyCoordinate, UnresolvedPom> unresolved = new HashMap<>();
        Map<DependencyCoordinate, ResolvedPom> resolved = new HashMap<>();
        Map<DependencyKey, DependencyResolution> resolutions = new HashMap<>();
        SequencedSet<DependencyKey> dependencies = new LinkedHashSet<>(), conflicts;
        MavenVersionNegotiator negotiator = negotiatorSupplier.get();
        ContextualPom initial = new ContextualPom(resolveOrCached(groupId, artifactId, version, resolved, unresolved),
                true,
                scope,
                Set.of());
        do {
            dependencies.clear();
            conflicts = traverse(negotiator,
                    resolved,
                    unresolved,
                    resolutions,
                    initial.pom().managedDependencies(),
                    dependencies,
                    initial);
            Iterator<DependencyKey> it = conflicts.iterator();
            while (it.hasNext()) {
                DependencyKey key = it.next();
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
        return dependencies.stream().map(key -> {
            DependencyResolution resolution = resolutions.get(key);
            return new MavenDependency(key.groupId(),
                    key.artifactId(),
                    resolution.currentVersion,
                    key.type(),
                    key.classifier(),
                    resolution.widestScope,
                    null,
                    false);
        }).toList();
    }

    private SequencedSet<DependencyKey> traverse(MavenVersionNegotiator negotiator,
                                                 Map<DependencyCoordinate, ResolvedPom> resolved,
                                                 Map<DependencyCoordinate, UnresolvedPom> unresolved,
                                                 Map<DependencyKey, DependencyResolution> resolutions,
                                                 Map<DependencyKey, DependencyValue> managedDependencies,
                                                 SequencedSet<DependencyKey> dependencies,
                                                 ContextualPom current) throws IOException {
        SequencedSet<DependencyKey> conflicting = new LinkedHashSet<>();
        Queue<ContextualPom> queue = new ArrayDeque<>();
        do {
            for (Map.Entry<DependencyKey, DependencyValue> entry : current.pom().dependencies().entrySet()) {
                if (current.exclusions().contains(new DependencyName(
                        entry.getKey().groupId(),
                        entry.getKey().artifactId()))) {
                    continue;
                }
                DependencyValue override = managedDependencies.get(entry.getKey()), value;
                if (current.root()) {
                    value = entry.getValue().with(override);
                } else {
                    value = override == null ? entry.getValue() : override.with(entry.getValue());
                    value = value.with(current.pom().managedDependencies().get(entry.getKey()));
                }
                if (!current.root() && Boolean.parseBoolean(value.optional())) {
                    continue;
                }
                DependencyResolution resolution = resolutions.computeIfAbsent(
                        entry.getKey(),
                        key -> new DependencyResolution());
                MavenDependencyScope declaredScope = toScope(value.scope()), resolvedScope = switch (current.scope()) {
                    case null -> declaredScope;
                    case COMPILE -> switch (declaredScope) {
                        case COMPILE, RUNTIME -> declaredScope;
                        default -> null;
                    };
                    case PROVIDED, RUNTIME, TEST -> switch (declaredScope) {
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
                    Set<DependencyName> exclusions = current.exclusions();
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

    private UnresolvedPom assemble(InputStream inputStream,
                                   Set<DependencyCoordinate> children,
                                   Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException,
            SAXException,
            ParserConfigurationException {
        Document document;
        try (inputStream) {
            document = factory.newDocumentBuilder().parse(inputStream);
        }
        return switch (document.getDocumentElement().getNamespaceURI()) {
            case NAMESPACE_4_0_0 -> {
                DependencyCoordinate parent = toChildren400(document.getDocumentElement(), "parent")
                        .findFirst()
                        .map(node -> new DependencyCoordinate(
                                toTextChild400(node, "groupId").orElseThrow(missing("parent.groupId")),
                                toTextChild400(node, "artifactId").orElseThrow(missing("parent.artifactId")),
                                toTextChild400(node, "version").orElseThrow(missing("parent.version"))))
                        .orElse(null);
                Map<String, String> properties = new HashMap<>();
                Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
                SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
                if (parent != null) {
                    if (!children.add(new DependencyCoordinate(parent.groupId(), parent.artifactId(), parent.version()))) {
                        throw new IllegalStateException("Circular dependency to "
                                + parent.groupId() + ":" + parent.artifactId() + ":" + parent.version());
                    }
                    UnresolvedPom resolution = assembleOrCached(parent.groupId(),
                            parent.artifactId(),
                            parent.version(),
                            children,
                            unresolved);
                    properties.putAll(resolution.properties());
                    IMPLICITS.forEach(property -> {
                        String value = resolution.properties().get(property);
                        if (value != null) {
                            properties.put("parent." + property, value);
                            properties.put("project.parent." + property, value);
                        }
                    });
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
                yield new UnresolvedPom(properties, managedDependencies, dependencies);
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
                Optional<RepositoryItem> candidate = repository.fetch(groupId,
                        artifactId,
                        version,
                        "pom",
                        null,
                        null);
                pom = candidate.isPresent()
                        ? assemble(candidate.get().toInputStream(), children, poms)
                        : new UnresolvedPom(Map.of(), Map.of(), Collections.emptyNavigableMap());
            } catch (RuntimeException | SAXException | ParserConfigurationException e) {
                throw new IllegalStateException("Failed to resolve " + groupId + ":" + artifactId + ":" + version, e);
            }
            poms.put(coordinates, pom);
        }
        return pom;
    }

    private ResolvedPom resolve(UnresolvedPom pom, Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException {
        Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
        SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
        for (Map.Entry<DependencyKey, DependencyValue> entry : pom.managedDependencies().entrySet()) {
            DependencyKey key = entry.getKey().resolve(pom.properties());
            DependencyValue value = entry.getValue().resolve(pom.properties());
            if (Objects.equals("import", value.scope())) {
                UnresolvedPom imported = assembleOrCached(key.groupId(),
                        key.artifactId(),
                        value.version(),
                        new HashSet<>(),
                        unresolved);
                imported.managedDependencies().forEach((importKey, importValue) -> {
                    DependencyValue resolved = importValue.resolve(imported.properties());
                    if (!Objects.equals("import", resolved.scope())) {
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
                                        .map(child -> new DependencyName(
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

    static Supplier<IllegalStateException> missing(String property) {
        return () -> new IllegalStateException("Property not defined: " + property);
    }

    private record DependencyKey(String groupId,
                                 String artifactId,
                                 String type,
                                 String classifier) {
        private DependencyKey resolve(Map<String, String> properties) {
            return new DependencyKey(property(groupId, properties),
                    property(artifactId, properties),
                    property(type, properties),
                    property(classifier, properties));
        }
    }

    private record DependencyValue(String version,
                                   String scope,
                                   String systemPath,
                                   List<DependencyName> exclusions,
                                   String optional) {
        private DependencyValue resolve(Map<String, String> properties) {
            return new DependencyValue(property(version, properties),
                    property(scope, properties),
                    property(systemPath, properties),
                    exclusions == null ? null : exclusions.stream().map(exclusion -> new DependencyName(
                            property(exclusion.groupId(), properties),
                            property(exclusion.artifactId(), properties))).toList(),
                    property(optional, properties)
            );
        }

        private DependencyValue with(DependencyValue supplement) {
            if (supplement == null) {
                return this;
            }
            return new DependencyValue(version == null ? supplement.version() : version,
                    scope == null ? supplement.scope() : scope,
                    systemPath == null ? supplement.systemPath() : systemPath,
                    exclusions == null ? supplement.exclusions() : exclusions,
                    optional == null ? supplement.optional() : optional);
        }
    }

    record DependencyName(String groupId, String artifactId) {
    }

    private record DependencyCoordinate(String groupId, String artifactId, String version) {
    }

    private record UnresolvedPom(Map<String, String> properties,
                                 Map<DependencyKey, DependencyValue> managedDependencies,
                                 SequencedMap<DependencyKey, DependencyValue> dependencies) {
    }

    private record ResolvedPom(Map<DependencyKey, DependencyValue> managedDependencies,
                               SequencedMap<DependencyKey, DependencyValue> dependencies) {
    }

    private record ContextualPom(ResolvedPom pom,
                                 boolean root,
                                 MavenDependencyScope scope,
                                 Set<DependencyName> exclusions) {
    }

    private static class DependencyResolution {
        private final SequencedSet<String> observedVersions = new LinkedHashSet<>();
        private String currentVersion;
        private MavenDependencyScope widestScope, currentScope;
    }
}
