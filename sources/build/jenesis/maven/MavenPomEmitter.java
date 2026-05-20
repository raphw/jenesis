package build.jenesis.maven;

import module java.base;
import module java.xml;

public class MavenPomEmitter {

    private static final String NAMESPACE_4_0_0 = "http://maven.apache.org/POM/4.0.0";

    private final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    private final TransformerFactory transformerFactory = TransformerFactory.newInstance();

    public MavenPomEmitter() {
        documentBuilderFactory.setNamespaceAware(true);
    }

    public IOConsumer emit(String groupId,
                           String artifactId,
                           String version,
                           SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies) {
        return emit(groupId, artifactId, version, dependencies, null);
    }

    public IOConsumer emit(String groupId,
                           String artifactId,
                           String version,
                           SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies,
                           Metadata metadata) {
        Document document;
        try {
            document = documentBuilderFactory.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        Element project = (Element) appendChild(document, document, "project");
        project.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance",
                "xsi:schemaLocation",
                "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd");
        appendText(document, project, "modelVersion", "4.0.0");
        appendText(document, project, "groupId", groupId);
        appendText(document, project, "artifactId", artifactId);
        appendText(document, project, "version", version);
        if (metadata != null) {
            if (metadata.name() != null) {
                appendText(document, project, "name", metadata.name());
            }
            if (metadata.description() != null) {
                appendText(document, project, "description", metadata.description());
            }
            if (metadata.url() != null) {
                appendText(document, project, "url", metadata.url());
            }
            if (!metadata.licenses().isEmpty()) {
                Node wrapper = appendChild(document, project, "licenses");
                for (Metadata.License license : metadata.licenses()) {
                    Node node = appendChild(document, wrapper, "license");
                    if (license.name() != null) {
                        appendText(document, node, "name", license.name());
                    }
                    if (license.url() != null) {
                        appendText(document, node, "url", license.url());
                    }
                }
            }
            if (!metadata.developers().isEmpty()) {
                Node wrapper = appendChild(document, project, "developers");
                for (Metadata.Developer developer : metadata.developers()) {
                    Node node = appendChild(document, wrapper, "developer");
                    if (developer.id() != null) {
                        appendText(document, node, "id", developer.id());
                    }
                    if (developer.name() != null) {
                        appendText(document, node, "name", developer.name());
                    }
                    if (developer.email() != null) {
                        appendText(document, node, "email", developer.email());
                    }
                }
            }
            if (metadata.scm() != null) {
                Node node = appendChild(document, project, "scm");
                Metadata.Scm scm = metadata.scm();
                if (scm.connection() != null) {
                    appendText(document, node, "connection", scm.connection());
                }
                String developerConnection = scm.developerConnection() != null
                        ? scm.developerConnection()
                        : scm.connection();
                if (developerConnection != null) {
                    appendText(document, node, "developerConnection", developerConnection);
                }
                if (scm.url() != null) {
                    appendText(document, node, "url", scm.url());
                }
            }
        }
        if (!dependencies.isEmpty()) {
            Node wrapper = appendChild(document, project, "dependencies");
            for (Map.Entry<MavenDependencyKey, MavenDependencyValue> dependency : dependencies.entrySet()) {
                Node node = appendChild(document, wrapper, "dependency");
                appendText(document, node, "groupId", dependency.getKey().groupId());
                appendText(document, node, "artifactId", dependency.getKey().artifactId());
                appendText(document, node, "version", dependency.getValue().version());
                if (!Objects.equals(dependency.getKey().type(), "jar")) {
                    appendText(document, node, "type", dependency.getKey().type());
                }
                if (dependency.getKey().classifier() != null) {
                    appendText(document, node, "classifier", dependency.getKey().classifier());
                }
                if (dependency.getValue().scope() != MavenDependencyScope.COMPILE) {
                    appendText(document, node, "scope", switch (dependency.getValue().scope()) {
                        case PROVIDED -> "provided";
                        case RUNTIME -> "runtime";
                        case TEST -> "test";
                        case SYSTEM -> "system";
                        case IMPORT -> "import";
                        default -> throw new IllegalStateException("Unexpected scope: " + dependency.getValue().scope());
                    });
                }
                if (dependency.getValue().systemPath() != null) {
                    appendText(document, node, "systemPath", dependency.getValue().systemPath().toString());
                }
                if (dependency.getValue().optional() != null) {
                    appendText(document, node, "optional", dependency.getValue().optional().toString());
                }
                if (dependency.getValue().exclusions() != null) {
                    Node exclusions = appendChild(document, node, "exclusions");
                    dependency.getValue().exclusions().forEach(name -> {
                        Node exclusion = appendChild(document, exclusions, "exclusion");
                        appendText(document, exclusion, "groupId", name.groupId());
                        appendText(document, exclusion, "artifactId", name.artifactId());
                    });
                }
            }
        }
        Transformer transformer;
        try {
            transformer = transformerFactory.newTransformer();
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        return writer -> {
            StringWriter buffer = new StringWriter();
            try {
                transformer.transform(new DOMSource(document), new StreamResult(buffer));
            } catch (TransformerException e) {
                throw new IOException(e);
            }
            writer.write(buffer.toString().replace("\r\n", "\n"));
        };
    }

    private static Node appendChild(Document document, Node parent, String name) {
        return parent.appendChild(document.createElementNS(NAMESPACE_4_0_0, name));
    }

    private static void appendText(Document document, Node parent, String name, String text) {
        appendChild(document, parent, name).setTextContent(text);
    }

    @FunctionalInterface
    public interface IOConsumer {

        void accept(Writer writer) throws IOException;
    }

    public record Metadata(
            String name,
            String description,
            String url,
            List<License> licenses,
            List<Developer> developers,
            Scm scm
    ) implements Serializable {

        public Metadata {
            licenses = licenses == null ? List.of() : List.copyOf(licenses);
            developers = developers == null ? List.of() : List.copyOf(developers);
        }

        public record License(String name, String url) implements Serializable {
        }

        public record Developer(String id, String name, String email) implements Serializable {
        }

        public record Scm(String connection, String developerConnection, String url) implements Serializable {
        }
    }
}
