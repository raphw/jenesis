package build.buildbuddy;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

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

public class MavenPomResolver { // TODO: resolve BOMs

    private static final String NAMESPACE_4_0_0 = "http://maven.apache.org/POM/4.0.0";
    private static final Set<String> IMPLICITS = Set.of("groupId", "artifactId", "version", "packaging");
    private static final Pattern PROPERTY = Pattern.compile("(\\$\\{([\\w.]+)})");

    private final MavenRepository repository;
    private final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    public MavenPomResolver(MavenRepository repository) {
        this.repository = repository;
        factory.setNamespaceAware(true);
    }

    public List<MavenDependency> dependencies(String groupId, String artifactId, String version) throws IOException {
        SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
        Map<DependencyCoordinates, ResolvedPom> poms = new HashMap<>();
        Set<DependencyKey> previous = new HashSet<>();
        Queue<ContextualPom> queue = new ArrayDeque<>(Set.of(new ContextualPom(
                doResolveOrCached(groupId, artifactId, version, new HashSet<>(), poms),
                MavenDependencyScope.COMPILE,
                Set.of(),
                Map.of())));
        do {
            ContextualPom current = queue.remove();
            Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>(current.pom().managedDependencies());
            managedDependencies.putAll(current.managedDependencies());
            for (Map.Entry<DependencyKey, DependencyValue> entry : current.pom().dependencies().entrySet()) {
                if (!current.exclusions().contains(new DependencyExclusion(
                        entry.getKey().groupId(),
                        entry.getKey().artifactId())) && previous.add(entry.getKey())) {
                    dependencies.put(entry.getKey(), managedDependencies.getOrDefault(entry.getKey(), entry.getValue()));
                    MavenDependencyScope scope = switch (current.scope()) {
                        case COMPILE -> switch (entry.getValue().scope()) {
                            case COMPILE, RUNTIME -> entry.getValue().scope();
                            default -> null;
                        };
                        case PROVIDED, RUNTIME, TEST -> switch (entry.getValue().scope()) {
                            case COMPILE, RUNTIME -> current.scope();
                            default -> null;
                        };
                        case SYSTEM, IMPORT -> null;
                    };
                    if (scope != null) {
                        Set<DependencyExclusion> exclusions;
                        if (entry.getValue().exclusions() == null || entry.getValue().exclusions().isEmpty()) {
                            exclusions = current.exclusions();
                        } else {
                            exclusions = new HashSet<>(current.exclusions());
                            exclusions.addAll(entry.getValue().exclusions());
                        }
                        queue.add(new ContextualPom(doResolveOrCached(entry.getKey().groupId(),
                                entry.getKey().artifactId(),
                                entry.getValue().version(),
                                new HashSet<>(),
                                poms), scope, exclusions, managedDependencies));
                    }
                }
            }
        } while (!queue.isEmpty());
        return dependencies.entrySet().stream().map(entry -> new MavenDependency(
                entry.getKey().groupId(),
                entry.getKey().artifactId(),
                entry.getValue().version(),
                entry.getKey().type(),
                entry.getKey().classifier(),
                entry.getValue().scope(),
                Objects.equals(entry.getValue().optional(), true))).toList();
    }

