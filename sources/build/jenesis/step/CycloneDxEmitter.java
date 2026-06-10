package build.jenesis.step;

import module java.base;
import module java.xml;
import build.jenesis.License;

public class CycloneDxEmitter {

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

    private static final Map<String, String> SPDX = Map.ofEntries(
            Map.entry("apache license, version 2.0", "Apache-2.0"),
            Map.entry("the apache software license, version 2.0", "Apache-2.0"),
            Map.entry("apache 2.0", "Apache-2.0"),
            Map.entry("apache-2.0", "Apache-2.0"),
            Map.entry("apache license 2.0", "Apache-2.0"),
            Map.entry("mit license", "MIT"),
            Map.entry("the mit license", "MIT"),
            Map.entry("mit", "MIT"),
            Map.entry("bsd license", "BSD-2-Clause"),
            Map.entry("the bsd license", "BSD-2-Clause"),
            Map.entry("bsd-2-clause", "BSD-2-Clause"),
            Map.entry("bsd 3-clause", "BSD-3-Clause"),
            Map.entry("the bsd 3-clause license", "BSD-3-Clause"),
            Map.entry("bsd-3-clause", "BSD-3-Clause"),
            Map.entry("eclipse public license - v 1.0", "EPL-1.0"),
            Map.entry("eclipse public license 1.0", "EPL-1.0"),
            Map.entry("epl-1.0", "EPL-1.0"),
            Map.entry("eclipse public license - v 2.0", "EPL-2.0"),
            Map.entry("eclipse public license 2.0", "EPL-2.0"),
            Map.entry("epl-2.0", "EPL-2.0"),
            Map.entry("gnu lesser general public license", "LGPL-2.1-or-later"),
            Map.entry("lgpl-2.1", "LGPL-2.1-only"),
            Map.entry("gnu general public license, version 2", "GPL-2.0-only"),
            Map.entry("common development and distribution license 1.0", "CDDL-1.0"),
            Map.entry("cddl-1.0", "CDDL-1.0"));

    public record Component(String bomRef, String group, String name, String version, String purl, String sha256, List<License> licenses) {
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
        return format == Format.XML
                ? emitXml(metadata, sortedComponents, sortedDependencies)
                : emitJson(metadata, sortedComponents, sortedDependencies);
    }

    private String emitJson(Component metadata, List<Component> components, List<Dependency> dependencies) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"bomFormat\": \"CycloneDX\",\n");
        builder.append("  \"specVersion\": \"").append(SPEC_VERSION).append("\",\n");
        builder.append("  \"version\": 1,\n");
        builder.append("  \"metadata\": {\n");
        builder.append("    \"tools\": [\n      { \"name\": \"Jenesis\" }\n    ],\n");
        builder.append("    \"component\": ");
        appendJsonComponent(builder, metadata, 4);
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
        builder.append(pad).append("  \"name\": \"").append(escapeJson(component.name())).append("\",\n");
        builder.append(pad).append("  \"version\": \"").append(escapeJson(component.version())).append("\"");
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
                String spdx = spdx(license.name());
                builder.append(pad).append("    { \"license\": { ");
                if (spdx != null) {
                    builder.append("\"id\": \"").append(escapeJson(spdx)).append("\"");
                } else {
                    builder.append("\"name\": \"").append(escapeJson(license.name() == null ? "" : license.name())).append("\"");
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
        builder.append("\n").append(pad).append("}");
    }

    private String emitXml(Component metadata, List<Component> components, List<Dependency> dependencies) {
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
        Element meta = (Element) bom.appendChild(document.createElementNS(NAMESPACE, "metadata"));
        Element tools = (Element) meta.appendChild(document.createElementNS(NAMESPACE, "tools"));
        Element tool = (Element) tools.appendChild(document.createElementNS(NAMESPACE, "tool"));
        appendXmlText(document, tool, "name", "Jenesis");
        appendXmlComponent(document, meta, metadata);
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
        if (component.group() != null) {
            appendXmlText(document, node, "group", component.group());
        }
        appendXmlText(document, node, "name", component.name());
        appendXmlText(document, node, "version", component.version());
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
                String spdx = spdx(license.name());
                if (spdx != null) {
                    appendXmlText(document, wrapper, "id", spdx);
                } else {
                    appendXmlText(document, wrapper, "name", license.name() == null ? "" : license.name());
                    if (license.url() != null) {
                        appendXmlText(document, wrapper, "url", license.url());
                    }
                }
            }
        }
        if (component.purl() != null) {
            appendXmlText(document, node, "purl", component.purl());
        }
    }

    private static void appendXmlText(Document document, Node parent, String name, String text) {
        parent.appendChild(document.createElementNS(NAMESPACE, name)).setTextContent(text);
    }

    private static String spdx(String name) {
        return name == null ? null : SPDX.get(name.toLowerCase(Locale.ROOT).trim());
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
