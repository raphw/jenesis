package build.jenesis.maven;

import module java.base;
import module java.xml;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

public class MavenPomTranslator implements BiFunction<String, String, String>, Serializable {

    private static final String NAMESPACE_4_0_0 = "http://maven.apache.org/POM/4.0.0";

    private final transient Repository repository;

    public MavenPomTranslator(Repository repository) {
        this.repository = repository;
    }

    @Override
    public String apply(String prefix, String coordinate) {
        RepositoryItem item;
        try {
            item = repository.fetch(Runnable::run, coordinate + ":pom")
                    .orElseThrow(() -> new IllegalArgumentException("No POM found for " + coordinate));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String groupId, artifactId, version;
        try (InputStream stream = item.toInputStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Node root = factory.newDocumentBuilder().parse(stream).getDocumentElement();
            artifactId = textChild(root, "artifactId");
            groupId = textChild(root, "groupId");
            version = textChild(root, "version");
            if (groupId == null || version == null) {
                Node parent = elementChild(root, "parent");
                if (parent != null) {
                    if (groupId == null) {
                        groupId = textChild(parent, "groupId");
                    }
                    if (version == null) {
                        version = textChild(parent, "version");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("Failed to parse POM for " + coordinate, e);
        }
        if (groupId == null) {
            throw new IllegalArgumentException("Missing groupId in POM for " + coordinate);
        }
        if (artifactId == null) {
            throw new IllegalArgumentException("Missing artifactId in POM for " + coordinate);
        }
        if (version == null) {
            throw new IllegalArgumentException("Missing version in POM for " + coordinate);
        }
        return groupId + "/" + artifactId + "/" + version;
    }

    private static String textChild(Node parent, String name) {
        Node child = elementChild(parent, name);
        return child == null ? null : child.getTextContent();
    }

    private static Node elementChild(Node parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && name.equals(child.getLocalName())
                    && (child.getNamespaceURI() == null || NAMESPACE_4_0_0.equals(child.getNamespaceURI()))) {
                return child;
            }
        }
        return null;
    }
}
