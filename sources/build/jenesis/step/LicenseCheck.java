package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Json;
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
        this(null,
                null,
                Unknown.FAIL,
                Map.of());
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
        return new LicenseCheck(allowed, denied, unknown, overrides);
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, List<String[]>> licensesByCoordinate = new TreeMap<>();
        SequencedMap<String, Path> jarByCoordinate = new LinkedHashMap<>();
        SequencedSet<String> strict = new LinkedHashSet<>();
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
                if (third < 0 || !key.substring(0, first).equals("main")) {
                    continue;
                }
                String coordinate = key.substring(third + 1);
                if (coordinate.substring(coordinate.lastIndexOf('/') + 1).endsWith("-SNAPSHOT")
                        || licensesByCoordinate.containsKey(coordinate)) {
                    continue;
                }
                licensesByCoordinate.put(coordinate, licenses(licenses, key.substring(second + 1)));
                if (key.substring(second + 1, third).equals("maven")) {
                    strict.add(coordinate);
                }
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
            List<String[]> licenses = resolve(coordinate, entry.getValue(), jarByCoordinate.get(coordinate), overrides);
            String verdict;
            if (licenses.isEmpty()) {
                if (!strict.contains(coordinate)) {
                    continue;
                }
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

    private static List<String[]> resolve(String coordinate, List<String[]> declared, Path jar, Map<String, String> overrides) {
        String override = override(coordinate, overrides);
        if (override != null) {
            return Collections.singletonList(new String[]{override, null});
        }
        if (!declared.isEmpty()) {
            return declared;
        }
        return jarLicenses(jar);
    }

    // Overrides are keyed by the internal dependency coordinate, with the maven/ repository
    // prefix, either fully (maven/<groupId>/<artifactId>/<version>) or version-agnostically.
    private static String override(String coordinate, Map<String, String> overrides) {
        String full = "maven/" + coordinate;
        if (overrides.containsKey(full)) {
            return overrides.get(full);
        }
        try {
            MavenDependencyKey.Versioned parsed = MavenDependencyKey.tryParse(coordinate);
            return overrides.get("maven/" + parsed.key().groupId() + "/" + parsed.key().artifactId());
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

    private static List<String[]> jarLicenses(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return List.of();
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest != null) {
                List<String[]> embedded = sbomLicenses(file, manifest);
                if (!embedded.isEmpty()) {
                    return embedded;
                }
                String bundle = bundleLicense(manifest);
                if (bundle != null) {
                    return Collections.singletonList(new String[]{bundle, null});
                }
            }
            String text = licenseFile(file);
            return text == null ? List.of() : Collections.singletonList(new String[]{text, null});
        } catch (IOException _) {
            return List.of();
        }
    }

    private static List<String[]> sbomLicenses(JarFile file, Manifest manifest) {
        String location = manifest.getMainAttributes().getValue("Sbom-Location");
        if (location == null || location.isBlank()) {
            return List.of();
        }
        JarEntry entry = file.getJarEntry(location.trim());
        if (entry == null) {
            return List.of();
        }
        Object document;
        try (InputStream in = file.getInputStream(entry)) {
            document = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException _) {
            return List.of();
        }
        if (!(document instanceof Map<?, ?> root)
                || !(root.get("metadata") instanceof Map<?, ?> metadata)
                || !(metadata.get("component") instanceof Map<?, ?> component)
                || !(component.get("licenses") instanceof List<?> licenses)) {
            return List.of();
        }
        List<String[]> result = new ArrayList<>();
        for (Object element : licenses) {
            if (!(element instanceof Map<?, ?> wrapper)) {
                continue;
            }
            if (wrapper.get("license") instanceof Map<?, ?> license) {
                String id = string(license.get("id"));
                String name = string(license.get("name"));
                String url = string(license.get("url"));
                if (id != null) {
                    result.add(new String[]{id, url});
                } else if (name != null || url != null) {
                    result.add(new String[]{name, url});
                }
            } else {
                String expression = string(wrapper.get("expression"));
                if (expression != null) {
                    result.add(new String[]{expression, null});
                }
            }
        }
        return result;
    }

    private static String bundleLicense(Manifest manifest) {
        String value = manifest.getMainAttributes().getValue("Bundle-License");
        if (value == null || value.isBlank()) {
            return null;
        }
        int semicolon = value.indexOf(';');
        return (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
    }

    private static String licenseFile(JarFile file) throws IOException {
        for (String name : List.of("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md", "LICENSE", "LICENSE.txt")) {
            JarEntry entry = file.getJarEntry(name);
            if (entry == null) {
                continue;
            }
            String[] spdx;
            try (InputStream in = file.getInputStream(entry)) {
                spdx = identify(new String(in.readAllBytes(), StandardCharsets.UTF_8), null);
            }
            if (spdx != null) {
                return spdx[0];
            }
        }
        return null;
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
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
