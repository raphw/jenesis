package build.buildbuddy;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
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
    private final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    public MavenPomResolver(MavenRepository repository) {
        this.repository = repository;
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<MavenDependency> dependencies(String groupId,
                                              String artifactId,
                                              String version,
                                              MavenDependencyScope scope) throws IOException {
        SequencedMap<DependencyKey, DependencyInclusion> dependencies = new LinkedHashMap<>();
        Map<DependencyKey, MavenDependencyScope> overrides = new HashMap<>();
        Map<DependencyCoordinate, UnresolvedPom> poms = new HashMap<>();
        Map<DependencyName, Metadata> metadata = new HashMap<>();
        Queue<ContextualPom> queue = new ArrayDeque<>(Set.of(new ContextualPom(
                resolve(assembleOrCached(groupId, artifactId, version, new HashSet<>(), poms), poms, metadata, null),
                scope,
                Set.of(),
                Map.of())));
        do {
            ContextualPom current = queue.remove();
            Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>(current.pom().managedDependencies());
            managedDependencies.putAll(current.managedDependencies());
            for (Map.Entry<DependencyKey, DependencyValue> entry : current.pom().dependencies().entrySet()) {
                if (!current.exclusions().contains(new DependencyName(
                        entry.getKey().groupId(),
                        entry.getKey().artifactId()))) {
                    DependencyValue primary = current.managedDependencies().get(entry.getKey()), value = primary == null
                            ? entry.getValue().with(current.pom().managedDependencies().get(entry.getKey()))
                            : primary.with(entry.getValue());
                    boolean optional = switch (value.optional()) {
                        case "true" -> true;
                        case "false" -> false;
                        case null -> false;
                        default -> throw new IllegalStateException("Unexpected value: " + value);
                    };
                    if (optional && current.pom().origin() != null) {
                        continue;
                    }
                    MavenDependencyScope actual = toScope(value.scope()), derived = switch (current.scope()) {
                        case null -> actual == MavenDependencyScope.IMPORT ? null : actual;
                        case COMPILE -> switch (actual) {
                            case COMPILE, RUNTIME -> actual;
                            default -> null;
                        };
                        case PROVIDED, RUNTIME, TEST -> switch (actual) {
                            case COMPILE, RUNTIME -> current.scope();
                            default -> null;
                        };
                        case SYSTEM, IMPORT -> null;
                    };
                    if (derived == null) {
                        continue;
                    }
                    DependencyInclusion previous = dependencies.get(entry.getKey());
                    if (previous != null) {
                        if (previous.scope().ordinal() > overrides.getOrDefault(entry.getKey(), derived).ordinal()) {
                            overrides.put(entry.getKey(), derived);
                        }
                        continue;
                    }
                    String resolved = toVersion(metadata,
                            entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            value.version());
                    dependencies.put(entry.getKey(), new DependencyInclusion(resolved,
                            optional,
                            derived,
                            value.systemPath() == null ? null : Path.of(value.systemPath()),
                            new HashSet<>()));
                    if (current.pom().origin() != null) {
                        dependencies.get(current.pom().origin()).transitives().add(entry.getKey());
                    }
                    Set<DependencyName> exclusions;
                    if (value.exclusions() == null || value.exclusions().isEmpty()) {
                        exclusions = current.exclusions();
                    } else {
                        exclusions = new HashSet<>(current.exclusions());
                        exclusions.addAll(value.exclusions());
                    }
                    queue.add(new ContextualPom(resolve(assembleOrCached(entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            resolved,
                            new HashSet<>(),
                            poms), poms, metadata, entry.getKey()), derived, exclusions, managedDependencies));
                }
            }
        } while (!queue.isEmpty());
        Queue<DependencyKey> keys = new ArrayDeque<>(overrides.keySet());
        while (!keys.isEmpty()) {
            DependencyKey key = keys.remove();
            Set<DependencyKey> transitives = dependencies.get(key).transitives();
            transitives.forEach(transitive -> {
                MavenDependencyScope current = overrides.get(transitive), candidate = overrides.get(key);
                if (current == null || candidate.ordinal() < current.ordinal()) {
                    overrides.put(transitive, candidate);
                }
            });
            keys.addAll(transitives);
        }
        return dependencies.entrySet().stream().map(entry -> new MavenDependency(entry.getKey().groupId(),
                entry.getKey().artifactId(),
                entry.getValue().version(),
                entry.getKey().type(),
                entry.getKey().classifier(),
                overrides.getOrDefault(entry.getKey(), entry.getValue().scope()),
                entry.getValue().path(),
                entry.getValue().optional())).toList();
    }

    private UnresolvedPom assemble(InputStream inputStream,
                                   Set<DependencyCoordinate> children,
                                   Map<DependencyCoordinate, UnresolvedPom> poms) throws IOException,
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
                            poms);
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
        if (version == null) {
            throw new IllegalArgumentException("No version specified for " + groupId + ":" + artifactId);
        }
        DependencyCoordinate coordinates = new DependencyCoordinate(groupId, artifactId, version);
        UnresolvedPom pom = poms.get(coordinates);
        if (pom == null) {
            try {
                pom = assemble(repository.fetch(groupId,
                        artifactId,
                        version,
                        "pom",
                        null,
                        null).toInputStream(), children, poms);
            } catch (RuntimeException | SAXException | ParserConfigurationException e) {
                throw new IllegalStateException("Failed to resolve " + groupId + ":" + artifactId + ":" + version, e);
            }
            poms.put(coordinates, pom);
        }
        return pom;
    }

    private ResolvedPom resolve(UnresolvedPom pom,
                                Map<DependencyCoordinate, UnresolvedPom> poms,
                                Map<DependencyName, Metadata> metadata,
                                DependencyKey origin) throws IOException {
        Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
        for (Map.Entry<DependencyKey, DependencyValue> entry : pom.managedDependencies().entrySet()) {
            DependencyKey key = entry.getKey().resolve(pom.properties());
            DependencyValue value = entry.getValue().resolve(pom.properties());
            if (Objects.equals(value.scope(), "import") && Objects.equals(key.type(), "pom")) {
                UnresolvedPom imported = assembleOrCached(key.groupId(),
                        key.artifactId(),
                        toVersion(metadata, key.groupId(), key.artifactId(), value.version()),
                        new HashSet<>(),
                        poms);
                imported.managedDependencies().forEach((importedKey, importedValue) -> managedDependencies.putIfAbsent(
                        importedKey.resolve(imported.properties()),
                        importedValue.resolve(imported.properties())));
            } else {
                managedDependencies.put(key, value);
            }
        }
        SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
        pom.dependencies().forEach((key, value) -> dependencies.putLast(
                key.resolve(pom.properties()),
                value.resolve(pom.properties())));
        return new ResolvedPom(managedDependencies, dependencies, origin);
    }

    private static Stream<Node> toChildren(Node node) {
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

    private static Supplier<IllegalStateException> missing(String property) {
        return () -> new IllegalStateException("Property not defined: " + property);
    }

    private String toVersion(Map<DependencyName, Metadata> cache, String groupId, String artifactId, String version) throws IOException {
        return switch (version) { // TODO: what to do with version ranges?
            case "RELEASE" -> toMetadata(cache, groupId, artifactId).release();
            case "LATEST" -> toMetadata(cache, groupId, artifactId).latest();
            default -> version;
        };
    }

    private Metadata toMetadata(Map<DependencyName, Metadata> cache, String groupId, String artifactId) throws IOException {
        Metadata metadata = cache.get(new DependencyName(groupId, artifactId));
        if (metadata == null) {
            Document document;
            try (InputStream inputStream = repository.fetchMetadata(groupId, artifactId, null).toInputStream()) {
                document = factory.newDocumentBuilder().parse(inputStream);
            } catch (SAXException | ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
            metadata = switch (document.getDocumentElement().getAttribute("modelVersion")) {
                case "1.1.0" -> {
                    Node versioning = toChildren(document.getDocumentElement())
                            .filter(node -> Objects.equals(node.getLocalName(), "versioning"))
                            .findFirst()
                            .orElseThrow(missing("versioning"));
                    yield new Metadata(
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "latest"))
                                    .findFirst()
                                    .map(Node::getTextContent)
                                    .orElseThrow(missing("latest")),
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "release"))
                                    .findFirst()
                                    .map(Node::getTextContent)
                                    .orElseThrow(missing("release")),
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "versions"))
                                    .findFirst()
                                    .stream()
                                    .flatMap(MavenPomResolver::toChildren)
                                    .filter(node -> Objects.equals(node.getLocalName(), "version"))
                                    .map(Node::getTextContent)
                                    .toList());
                }
                case null, default -> throw new IllegalStateException("Unknown model version: " +
                        document.getDocumentElement().getAttribute("modelVersion"));
            };
            cache.put(new DependencyName(groupId, artifactId), metadata);
        }
        return metadata;
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

    private record DependencyInclusion(String version,
                                       boolean optional,
                                       MavenDependencyScope scope,
                                       Path path,
                                       Set<DependencyKey> transitives) {
    }

    private record DependencyName(String groupId, String artifactId) {
    }

    private record DependencyCoordinate(String groupId, String artifactId, String version) {
    }

    private record UnresolvedPom(Map<String, String> properties,
                                 Map<DependencyKey, DependencyValue> managedDependencies,
                                 SequencedMap<DependencyKey, DependencyValue> dependencies) {
    }

    private record ResolvedPom(Map<DependencyKey, DependencyValue> managedDependencies,
                               SequencedMap<DependencyKey, DependencyValue> dependencies,
                               DependencyKey origin) {
    }

    private record ContextualPom(ResolvedPom pom,
                                 MavenDependencyScope scope,
                                 Set<DependencyName> exclusions,
                                 Map<DependencyKey, DependencyValue> managedDependencies) {
    }

    private record Metadata(String latest, String release, List<String> versions) {
    }
}
