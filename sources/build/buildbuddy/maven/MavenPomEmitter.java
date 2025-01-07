package build.buildbuddy.maven;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Objects;

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
                           List<MavenDependency> dependencies) {
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
            for (MavenDependency dependency : dependencies) {
                Node node = wrapper.appendChild(document.createElementNS(NAMESPACE_4_0_0, "dependency"));
                node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "groupId")).setTextContent(dependency.groupId());
                node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "artifactId")).setTextContent(dependency.artifactId());
                node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "version")).setTextContent(dependency.version());
                if (!Objects.equals(dependency.type(), "jar")) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "type")).setTextContent(dependency.type());
                }
                if (dependency.classifier() != null) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "classifier")).setTextContent(dependency.classifier());
                }
                if (dependency.scope() != MavenDependencyScope.COMPILE) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "scope")).setTextContent(switch (dependency.scope()) {
                        case PROVIDED -> "provided";
                        case RUNTIME -> "runtime";
                        case TEST -> "test";
                        case SYSTEM -> "system";
                        case IMPORT -> "import";
                        default -> throw new IllegalStateException("Unexpected scope: " + dependency.scope());
                    });
                }
                if (dependency.optional()) {
                    node.appendChild(document.createElementNS(NAMESPACE_4_0_0, "optional")).setTextContent("true");
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
