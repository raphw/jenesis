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
        return doResolve(repository.download(groupId, artifactId, version, null, "pom")).dependencies().entrySet().stream().map(entry -> new MavenDependency(
                entry.getKey().groupId(),
                entry.getKey().artifactId(),
                entry.getValue().version(),
                entry.getKey().type(),
                entry.getKey().classifier(),
                entry.getValue().optional())).toList();
    }

    private ResolvedPom doResolve(InputStream inputStream) throws IOException {
        // TODO: implicit properties.
        // TODO: resolve properties
        // TODO: resolve transitives
        // TODO: consider excludes
        // TODO: resolve configurations
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
                Map<MavenDependencyKey, MavenDependencyValue> managedDependencies = new LinkedHashMap<>();
                Map<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
                if (parent != null) {
                    ResolvedPom resolution = doResolve(repository.download(parent.groupId(),
                            parent.artifactId(),
                            parent.version(),
                            null,
                            "pom"));
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
                        .forEach(entry -> dependencies.put(entry.getKey(), entry.getValue()));
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
                                .flatMap(child -> toChildren400(child, "exclusion"))
                                .map(child -> new Exclusion(
                                        toChildren400(child, "groupId").map(Node::getTextContent).findFirst().orElseThrow(),
                                        toChildren400(child, "artifactId").map(Node::getTextContent).findAny().orElseThrow()))
                                .toList(),
                        toChildren400(node, "optional").findFirst().map(Node::getTextContent).map(Boolean::valueOf).orElse(false)));
    }

    private record MavenDependencyKey(
            String groupId,
            String artifactId,
            String type,
            String classifier
    ) {
    }

    private record MavenDependencyValue(
            String version,
            String scope,
            List<Exclusion> exclusions,
            boolean optional
    ) {
    }

    private record Exclusion(String groupId, String artifactId) {
    }

    private record PomVersion(String groupId, String artifactId, String version) {
    }

    private record ResolvedPom(Map<String, String> properties,
                               Map<MavenDependencyKey, MavenDependencyValue> managedDependencies,
                               Map<MavenDependencyKey, MavenDependencyValue> dependencies) {
    }
}
