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
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class MavenPomResolver {

    private static final String NAMESPACE_4_0_0 = "http://maven.apache.org/POM/4.0.0";

    private final MavenRepository repository;
    private final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    public MavenPomResolver(MavenRepository repository) {
        this.repository = repository;
        factory.setNamespaceAware(true);
    }

    public List<MavenDependency> dependencies(String groupId, String artifactId, String version) throws IOException {
        SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
        Set<DependencyKey> previous = new HashSet<>();
        ResolvedPom root = doResolve(repository.download(groupId,
                artifactId,
                version,
                null,
                "pom"), new HashSet<>());
        Queue<DependencyElement> queue = new ArrayDeque<>();
        DependencyElement current = new DependencyElement(root, Set.of(), Map.of());
        do {
            for (Map.Entry<DependencyKey, DependencyValue> entry : current.pom().dependencies().entrySet()) {
                if (!current.exclusions().contains(new DependencyExclusion(
                        entry.getKey().groupId(),
                        entry.getKey().artifactId())) && previous.add(entry.getKey())) {
                    dependencies.put(entry.getKey(), entry.getValue());
                    Set<DependencyExclusion> exclusions;
                    if (entry.getValue().exclusions() == null || entry.getValue().exclusions().isEmpty()) {
                        exclusions = current.exclusions();
                    } else {
                        exclusions = new HashSet<>(current.exclusions());
                        exclusions.addAll(entry.getValue().exclusions());
                    }
                    queue.add(new DependencyElement(doResolve(repository.download(entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            entry.getValue().version(),
                            null,
                            "pom"), new HashSet<>()), exclusions, Map.of())); // TODO: dependency management
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

    private ResolvedPom doResolve(InputStream inputStream, Set<DependencyCoordinates> children) throws IOException {
        // TODO: implicit properties.
        // TODO: resolve properties
        // TODO: cache resolved poms
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
                                toChildren400(node, "groupId").map(Node::getTextContent).findFirst().orElseThrow(),
                                toChildren400(node, "artifactId").map(Node::getTextContent).findFirst().orElseThrow(),
                                toChildren400(node, "version").map(Node::getTextContent).findFirst().orElseThrow()))
                        .orElse(null);
                Map<String, String> properties = new HashMap<>();
                Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
                SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
                if (parent != null) {
                    if (!children.add(new DependencyCoordinates(parent.groupId(), parent.artifactId(), parent.version()))) {
                        throw new IllegalStateException("Circular dependency to " + parent);
                    }
                    ResolvedPom resolution = doResolve(repository.download(parent.groupId(),
                            parent.artifactId(),
                            parent.version(),
                            null,
                            "pom"), children);
                    properties.putAll(resolution.properties());
                    managedDependencies.putAll(resolution.managedDependencies());
                    dependencies.putAll(resolution.dependencies());
                }
                toChildren400(document.getDocumentElement(), "properties")
                        .limit(1)
                        .flatMap(MavenPomResolver::toChildren)
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
                        .forEach(entry -> dependencies.putLast(
                                entry.getKey(),
                                managedDependencies.getOrDefault(entry.getKey(), entry.getValue()).merge(entry.getValue())));
                yield new ResolvedPom(properties, managedDependencies, dependencies);
            }
            case null, default ->
                    throw new IllegalArgumentException("Unknown namespace: " + document.getDocumentElement().getNamespaceURI());
        };
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

    private static Map.Entry<DependencyKey, DependencyValue> toDependency400(Node node) {
        return Map.entry(
                new DependencyKey(
                        toChildren400(node, "groupId").map(Node::getTextContent).findFirst().orElseThrow(),
                        toChildren400(node, "artifactId").map(Node::getTextContent).findFirst().orElseThrow(),
                        toChildren400(node, "type").map(Node::getTextContent).findFirst().orElse("jar"),
                        toChildren400(node, "classifier").map(Node::getTextContent).findFirst().orElse(null)),
                new DependencyValue(
                        toChildren400(node, "version").map(Node::getTextContent).findFirst().orElse(null),
                        toChildren400(node, "scope").map(Node::getTextContent).findFirst().orElse("compile"),
                        toChildren400(node, "exclusions")
                                .findFirst()
                                .map(exclusions -> toChildren400(exclusions, "exclusion")
                                        .map(child -> new DependencyExclusion(
                                                toChildren400(child, "groupId").map(Node::getTextContent).findFirst().orElseThrow(),
                                                toChildren400(child, "artifactId").map(Node::getTextContent).findAny().orElseThrow()))
                                        .toList())
                                .orElse(null),
                        toChildren400(node, "optional").findFirst().map(Node::getTextContent).map(Boolean::valueOf).orElse(null)));
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
        DependencyValue merge(DependencyValue value) {
            return value.equals(this) ? this : new DependencyValue(
                    value.version() == null ? version : value.version(),
                    value.scope() == null ? scope : value.scope(),
                    value.exclusions() == null ? exclusions : value.exclusions(),
                    value.optional() == null ? optional : value.optional());
        }
    }

    private record DependencyExclusion(String groupId, String artifactId) {
    }

    private record DependencyCoordinates(String groupId, String artifactId, String version) {
    }

    private record ResolvedPom(Map<String, String> properties,
                               Map<DependencyKey, DependencyValue> managedDependencies,
                               Map<DependencyKey, DependencyValue> dependencies) {
    }

    private record DependencyElement(ResolvedPom pom,
                                     Set<DependencyExclusion> exclusions,
                                     Map<DependencyKey, DependencyValue> managedDependencies) {
    }
}
