package build.jenesis;

import module java.base;

public record Platform(SequencedSet<String> tokens) implements Serializable {

    public Platform(SequencedSet<String> tokens) {
        SequencedSet<String> normalized = new TreeSet<>();
        for (String token : tokens) {
            String value = token.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("No platform tokens in " + tokens);
        }
        this.tokens = Collections.unmodifiableSequencedSet(normalized);
    }

    public static Platform detect() {
        String property = System.getProperty("jenesis.dependency.platform");
        if (property != null) {
            return of(property);
        }
        SequencedSet<String> tokens = new TreeSet<>();
        String os = System.getProperty("os.name", "").trim().toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            tokens.add("windows");
        } else if (os.startsWith("mac") || os.startsWith("darwin")) {
            tokens.add("macos");
        } else if (os.startsWith("linux")) {
            tokens.add("linux");
        } else {
            tokens.add(os.replace(' ', '-'));
        }
        String arch = System.getProperty("os.arch", "").trim().toLowerCase(Locale.ROOT);
        tokens.add(switch (arch) {
            case "amd64", "x86-64", "x64" -> "x86_64";
            case "arm64" -> "aarch64";
            default -> arch;
        });
        return new Platform(tokens);
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
