package build.jenesis;

import module java.base;

public final class Platform {

    private Platform() {
    }

    public static SequencedSet<String> tokens() {
        String property = System.getProperty("jenesis.dependency.platform");
        if (property != null) {
            return tokens(property);
        }
        SequencedSet<String> tokens = new TreeSet<>();
        tokens.add(os(System.getProperty("os.name", "")));
        tokens.add(arch(System.getProperty("os.arch", "")));
        return tokens;
    }

    public static SequencedSet<String> tokens(String value) {
        SequencedSet<String> tokens = new TreeSet<>();
        for (String token : value.split(",", -1)) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("No platform tokens in '" + value + "'");
        }
        return tokens;
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

    public static String select(String key,
                                String fallback,
                                SequencedMap<String, String> guarded,
                                SequencedSet<String> active) {
        String selected = fallback;
        String winner = null;
        int specificity = 0;
        for (Map.Entry<String, String> entry : guarded.entrySet()) {
            SequencedSet<String> guard = tokens(entry.getKey());
            if (!active.containsAll(guard)) {
                continue;
            }
            if (guard.size() > specificity) {
                selected = entry.getValue();
                winner = entry.getKey();
                specificity = guard.size();
            } else if (guard.size() == specificity && winner != null && !entry.getValue().equals(selected)) {
                throw new IllegalStateException("Ambiguous platform guards for " + key
                        + ": [" + winner + "] and [" + entry.getKey() + "] both match " + active);
            }
        }
        return selected;
    }
}
