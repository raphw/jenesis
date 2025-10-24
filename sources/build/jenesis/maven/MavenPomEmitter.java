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
                           String packaging,
                           SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies) {
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
        if (packaging != null) {
            project.appendChild(document.createElementNS(NAMESPACE_4_0_0, "packaging")).setTextContent(packaging);
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
            try {
                transformer.transform(new DOMSource(document), new StreamResult(writer));
            } catch (TransformerException e) {
                throw new IOException(e);
            }
        };
    }

    @FunctionalInterface
    public interface IOConsumer {

        void accept(Writer writer) throws IOException;
    }
}
