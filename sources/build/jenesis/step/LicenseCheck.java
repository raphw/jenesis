package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDependencyKey;

public class LicenseCheck implements BuildStep {

    public enum Unknown {
        IGNORE, WARN, FAIL
    }

    private final SequencedSet<String> allowed;
    private final SequencedSet<String> denied;
    private final Unknown unknown;
    private final Map<String, String> overrides;

    public LicenseCheck() {
        this(listFrom(System.getProperty("jenesis.compliance.license")),
                null,
                unknownFrom(System.getProperty("jenesis.compliance.license.unknown")),
                overridesFrom(System.getProperties()));
    }

    private LicenseCheck(SequencedSet<String> allowed,
                         SequencedSet<String> denied,
                         Unknown unknown,
                         Map<String, String> overrides) {
        this.allowed = allowed;
        this.denied = denied;
        this.unknown = unknown;
        this.overrides = overrides;
    }

    public LicenseCheck allowed(SequencedSet<String> allowed) {
        return new LicenseCheck(allowed, denied, unknown, overrides);
    }

    public LicenseCheck denied(SequencedSet<String> denied) {
        return new LicenseCheck(allowed, denied, unknown, overrides);
    }

    public LicenseCheck unknown(Unknown unknown) {
        return new LicenseCheck(allowed, denied, unknown, overrides);
    }

    public LicenseCheck overrides(Map<String, String> overrides) {
        return new LicenseCheck(allowed, denied, unknown, Map.copyOf(overrides));
    }

    private static SequencedSet<String> listFrom(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        SequencedSet<String> entries = new LinkedHashSet<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries.isEmpty() ? null : entries;
    }

    private static Unknown unknownFrom(String value) {
        if (value == null) {
            return Unknown.FAIL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "ignore" -> Unknown.IGNORE;
            case "warn" -> Unknown.WARN;
            default -> Unknown.FAIL;
        };
    }

