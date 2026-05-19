package build.jenesis.maven;

import module java.base;
import build.jenesis.SequencedProperties;

public class MavenUriParser implements Function<String, String>, Serializable {

    @SuppressWarnings("unchecked")
    public static <F extends Function<String, String> & Serializable> F ofUris(MavenUriParser parser,
                                                                               String location,
                                                                               Iterable<Path> folders)
            throws IOException {
        SequencedProperties properties = SequencedProperties.ofFolders(folders, location);
        return (F) (Function<String, String> & Serializable) property -> {
            String value = properties.getProperty(property);
            if (value == null) {
                throw new IllegalArgumentException("Could not translate " + property);
            }
            return parser.apply(value);
        };
    }

    @Override
    public String apply(String value) {
        URI uri = URI.create(value);
        String[] elements = uri.getPath().split("/");
        String type = elements[elements.length - 1].substring(elements[elements.length - 1].lastIndexOf('.') + 1);
        String classifier = elements[elements.length - 1].substring(
                elements[elements.length - 3].length(),
                elements[elements.length - 1].length() - type.length() - elements[elements.length - 2].length() - 2);
        return String.join(".", Arrays.asList(elements).subList(2, elements.length - 3))
                + "/" + elements[elements.length - 3]
                + (Objects.equals(type, "jar") && classifier.isEmpty() ? "" : "/" + type)
                + (classifier.isEmpty() ? "" : "/" + classifier.substring(1))
                + "/" + elements[elements.length - 2];
    }
}
