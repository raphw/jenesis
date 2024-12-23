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

public class MavenPomResolver { // TODO: scope resolution, BOMs

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
        Map<DependencyCoordinates, ResolvedPom> poms = new HashMap<>();
        SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
        Set<DependencyKey> previous = new HashSet<>();
        ResolvedPom root = doResolveOrCached(groupId, artifactId, version, new HashSet<>(), poms);
        Queue<ContextualPom> queue = new ArrayDeque<>();
        ContextualPom current = new ContextualPom(root, Set.of(), Map.of());
        do {
            Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>(current.pom().managedDependencies());
            managedDependencies.putAll(current.managedDependencies());
            for (Map.Entry<DependencyKey, DependencyValue> entry : current.pom().dependencies().entrySet()) {
                if (!current.exclusions().contains(new DependencyExclusion(
                        entry.getKey().groupId(),
                        entry.getKey().artifactId())) && previous.add(entry.getKey())) {
                    dependencies.put(entry.getKey(), managedDependencies.getOrDefault(entry.getKey(), entry.getValue()));
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
                            poms), exclusions, managedDependencies));
                }
            }
        } while ((current = queue.poll()) != null);
        return dependencies.entrySet().stream().map(entry -> new MavenDependency(
                entry.getKey().groupId(),
                entry.getKey().artifactId(),
                entry.getValue().version(),
                entry.getKey().type(),
                entry.getKey().classifier(),
                Objects.equals(entry.getValue().optional(), true))).toList();
    }

    private ResolvedPom doResolve(InputStream inputStream,
                                  Set<DependencyCoordinates> children,
                                  Map<DependencyCoordinates, ResolvedPom> poms) throws IOException {
        // TODO: order of dependencies?
        Document document;
        try (inputStream) {
            document = factory.newDocumentBuilder().parse(inputStream);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return switch (document.getDocumentElement().getNamespaceURI()) {
            case NAMESPACE_4_0_0 -> {
                DependencyCoordinates parent = toChildren400(document.getDocumentElement(), "parent")
                        .findFirst()
                        .map(node -> new DependencyCoordinates(
                                toChildren400(node, "groupId").map(Node::getTextContent).findFirst().orElseThrow(missing("parent.groupId")),
                                toChildren400(node, "artifactId").map(Node::getTextContent).findFirst().orElseThrow(missing("parent.artifactId")),
                                toChildren400(node, "version").map(Node::getTextContent).findFirst().orElseThrow(missing("parent.version"))))
                        .orElse(null);
                Map<String, String> properties = new HashMap<>();
                Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
                SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
                if (parent != null) {
                    if (!children.add(new DependencyCoordinates(parent.groupId(), parent.artifactId(), parent.version()))) {
                        throw new IllegalStateException("Circular dependency to " + parent.groupId() + ":" + parent.artifactId() + ":" + parent.version());
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
                        .forEach(entry -> dependencies.put(
                                entry.getKey(),
                                managedDependencies.getOrDefault(entry.getKey(), entry.getValue())));
                yield new ResolvedPom(properties, managedDependencies, dependencies);
            }
            case null, default ->
                    throw new IllegalArgumentException("Unknown namespace: " + document.getDocumentElement().getNamespaceURI());
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
            } catch (RuntimeException e) {
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
        return toChildren(node).filter(child -> Objects.equals(child.getLocalName(), localName) && Objects.equals(child.getNamespaceURI(), NAMESPACE_4_0_0));
    }

    private static Map.Entry<DependencyKey, DependencyValue> toDependency400(Node node, Map<String, String> properties) {
        return Map.entry(
                new DependencyKey(
                        toChildren400(node, "groupId").map(Node::getTextContent).findFirst().map(value -> toValue(value, properties)).orElseThrow(missing("groupId")),
                        toChildren400(node, "artifactId").map(Node::getTextContent).findFirst().map(value -> toValue(value, properties)).orElseThrow(missing("artifactId")),
                        toChildren400(node, "type").map(Node::getTextContent).findFirst().map(value -> toValue(value, properties)).orElse("jar"),
                        toChildren400(node, "classifier").map(Node::getTextContent).findFirst().map(value -> toValue(value, properties)).orElse(null)),
                new DependencyValue(
                        toChildren400(node, "version").map(Node::getTextContent).findFirst().map(value -> toValue(value, properties)).orElse(null),
                        toChildren400(node, "scope").map(Node::getTextContent).findFirst().map(value -> toValue(value, properties)).orElse("compile"),
                        toChildren400(node, "exclusions")
                                .findFirst()
                                .map(exclusions -> toChildren400(exclusions, "exclusion")
                                        .map(child -> new DependencyExclusion(
                                                toChildren400(child, "groupId").map(Node::getTextContent).map(value -> toValue(value, properties)).findFirst().orElseThrow(missing("exclusion.groupId")),
                                                toChildren400(child, "artifactId").map(Node::getTextContent).map(value -> toValue(value, properties)).findFirst().orElseThrow(missing("exclusion.artifactId"))))
                                        .toList())
                                .orElse(null),
                        toChildren400(node, "optional").findFirst().map(Node::getTextContent).map(value -> toValue(value, properties)).map(Boolean::valueOf).orElse(null)));
    }


    private static String toValue(String text, Map<String, String> properties) {
        return toValue(text, properties, Set.of());
    }

    private static String toValue(String text, Map<String, String> properties, Set<String> previous) {
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
                    matcher.appendReplacement(sb, toValue(replacement, properties, duplicates));
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
                                   String scope,
                                   List<DependencyExclusion> exclusions,
                                   Boolean optional) {
    }

    private record DependencyExclusion(String groupId, String artifactId) {
    }

    private record DependencyCoordinates(String groupId, String artifactId, String version) {
    }

    private record ResolvedPom(Map<String, String> properties,
                               Map<DependencyKey, DependencyValue> managedDependencies,
                               Map<DependencyKey, DependencyValue> dependencies) {
    }

    private record ContextualPom(ResolvedPom pom,
                                 Set<DependencyExclusion> exclusions,
                                 Map<DependencyKey, DependencyValue> managedDependencies) {
    }
}
