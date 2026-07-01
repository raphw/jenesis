package build.jenesis;

import module java.base;
import module java.xml;

public class CycloneDx {

    public enum Format {

        JSON("cdx.json"), XML("cdx.xml");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    private static final String SPEC_VERSION = "1.6";
    private static final String NAMESPACE = "http://cyclonedx.org/schema/bom/" + SPEC_VERSION;

    private static final Set<String> DEFAULT_IDENTIFIERS = Set.of(
            "Apache-2.0", "MIT", "MIT-0",
            "BSD-2-Clause", "BSD-3-Clause", "ISC", "BSL-1.0", "Zlib", "PSF-2.0",
            "Unlicense", "CC0-1.0", "WTFPL",
            "EPL-1.0", "EPL-2.0", "MPL-1.1", "MPL-2.0", "CDDL-1.0", "CDDL-1.1",
            "LGPL-2.1-only", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
            "GPL-2.0-with-classpath-exception",
            "GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
            "AGPL-3.0-only", "AGPL-3.0-or-later");

    private final Set<String> identifiers;

    public CycloneDx() {
        this(DEFAULT_IDENTIFIERS);
    }

    private CycloneDx(Set<String> identifiers) {
        this.identifiers = identifiers;
    }

    public CycloneDx identifiers(Set<String> identifiers) {
        return new CycloneDx(identifiers);
    }

    public record Component(String bomRef, String group, String name, String version, String purl, String sha256,
                            List<License> licenses, String description, List<Author> authors,
                            List<ExternalReference> externalReferences) {

        public Component(String bomRef, String group, String name, String version, String purl, String sha256, List<License> licenses) {
            this(bomRef, group, name, version, purl, sha256, licenses, null, List.of(), List.of());
        }
    }

    public record Author(String name, String email) {
    }

    public record ExternalReference(String type, String url) {
    }

    public record Dependency(String ref, List<String> dependsOn) {
    }

    public String emit(Format format, Component metadata, List<Component> components, List<Dependency> dependencies) {
        List<Component> sortedComponents = new ArrayList<>(components);
        sortedComponents.sort(Comparator.comparing(component -> component.purl() == null
                ? component.group() + ":" + component.name() + ":" + component.version()
                : component.purl()));
        List<Dependency> sortedDependencies = new ArrayList<>();
        for (Dependency dependency : dependencies) {
            List<String> dependsOn = new ArrayList<>(dependency.dependsOn());
            dependsOn.sort(Comparator.naturalOrder());
            sortedDependencies.add(new Dependency(dependency.ref(), dependsOn));
        }
        sortedDependencies.sort(Comparator.comparing(Dependency::ref));
        String serialLess = format == Format.XML
                ? emitXml(null, metadata, sortedComponents, sortedDependencies)
                : emitJson(null, metadata, sortedComponents, sortedDependencies);
        String serialNumber = "urn:uuid:" + UUID.nameUUIDFromBytes(serialLess.getBytes(StandardCharsets.UTF_8));
        return format == Format.XML
                ? emitXml(serialNumber, metadata, sortedComponents, sortedDependencies)
                : emitJson(serialNumber, metadata, sortedComponents, sortedDependencies);
    }