    private ResolvedPom doResolve(InputStream inputStream,
                                  Set<DependencyCoordinates> children,
                                  Map<DependencyCoordinates, ResolvedPom> poms) throws IOException,
            SAXException,
            ParserConfigurationException {
        Document document;
        try (inputStream) {
            document = factory.newDocumentBuilder().parse(inputStream);
        }
        return switch (document.getDocumentElement().getNamespaceURI()) {
            case NAMESPACE_4_0_0 -> {
                DependencyCoordinates parent = toChildren400(document.getDocumentElement(), "parent")
                        .findFirst()
                        .map(node -> new DependencyCoordinates(
                                toTextChild400(node, "groupId", Map.of()).orElseThrow(missing("parent.groupId")),
                                toTextChild400(node, "artifactId", Map.of()).orElseThrow(missing("parent.artifactId")),
                                toTextChild400(node, "version", Map.of()).orElseThrow(missing("parent.version"))))
                        .orElse(null);
                Map<String, String> properties = new HashMap<>();
                Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
                SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
                if (parent != null) {
                    if (!children.add(new DependencyCoordinates(parent.groupId(), parent.artifactId(), parent.version()))) {
                        throw new IllegalStateException("Circular dependency to "
                                + parent.groupId() + ":" + parent.artifactId() + ":" + parent.version());
                    }
                    ResolvedPom resolution = doResolveOrCached(parent.groupId(),
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
                        .map(node -> toDependency400(node, properties))
                        .forEach(entry -> managedDependencies.put(entry.getKey(), entry.getValue()));
                toChildren400(document.getDocumentElement(), "dependencies")
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependency"))
                        .map(node -> toDependency400(node, properties))
                        .forEach(entry -> dependencies.putLast(
                                entry.getKey(),
                                managedDependencies.getOrDefault(entry.getKey(), entry.getValue())));
                yield new ResolvedPom(properties, managedDependencies, dependencies);
            }
            case null, default -> throw new IllegalArgumentException(
                    "Unknown namespace: " + document.getDocumentElement().getNamespaceURI());
        };
    }

    private ResolvedPom doResolveOrCached(String groupId,
                                          String artifactId,
                                          String version,
                                          Set<DependencyCoordinates> children,
                                          Map<DependencyCoordinates, ResolvedPom> poms) throws IOException {
        DependencyCoordinates coordinates = new DependencyCoordinates(groupId, artifactId, version);
        ResolvedPom pom = poms.get(coordinates);
        if (pom == null) {
            try {
                pom = doResolve(repository.download(groupId,
                        artifactId,
                        version,
                        null,
                        "pom"), children, poms);
            } catch (RuntimeException | SAXException | ParserConfigurationException e) {
                throw new IllegalStateException("Failed to resolve " + groupId + ":" + artifactId + ":" + version, e);
            }
            poms.put(coordinates, pom);
        }
        return pom;
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

    private static Optional<String> toTextChild400(Node node, String localName, Map<String, String> properties) {
        return toChildren400(node, localName).map(Node::getTextContent).findFirst().map(value -> property(value, properties));
    }

    private static Map.Entry<DependencyKey, DependencyValue> toDependency400(Node node, Map<String, String> properties) {
        return Map.entry(
                new DependencyKey(
                        toTextChild400(node, "groupId", properties).orElseThrow(missing("groupId")),
                        toTextChild400(node, "artifactId", properties).orElseThrow(missing("artifactId")),
                        toTextChild400(node, "type", properties).orElse("jar"),
                        toTextChild400(node, "classifier", properties).orElse(null)),
                new DependencyValue(
                        toTextChild400(node, "version", properties).orElse(null),
                        toTextChild400(node, "scope", properties).map(scope -> {
                            if (!scope.toLowerCase().endsWith(scope.toLowerCase())) {
                                throw new IllegalArgumentException("Unknown scope " + scope);
                            }
                            return MavenDependencyScope.valueOf(scope.toUpperCase());
                        }).orElse(MavenDependencyScope.COMPILE),
                        toChildren400(node, "exclusions")
                                .findFirst()
                                .map(exclusions -> toChildren400(exclusions, "exclusion")
                                        .map(child -> new DependencyExclusion(
                                                toTextChild400(child, "groupId", properties).orElseThrow(missing("exclusion.groupId")),
                                                toTextChild400(child, "artifactId", properties).orElseThrow(missing("exclusion.artifactId"))))
                                        .toList())
                                .orElse(null),
                        toTextChild400(node, "optional", properties).map(Boolean::valueOf).orElse(null)));
    }


    private static String property(String text, Map<String, String> properties) {
        return property(text, properties, Set.of());
    }

    private static String property(String text, Map<String, String> properties, Set<String> previous) {
        if (text.contains("$")) {
            Matcher matcher = PROPERTY.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String property = matcher.group(2);
                String replacement = properties.get(property);
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

    private static Supplier<IllegalStateException> missing(String property) {
        return () -> new IllegalStateException("Property not defined: " + property);
    }

    private record DependencyKey(String groupId,
                                 String artifactId,
                                 String type,
                                 String classifier) {
    }

    private record DependencyValue(String version,
                                   MavenDependencyScope scope,
                                   List<DependencyExclusion> exclusions,
                                   Boolean optional) {
    }

    private record DependencyExclusion(String groupId, String artifactId) {
    }

    private record DependencyCoordinates(String groupId, String artifactId, String version) {
    }

    private record ResolvedPom(Map<String, String> properties,
                               Map<DependencyKey, DependencyValue> managedDependencies,
                               SequencedMap<DependencyKey, DependencyValue> dependencies) {
    }

    private record ContextualPom(ResolvedPom pom,
                                 MavenDependencyScope scope,
                                 Set<DependencyExclusion> exclusions,
                                 Map<DependencyKey, DependencyValue> managedDependencies) {
    }
}
