package build.jenesis.maven;

public record MavenDependencyKey(String groupId, String artifactId, String type, String classifier) {

    public MavenDependencyKey {
        validate("groupId", groupId);
        validate("artifactId", artifactId);
        validate("type", type);
        validate("classifier", classifier);
        // Maven treats an empty type/classifier as absent. A POM may resolve one to "" - e.g.
        // netty-parent declares <classifier>${tcnative.classifier}</classifier>, and that property
        // is empty unless the os-maven-plugin extension (which Jenesis does not run) populates it.
        // Without this normalization the key keeps "", coordinate() emits "g/a/jar//v" instead of
        // "g/a/v", and the fetch builds a "<artifact>-<version>-.jar" URL that does not exist.
        if (type != null && type.isEmpty()) {
            type = null;
        }
        if (classifier != null && classifier.isEmpty()) {
            classifier = null;
        }
    }

    static void validate(String role, String component) {
        if (component != null && (component.contains("/")
                || component.contains("\\")
                || component.equals(".")
                || component.equals(".."))) {
            throw new IllegalArgumentException("Illegal Maven coordinate " + role + ": " + component);
        }
    }

    public String coordinate(String prefix, String version) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix).append('/');
        }
        sb.append(groupId).append('/').append(artifactId);
        String resolvedType = type == null ? "jar" : type;
        if (!"jar".equals(resolvedType) || classifier != null) {
            sb.append('/').append(resolvedType);
        }
        if (classifier != null) {
            sb.append('/').append(classifier);
        }
        if (version != null) {
            sb.append('/').append(version);
        }
        return sb.toString();
    }

    public static Versioned parse(String suffix) {
        String[] elements = suffix.split("/");
        return switch (elements.length) {
            case 3 -> new Versioned(new MavenDependencyKey(elements[0], elements[1], "jar", null), elements[2]);
            case 4 -> new Versioned(new MavenDependencyKey(elements[0], elements[1], elements[2], null), elements[3]);
            case 5 -> new Versioned(new MavenDependencyKey(elements[0], elements[1], elements[2], elements[3]), elements[4]);
            default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + suffix);
        };
    }

    public static Versioned tryParse(String suffix) {
        String[] elements = suffix.split("/");
        return switch (elements.length) {
            case 2 -> new Versioned(new MavenDependencyKey(elements[0], elements[1], "jar", null), null);
            case 3 -> new Versioned(new MavenDependencyKey(elements[0], elements[1], "jar", null), elements[2]);
            case 4 -> new Versioned(new MavenDependencyKey(elements[0], elements[1], elements[2], null), elements[3]);
            case 5 -> new Versioned(new MavenDependencyKey(elements[0], elements[1], elements[2], elements[3]), elements[4]);
            default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + suffix);
        };
    }

    public static MavenDependencyKey parseKey(String suffix) {
        String[] elements = suffix.split("/");
        return switch (elements.length) {
            case 2 -> new MavenDependencyKey(elements[0], elements[1], "jar", null);
            case 3 -> new MavenDependencyKey(elements[0], elements[1], elements[2], null);
            case 4 -> new MavenDependencyKey(elements[0], elements[1], elements[2], elements[3]);
            default -> throw new IllegalArgumentException("Insufficient Maven managed coordinate: " + suffix);
        };
    }

    public record Versioned(MavenDependencyKey key, String version) {
        public Versioned {
            validate("version", version);
        }
    }
}