    private String emitJson(String serialNumber, Component metadata, List<Component> components, List<Dependency> dependencies) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"bomFormat\": \"CycloneDX\",\n");
        builder.append("  \"specVersion\": \"").append(SPEC_VERSION).append("\",\n");
        if (serialNumber != null) {
            builder.append("  \"serialNumber\": \"").append(escapeJson(serialNumber)).append("\",\n");
        }
        builder.append("  \"version\": 1,\n");
        builder.append("  \"metadata\": {\n");
        builder.append("    \"tools\": {\n      \"components\": [\n        { \"type\": \"application\", \"name\": \"Jenesis\" }\n      ]\n    }");
        if (metadata != null) {
            builder.append(",\n    \"component\": ");
            appendJsonComponent(builder, metadata, 4);
        }
        builder.append("\n  }");
        if (!components.isEmpty()) {
            builder.append(",\n  \"components\": [\n");
            for (int index = 0; index < components.size(); index++) {
                builder.append("    ");
                appendJsonComponent(builder, components.get(index), 4);
                builder.append(index + 1 < components.size() ? ",\n" : "\n");
            }
            builder.append("  ]");
        }
        if (!dependencies.isEmpty()) {
            builder.append(",\n  \"dependencies\": [\n");
            for (int index = 0; index < dependencies.size(); index++) {
                Dependency dependency = dependencies.get(index);
                builder.append("    { \"ref\": \"").append(escapeJson(dependency.ref())).append("\"");
                if (!dependency.dependsOn().isEmpty()) {
                    builder.append(", \"dependsOn\": [");
                    for (int on = 0; on < dependency.dependsOn().size(); on++) {
                        builder.append(on > 0 ? ", " : "")
                                .append("\"").append(escapeJson(dependency.dependsOn().get(on))).append("\"");
                    }
                    builder.append("]");
                }
                builder.append(" }").append(index + 1 < dependencies.size() ? ",\n" : "\n");
            }
            builder.append("  ]");
        }
        builder.append("\n}\n");
        return builder.toString();
    }

    private void appendJsonComponent(StringBuilder builder, Component component, int indent) {
        String pad = " ".repeat(indent);
        builder.append("{\n");
        builder.append(pad).append("  \"type\": \"library\",\n");
        if (component.bomRef() != null) {
            builder.append(pad).append("  \"bom-ref\": \"").append(escapeJson(component.bomRef())).append("\",\n");
        }
        if (component.group() != null) {
            builder.append(pad).append("  \"group\": \"").append(escapeJson(component.group())).append("\",\n");
        }
        builder.append(pad).append("  \"name\": \"").append(escapeJson(component.name())).append("\"");
        if (component.version() != null) {
            builder.append(",\n").append(pad).append("  \"version\": \"").append(escapeJson(component.version())).append("\"");
        }
        if (component.description() != null) {
            builder.append(",\n").append(pad).append("  \"description\": \"").append(escapeJson(component.description())).append("\"");
        }
        if (component.authors() != null && !component.authors().isEmpty()) {
            builder.append(",\n").append(pad).append("  \"authors\": [\n");
            for (int index = 0; index < component.authors().size(); index++) {
                Author author = component.authors().get(index);
                builder.append(pad).append("    {");
                if (author.name() != null) {
                    builder.append(" \"name\": \"").append(escapeJson(author.name())).append("\"");
                }
                if (author.email() != null) {
                    builder.append(author.name() != null ? "," : "")
                            .append(" \"email\": \"").append(escapeJson(author.email())).append("\"");
                }
                builder.append(" }").append(index + 1 < component.authors().size() ? ",\n" : "\n");
            }
            builder.append(pad).append("  ]");
        }
        if (component.sha256() != null) {
            builder.append(",\n").append(pad).append("  \"hashes\": [\n");
            builder.append(pad).append("    { \"alg\": \"SHA-256\", \"content\": \"")
                    .append(escapeJson(component.sha256())).append("\" }\n");
            builder.append(pad).append("  ]");
        }
        if (component.licenses() != null && !component.licenses().isEmpty()) {
            builder.append(",\n").append(pad).append("  \"licenses\": [\n");
            for (int index = 0; index < component.licenses().size(); index++) {
                License license = component.licenses().get(index);
                builder.append(pad).append("    { \"license\": { ");
                if (license.id() != null && identifiers.contains(license.id())) {
                    builder.append("\"id\": \"").append(escapeJson(license.id())).append("\"");
                } else {
                    String name = license.id() != null ? license.id() : license.name();
                    builder.append("\"name\": \"").append(escapeJson(name == null ? "" : name)).append("\"");
                    if (license.url() != null) {
                        builder.append(", \"url\": \"").append(escapeJson(license.url())).append("\"");
                    }
                }
                builder.append(" } }").append(index + 1 < component.licenses().size() ? ",\n" : "\n");
            }
            builder.append(pad).append("  ]");
        }
        if (component.purl() != null) {
            builder.append(",\n").append(pad).append("  \"purl\": \"").append(escapeJson(component.purl())).append("\"");
        }
        if (component.externalReferences() != null && !component.externalReferences().isEmpty()) {
            builder.append(",\n").append(pad).append("  \"externalReferences\": [\n");
            for (int index = 0; index < component.externalReferences().size(); index++) {
                ExternalReference reference = component.externalReferences().get(index);
                builder.append(pad).append("    { \"type\": \"").append(escapeJson(reference.type()))
                        .append("\", \"url\": \"").append(escapeJson(reference.url())).append("\" }")
                        .append(index + 1 < component.externalReferences().size() ? ",\n" : "\n");
            }
            builder.append(pad).append("  ]");
        }
        builder.append("\n").append(pad).append("}");
    }

    private String emitXml(String serialNumber, Component metadata, List<Component> components, List<Dependency> dependencies) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            document = factory.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        Element bom = (Element) document.appendChild(document.createElementNS(NAMESPACE, "bom"));
        bom.setAttribute("version", "1");
        if (serialNumber != null) {
            bom.setAttribute("serialNumber", serialNumber);
        }
        Element meta = (Element) bom.appendChild(document.createElementNS(NAMESPACE, "metadata"));
        Element tools = (Element) meta.appendChild(document.createElementNS(NAMESPACE, "tools"));
        Element toolComponents = (Element) tools.appendChild(document.createElementNS(NAMESPACE, "components"));
        Element tool = (Element) toolComponents.appendChild(document.createElementNS(NAMESPACE, "component"));
        tool.setAttribute("type", "application");
        appendXmlText(document, tool, "name", "Jenesis");
        if (metadata != null) {
            appendXmlComponent(document, meta, metadata);
        }
        if (!components.isEmpty()) {
            Element wrapper = (Element) bom.appendChild(document.createElementNS(NAMESPACE, "components"));
            for (Component component : components) {
                appendXmlComponent(document, wrapper, component);
            }
        }
        if (!dependencies.isEmpty()) {
            Element wrapper = (Element) bom.appendChild(document.createElementNS(NAMESPACE, "dependencies"));
            for (Dependency dependency : dependencies) {
                Element node = (Element) wrapper.appendChild(document.createElementNS(NAMESPACE, "dependency"));
                node.setAttribute("ref", dependency.ref());
                for (String on : dependency.dependsOn()) {
                    Element child = (Element) node.appendChild(document.createElementNS(NAMESPACE, "dependency"));
                    child.setAttribute("ref", on);
                }
            }
        }
        Transformer transformer;
        try {
            transformer = TransformerFactory.newInstance().newTransformer();
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter writer = new StringWriter();
        try {
            transformer.transform(new DOMSource(document), new StreamResult(writer));
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
        return writer.toString().replace("\r\n", "\n");
    }

    private void appendXmlComponent(Document document, Node parent, Component component) {
        Element node = (Element) parent.appendChild(document.createElementNS(NAMESPACE, "component"));
        node.setAttribute("type", "library");
        if (component.bomRef() != null) {
            node.setAttribute("bom-ref", component.bomRef());
        }
        if (component.authors() != null && !component.authors().isEmpty()) {
            Element authors = (Element) node.appendChild(document.createElementNS(NAMESPACE, "authors"));
            for (Author author : component.authors()) {
                Element entry = (Element) authors.appendChild(document.createElementNS(NAMESPACE, "author"));
                if (author.name() != null) {
                    appendXmlText(document, entry, "name", author.name());
                }
                if (author.email() != null) {
                    appendXmlText(document, entry, "email", author.email());
                }
            }
        }
        if (component.group() != null) {
            appendXmlText(document, node, "group", component.group());
        }
        appendXmlText(document, node, "name", component.name());
        if (component.version() != null) {
            appendXmlText(document, node, "version", component.version());
        }
        if (component.description() != null) {
            appendXmlText(document, node, "description", component.description());
        }
        if (component.sha256() != null) {
            Element hashes = (Element) node.appendChild(document.createElementNS(NAMESPACE, "hashes"));
            Element hash = (Element) hashes.appendChild(document.createElementNS(NAMESPACE, "hash"));
            hash.setAttribute("alg", "SHA-256");
            hash.setTextContent(component.sha256());
        }
        if (component.licenses() != null && !component.licenses().isEmpty()) {
            Element licenses = (Element) node.appendChild(document.createElementNS(NAMESPACE, "licenses"));
            for (License license : component.licenses()) {
                Element wrapper = (Element) licenses.appendChild(document.createElementNS(NAMESPACE, "license"));
                if (license.id() != null && identifiers.contains(license.id())) {
                    appendXmlText(document, wrapper, "id", license.id());
                } else {
                    String name = license.id() != null ? license.id() : license.name();
                    appendXmlText(document, wrapper, "name", name == null ? "" : name);
                    if (license.url() != null) {
                        appendXmlText(document, wrapper, "url", license.url());
                    }
                }
            }
        }
        if (component.purl() != null) {
            appendXmlText(document, node, "purl", component.purl());
        }
        if (component.externalReferences() != null && !component.externalReferences().isEmpty()) {
            Element references = (Element) node.appendChild(document.createElementNS(NAMESPACE, "externalReferences"));
            for (ExternalReference reference : component.externalReferences()) {
                Element entry = (Element) references.appendChild(document.createElementNS(NAMESPACE, "reference"));
                entry.setAttribute("type", reference.type());
                appendXmlText(document, entry, "url", reference.url());
            }
        }
    }

    private static void appendXmlText(Document document, Node parent, String name, String text) {
        parent.appendChild(document.createElementNS(NAMESPACE, name)).setTextContent(text);
    }

    private static String escapeJson(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }
}