    private static Map<String, String> overridesFrom(Properties properties) {
        Map<String, String> overrides = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("jenesis.compliance.license.override.")) {
                overrides.put(name.substring("jenesis.compliance.license.override.".length()),
                        properties.getProperty(name));
            }
        }
        return overrides;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, List<String[]>> licensesByCoordinate = new TreeMap<>();
        SequencedMap<String, Path> jarByCoordinate = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path index = argument.folder().resolve(DEPENDENCIES);
            if (!Files.exists(index)) {
                continue;
            }
            SequencedProperties dependencies = SequencedProperties.ofFiles(index);
            Path sidecar = argument.folder().resolve("licenses.properties");
            SequencedProperties licenses = Files.exists(sidecar)
                    ? SequencedProperties.ofFiles(sidecar)
                    : new SequencedProperties();
            for (String key : dependencies.stringPropertyNames()) {
                int first = key.indexOf('/'), second = key.indexOf('/', first + 1), third = key.indexOf('/', second + 1);
                if (third < 0
                        || !key.substring(0, first).equals("main")
                        || !key.substring(second + 1, third).equals("maven")) {
                    continue;
                }
                String coordinate = key.substring(third + 1);
                if (coordinate.substring(coordinate.lastIndexOf('/') + 1).endsWith("-SNAPSHOT")
                        || licensesByCoordinate.containsKey(coordinate)) {
                    continue;
                }
                licensesByCoordinate.put(coordinate, licenses(licenses, key.substring(second + 1)));
                String value = dependencies.getProperty(key);
                int space = value.indexOf(' ');
                jarByCoordinate.put(coordinate,
                        argument.folder().resolve(space < 0 ? value : value.substring(0, space)).normalize());
            }
        }
        List<String> violations = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, List<String[]>> entry : licensesByCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<String[]> licenses = resolve(coordinate, entry.getValue(), jarByCoordinate.get(coordinate));
            String verdict;
            if (licenses.isEmpty()) {
                verdict = switch (unknown) {
                    case FAIL -> "MISSING";
                    case WARN -> "WARN";
                    case IGNORE -> "UNKNOWN";
                };
                if (unknown == Unknown.FAIL) {
                    violations.add(coordinate + " (no license)");
                }
            } else if (acceptable(licenses)) {
                verdict = "OK";
            } else {
                verdict = "DENIED";
                violations.add(coordinate + " " + describe(licenses));
            }
            builder.append(coordinate).append(" [").append(verdict).append("] ").append(describe(licenses)).append("\n");
        }
        Path report = Files.createDirectories(context.next().resolve(REPORTS + "compliance"));
        Files.writeString(report.resolve("licenses.txt"), builder.toString());
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Disallowed dependency licenses: " + String.join(", ", violations));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private List<String[]> resolve(String coordinate, List<String[]> declared, Path jar) {
        String override = override(coordinate);
        if (override != null) {
            return Collections.singletonList(new String[]{override, null});
        }
        if (!declared.isEmpty()) {
            return declared;
        }
        String embedded = jarLicense(jar);
        return embedded == null ? declared : Collections.singletonList(new String[]{embedded, null});
    }

    private String override(String coordinate) {
        if (overrides.containsKey(coordinate)) {
            return overrides.get(coordinate);
        }
        try {
            MavenDependencyKey.Versioned parsed = MavenDependencyKey.tryParse(coordinate);
            String identifier = parsed.key().groupId() + "/" + parsed.key().artifactId();
            if (overrides.containsKey(identifier)) {
                return overrides.get(identifier);
            }
            String versioned = identifier + "/" + parsed.version();
            return overrides.get(versioned);
        } catch (RuntimeException _) {
            return null;
        }
    }

    // A dependency is acceptable if any one of its licenses passes the policy (Maven lists
    // multiple licenses disjunctively: the consumer may pick any).
    private boolean acceptable(List<String[]> licenses) {
        for (String[] license : licenses) {
            Set<String> tokens = tokens(license[0], license[1]);
            boolean rejected = denied != null && matches(tokens, denied);
            boolean permitted = allowed == null || matches(tokens, allowed);
            if (!rejected && permitted) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Set<String> tokens, SequencedSet<String> policy) {
        for (String entry : policy) {
            String lower = entry.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (token.contains(lower)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> tokens(String name, String url) {
        Set<String> tokens = new HashSet<>();
        if (name != null && !name.isBlank()) {
            tokens.add(name.toLowerCase(Locale.ROOT));
        }
        if (url != null && !url.isBlank()) {
            tokens.add(url.toLowerCase(Locale.ROOT));
        }
        String[] spdx = identify(name, url);
        if (spdx != null) {
            tokens.add(spdx[0].toLowerCase(Locale.ROOT));
            tokens.add(spdx[1]);
        }
        return tokens;
    }

    // A small map of common declared name/URL forms to a canonical SPDX identifier and a
    // license category, ordered most-specific first so the GPL family resolves correctly.
    private static String[] identify(String name, String url) {
        String text = ((name == null ? "" : name) + " " + (url == null ? "" : url)).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return null;
        }
        if (text.contains("affero")) {
            return new String[]{"AGPL-3.0", "network-copyleft"};
        }
        if (text.contains("lesser general public") || text.contains("lgpl")) {
            return new String[]{"LGPL", "weak-copyleft"};
        }
        if (text.contains("general public license") || text.contains("/gpl")) {
            return new String[]{"GPL", "strong-copyleft"};
        }
        if (text.contains("apache")) {
            return new String[]{"Apache-2.0", "permissive"};
        }
        if (text.contains("eclipse distribution")) {
            return new String[]{"BSD-3-Clause", "permissive"};
        }
        if (text.contains("eclipse public") || text.contains("/epl")) {
            return new String[]{"EPL-2.0", "weak-copyleft"};
        }
        if (text.contains("mozilla public") || text.contains("mpl")) {
            return new String[]{"MPL-2.0", "weak-copyleft"};
        }
        if (text.contains("common development and distribution") || text.contains("cddl")) {
            return new String[]{"CDDL-1.1", "weak-copyleft"};
        }
        if (text.contains("bsd")) {
            return new String[]{"BSD", "permissive"};
        }
        if (text.contains("mit license") || text.contains("licenses/mit") || text.contains("(mit)")) {
            return new String[]{"MIT", "permissive"};
        }
        if (text.contains("boost software")) {
            return new String[]{"BSL-1.0", "permissive"};
        }
        if (text.contains("unlicense")) {
            return new String[]{"Unlicense", "permissive"};
        }
        if (text.contains("cc0") || text.contains("public domain")) {
            return new String[]{"CC0-1.0", "permissive"};
        }
        if (text.contains("isc")) {
            return new String[]{"ISC", "permissive"};
        }
        return null;
    }

    private static String jarLicense(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return null;
            }
            String value = manifest.getMainAttributes().getValue("Bundle-License");
            if (value == null || value.isBlank()) {
                return null;
            }
            int semicolon = value.indexOf(';');
            return (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
        } catch (IOException _) {
            return null;
        }
    }

    private static String describe(List<String[]> licenses) {
        List<String> rendered = new ArrayList<>();
        for (String[] license : licenses) {
            if (license[0] != null && !license[0].isBlank()) {
                rendered.add(license[0]);
            } else if (license[1] != null && !license[1].isBlank()) {
                rendered.add(license[1]);
            }
        }
        return String.join("; ", rendered);
    }

    private static List<String[]> licenses(SequencedProperties licenses, String licenseKey) {
        SequencedMap<Integer, String[]> byIndex = new TreeMap<>();
        String prefix = licenseKey + "#";
        for (String key : licenses.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int hash = rest.indexOf('#');
            if (hash < 0) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(rest.substring(0, hash));
            } catch (NumberFormatException _) {
                continue;
            }
            String[] entry = byIndex.computeIfAbsent(index, _ -> new String[2]);
            String field = rest.substring(hash + 1);
            if (field.equals("name")) {
                entry[0] = licenses.getProperty(key);
            } else if (field.equals("url")) {
                entry[1] = licenses.getProperty(key);
            }
        }
        return new ArrayList<>(byIndex.values());
    }
}
