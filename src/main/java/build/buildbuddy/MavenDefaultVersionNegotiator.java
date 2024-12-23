package build.buildbuddy;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;

import static build.buildbuddy.MavenPomResolver.missing;
import static build.buildbuddy.MavenPomResolver.toChildren;

public class MavenDefaultVersionNegotiator implements MavenVersionNegotiator {

    private final MavenRepository repository;
    private final DocumentBuilderFactory factory;
    private final Map<MavenPomResolver.DependencyName, Metadata> cache = new HashMap<>();

    private MavenDefaultVersionNegotiator(MavenRepository repository, DocumentBuilderFactory factory) {
        this.repository = repository;
        this.factory = factory;
    }

    public static Supplier<MavenVersionNegotiator> mavenRules(MavenRepository repository) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return () -> new MavenDefaultVersionNegotiator(repository, factory);
    }

    @Override
    public String resolve(String groupId,
                          String artifactId,
                          String type,
                          String classifier,
                          String version) throws IOException {
        return switch (version) {
            case "RELEASE" -> toMetadata(groupId, artifactId).release();
            case "LATEST" -> toMetadata(groupId, artifactId).latest();
            case String range when (range.startsWith("[") || range.startsWith("(")) && range.contains(",")-> {
                String[] values = range.substring(1, range.length() - 2).split(",", 2);
                toMetadata(groupId, artifactId).versions();
                yield values[1];
            }
            case String fixed when fixed.startsWith("[") || fixed.startsWith("(") -> {
                String value = fixed.substring(1, fixed.length() - 2);
                toMetadata(groupId, artifactId).versions();
                yield value;
            }
            default -> version;
        };
    }

    @Override
    public String resolve(String groupId,
                          String artifactId,
                          String type,
                          String classifier,
                          String version,
                          SequencedSet<String> versions) throws IOException {
        return versions.getFirst(); // TODO
    }

    private Metadata toMetadata(String groupId, String artifactId) throws IOException {
        Metadata metadata = cache.get(new MavenPomResolver.DependencyName(groupId, artifactId));
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
            cache.put(new MavenPomResolver.DependencyName(groupId, artifactId), metadata);
        }
        return metadata;
    }

    private record Metadata(String latest, String release, List<String> versions) {
    }
}
