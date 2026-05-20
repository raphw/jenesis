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
        Element project = (Element) document.appendChild(document.createElementNS(NAMESPACE_4_0_0, "project"));
        project.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance",
                "xsi:schemaLocation",
                "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd");
        project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "modelVersion")).setTextContent("4.0.0");
        project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "groupId")).setTextContent(groupId);
        project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "artifactId")).setTextContent(artifactId);
        project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "version")).setTextContent(version);
        if (metadata != null) {
            if (metadata.name() != null) {
                project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "name")).setTextContent(metadata.name());
            }
            if (metadata.description() != null) {
                project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "description")).setTextContent(metadata.description());
            }
            if (metadata.url() != null) {
                project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "url")).setTextContent(metadata.url());
            }
            if (!metadata.licenses().isEmpty()) {
                Node wrapper = project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "licenses"));
                for (Metadata.License license : metadata.licenses()) {
                    Node node = wrapper.appendChild(document.createElementNS(NAMESPACE_4_0_0, "license"));
                    if (license.name() != null) {
                        node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "name")).setTextContent(license.name());
                    }
                    if (license.url() != null) {
                        node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "url")).setTextContent(license.url());
                    }
                }
            }
            if (!metadata.developers().isEmpty()) {
                Node wrapper = project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "developers"));
                for (Metadata.Developer developer : metadata.developers()) {
                    Node node = wrapper.appendChild(document.createElementNS(NAMESPACE_4_0_0, "developer"));
                    if (developer.id() != null) {
                        node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "id")).setTextContent(developer.id());
                    }
                    if (developer.name() != null) {
                        node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "name")).setTextContent(developer.name());
                    }
                    if (developer.email() != null) {
                        node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "email")).setTextContent(developer.email());
                    }
                }
            }
            if (metadata.scm() != null) {
                Node node = project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "scm"));
                Metadata.Scm scm = metadata.scm();
                if (scm.connection() != null) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "connection")).setTextContent(scm.connection());
                }
                String developerConnection = scm.developerConnection() != null
                        ? scm.developerConnection()
                        : scm.connection();
                if (developerConnection != null) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "developerConnection")).setTextContent(developerConnection);
                }
                if (scm.url() != null) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "url")).setTextContent(scm.url());
                }
            }
        }
        if (!dependencies.isEmpty()) {
            Node wrapper = project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "dependencies"));
            for (Map.Entry<MavenDependencyKey, MavenDependencyValue> dependency : dependencies.entrySet()) {
                Node node = wrapper.appendChild(document.createElementNS(NAMESPACE_4_0_0, "dependency"));
                node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "groupId")).setTextContent(dependency.getKey().groupId());
                node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "artifactId")).setTextContent(dependency.getKey().artifactId());
                node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "version")).setTextContent(dependency.getValue().version());
                if (!Objects.equals(dependency.getKey().type(), "jar")) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "type")).setTextContent(dependency.getKey().type());
                }
                if (dependency.getKey().classifier() != null) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "classifier")).setTextContent(dependency.getKey().classifier());
                }
                if (dependency.getValue().scope() != MavenDependencyScope.COMPILE) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "scope")).setTextContent(switch (dependency.getValue().scope()) {
                        case PROVIDED -> "provided";
                        case RUNTIME -> "runtime";
                        case TEST -> "test";
                        case SYSTEM -> "system";
                        case IMPORT -> "import";
                        default -> throw new IllegalStateException("Unexpected scope: " + dependency.getValue().scope());
                    });
                }
                if (dependency.getValue().systemPath() != null) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "systemPath")).setTextContent(dependency.getValue().systemPath().toString());
                }
                if (dependency.getValue().optional() != null) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "optional")).setTextContent(dependency.getValue().optional().toString());
                }
                if (dependency.getValue().exclusions() != null) {
                    Node exclusions = node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "exclusions"));
                    dependency.getValue().exclusions().forEach(name -> {
                        Node exclusion = exclusions.appendChild(document.createElementNS(NAMESPACE_4_0_0, "exclusion"));
                        exclusion.appendChild(document.createElementNS(NAMESPACE_4_0_0, "groupId")).setTextContent(name.groupId());
                        exclusion.appendChild(document.createElementNS(NAMESPACE_4_0_0, "artifactId")).setTextContent(name.artifactId());
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
