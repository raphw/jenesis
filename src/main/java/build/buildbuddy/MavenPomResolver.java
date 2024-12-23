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

    public List<MavenDependency> resolve(String groupId, String artifactId, String version) throws IOException {
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
        Set<MavenDependencyKey> previous = new HashSet<>();
        Queue<Map.Entry<PomVersion, Set<ExcludedDependency>>> queue = new ArrayDeque<>(Set.of(Map.entry(
                new PomVersion(groupId, artifactId, version),
                Set.of())));
        do {
            Map.Entry<PomVersion, Set<ExcludedDependency>> current = queue.remove();
            ResolvedPom pom = doResolve(repository.download(current.getKey().groupId(),
                    current.getKey().artifactId(),
                    current.getKey().version(),
                    null,
                    "pom"), new HashSet<>());
            for (Map.Entry<MavenDependencyKey, MavenDependencyValue> entry : pom.dependencies().entrySet()) {
                if (!current.getValue().contains(new ExcludedDependency(
                        entry.getKey().groupId(),
                        entry.getKey().artifactId())) && previous.add(entry.getKey())) {
                    dependencies.put(entry.getKey(), entry.getValue());
                    Set<ExcludedDependency> exclusions;
                    if (entry.getValue().exclusions() == null || entry.getValue().exclusions().isEmpty()) {
                        exclusions = current.getValue();
                    } else {
                        exclusions = new HashSet<>(current.getValue());
                        exclusions.addAll(entry.getValue().exclusions());
                    }
                    queue.add(Map.entry(new PomVersion(entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            entry.getValue().version()), exclusions));
                }
            }
        for (Map.Entry<MavenDependencyKey, MavenDependencyValue> entry : pom.dependencies().entrySet()) {
            ResolvedPom pom1 = doResolve(repository.download(entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            entry.getValue().version(),
                            null,
                            "pom"),
                    new HashSet<>());
            List<ExcludedDependency> exclusions = entry.getValue().exclusions() == null ? List.of() : entry.getValue().exclusions();
            pom1.dependencies().entrySet().stream()
                    .filter(dependency -> !exclusions.contains(new ExcludedDependency(
                            dependency.getKey().groupId(),
                            dependency.getKey().artifactId())))
                    .forEach(dependency -> dependencies.putIfAbsent(
                            dependency.getKey(),
                            dependency.getValue()));
            // TODO: as queue, with dependency configuration propagation
        }
        } while (!queue.isEmpty());
        return dependencies.entrySet().stream().map(entry -> new MavenDependency(
                entry.getKey().groupId(),
                entry.getKey().artifactId(),
                entry.getValue().version(),
                entry.getKey().type(),
                entry.getKey().classifier(),
                Objects.equals(entry.getValue().optional(), true))).toList();
    }

    private ResolvedPom doResolve(InputStream inputStream, Set<PomVersion> children) throws IOException {
        // TODO: implicit properties.
        // TODO: resolve properties
        // TODO: lazy resolution of dependencies and their configurations
        // TODO: cache and circularity detection
        // TODO: order of dependencies?
        Document document;
        try (inputStream) {
            document = factory.newDocumentBuilder().parse(inputStream);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return switch (document.getDocumentElement().getNamespaceURI()) {
            case NAMESPACE_4_0_0 -> {
                PomVersion parent = toChildren400(document.getDocumentElement(), "parent")
                        .findFirst()
                        .map(node -> new PomVersion(
                                toChildren400(node, "groupId").map(Node::getTextContent).findFirst().orElseThrow(),
                                toChildren400(node, "artifactId").map(Node::getTextContent).findFirst().orElseThrow(),
                                toChildren400(node, "version").map(Node::getTextContent).findFirst().orElseThrow()))
                        .orElse(null);
                Map<String, String> properties = new HashMap<>();
                Map<MavenDependencyKey, MavenDependencyValue> managedDependencies = new HashMap<>();
                SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
                if (parent != null) {
                    if (!children.add(new PomVersion(parent.groupId(), parent.artifactId(), parent.version()))) {
                        throw new IllegalStateException();
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

    private static Map.Entry<MavenDependencyKey, MavenDependencyValue> toDependency400(Node node) {
        return Map.entry(
                new MavenDependencyKey(
                        toChildren400(node, "groupId").map(Node::getTextContent).findFirst().orElseThrow(),
                        toChildren400(node, "artifactId").map(Node::getTextContent).findFirst().orElseThrow(),
                        toChildren400(node, "type").map(Node::getTextContent).findFirst().orElse("jar"),
                        toChildren400(node, "classifier").map(Node::getTextContent).findFirst().orElse(null)),
                new MavenDependencyValue(
                        toChildren400(node, "version").map(Node::getTextContent).findFirst().orElse(null),
                        toChildren400(node, "scope").map(Node::getTextContent).findFirst().orElse("compile"),
                        toChildren400(node, "exclusions")
                                .findFirst()
                                .map(exclusions -> toChildren400(exclusions, "exclusion")
                                        .map(child -> new ExcludedDependency(
                                                toChildren400(child, "groupId").map(Node::getTextContent).findFirst().orElseThrow(),
                                                toChildren400(child, "artifactId").map(Node::getTextContent).findAny().orElseThrow()))
                                        .toList())
                                .orElse(null),
                        toChildren400(node, "optional").findFirst().map(Node::getTextContent).map(Boolean::valueOf).orElse(null)));
    }

    private record MavenDependencyKey(String groupId,
                                      String artifactId,
                                      String type,
                                      String classifier) {
    }

    private record MavenDependencyValue(String version,
                                        String scope,
                                        List<ExcludedDependency> exclusions,
                                        Boolean optional) {
        MavenDependencyValue merge(MavenDependencyValue value) {
            return value.equals(this) ? this : new MavenDependencyValue(
                    value.version() == null ? version : value.version(),
                    value.scope() == null ? scope : value.scope(),
                    value.exclusions() == null ? exclusions : value.exclusions(),
                    value.optional() == null ? optional : value.optional());
        }
    }

    private record ExcludedDependency(String groupId, String artifactId) {
    }

    private record PomVersion(String groupId, String artifactId, String version) {
    }

    private record ResolvedPom(Map<String, String> properties,
                               Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
                               Map<MavenDependencyKey, MavenDependencyValue> dependencies) {
    }
}
