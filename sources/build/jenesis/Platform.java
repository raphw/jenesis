package build.jenesis;

import module java.base;

public record Platform(SequencedSet<String> tokens) implements Serializable {

    private static final String PREFIX = "jenesis.platform.";

    public Platform(SequencedSet<String> tokens) {
        SequencedSet<String> normalized = new TreeSet<>();
        for (String token : tokens) {
            String value = token.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        this.tokens = Collections.unmodifiableSequencedSet(normalized);
    }

    public Platform() {
        this(Stream.concat(
                Stream.of(os(System.getProperty("os.name", "")), arch(System.getProperty("os.arch", "")))
                        .filter(token -> !"false".equalsIgnoreCase(System.getProperty(PREFIX + token, ""))),
                System.getProperties().stringPropertyNames().stream()
                        .filter(name -> name.startsWith(PREFIX) && "true".equalsIgnoreCase(System.getProperty(name)))
                        .map(name -> name.substring(PREFIX.length())))
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    private static String os(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("windows")) {
            return "windows";
        }
        if (normalized.startsWith("mac") || normalized.startsWith("darwin")) {
            return "macos";
        }
        if (normalized.startsWith("linux")) {
            return "linux";
        }
        return normalized.replace(' ', '-');
    }

    private static String arch(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "amd64", "x86-64", "x64" -> "x86_64";
            case "arm64" -> "aarch64";
            default -> normalized;
        };
    }

    public static Platform of(String value) {
        return new Platform(new TreeSet<>(List.of(value.split(",", -1))));
    }

    public String canonical() {
        return String.join(",", tokens);
    }

    public boolean matches(Platform guard) {
        return tokens.containsAll(guard.tokens());
    }

    public String select(String key, String fallback, SequencedMap<String, String> guarded) {
        String selected = fallback;
        String winner = null;
        int specificity = 0;
        for (Map.Entry<String, String> entry : guarded.entrySet()) {
            Platform guard = of(entry.getKey());
            if (!matches(guard)) {
                continue;
            }
            if (guard.tokens().size() > specificity) {
                selected = entry.getValue();
                winner = entry.getKey();
                specificity = guard.tokens().size();
            } else if (guard.tokens().size() == specificity && winner != null && !entry.getValue().equals(selected)) {
                throw new IllegalStateException("Ambiguous platform guards for " + key
                        + ": [" + winner + "] and [" + entry.getKey() + "] both match " + tokens);
            }
        }
        return selected;
    }
}
