package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class LicenseCheck implements BuildStep {

    private final SequencedSet<String> allowed;
    private final SequencedSet<String> denied;
    private final boolean failOnMissing;

    public LicenseCheck() {
        this(null, null, false);
    }

    private LicenseCheck(SequencedSet<String> allowed, SequencedSet<String> denied, boolean failOnMissing) {
        this.allowed = allowed;
        this.denied = denied;
        this.failOnMissing = failOnMissing;
    }

    public LicenseCheck allowed(SequencedSet<String> allowed) {
        return new LicenseCheck(allowed, denied, failOnMissing);
    }

    public LicenseCheck denied(SequencedSet<String> denied) {
        return new LicenseCheck(allowed, denied, failOnMissing);
    }

    public LicenseCheck failOnMissing(boolean failOnMissing) {
        return new LicenseCheck(allowed, denied, failOnMissing);
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, List<String>> byCoordinate = new TreeMap<>();
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
                if (third < 0 || !key.substring(second + 1, third).equals("maven")) {
                    continue;
                }
                String coordinate = key.substring(third + 1);
                if (!byCoordinate.containsKey(coordinate)) {
                    byCoordinate.put(coordinate, names(licenses, key.substring(second + 1)));
                }
            }
        }
        List<String> violations = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : byCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<String> names = entry.getValue();
            String verdict;
            if (names.isEmpty()) {
                verdict = failOnMissing ? "MISSING" : "UNKNOWN";
                if (failOnMissing) {
                    violations.add(coordinate + " (no license)");
                }
            } else if (denied != null && names.stream().anyMatch(name -> matches(denied, name))) {
                verdict = "DENIED";
                violations.add(coordinate + " " + String.join("; ", names));
            } else if (allowed != null && names.stream().noneMatch(name -> matches(allowed, name))) {
                verdict = "DENIED";
                violations.add(coordinate + " " + String.join("; ", names));
            } else {
                verdict = "OK";
            }
            builder.append(coordinate).append(" [").append(verdict).append("] ")
                    .append(String.join("; ", names)).append("\n");
        }
        Path report = Files.createDirectories(context.next().resolve(REPORTS + "compliance"));
        Files.writeString(report.resolve("licenses.txt"), builder.toString());
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Disallowed dependency licenses: " + String.join(", ", violations));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static boolean matches(SequencedSet<String> policy, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String entry : policy) {
            if (lower.contains(entry.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> names(SequencedProperties licenses, String licenseKey) {
        SequencedMap<Integer, String> byIndex = new TreeMap<>();
        String prefix = licenseKey + "#";
        for (String key : licenses.stringPropertyNames()) {
            if (!key.startsWith(prefix) || !key.endsWith("#name")) {
                continue;
            }
            try {
                byIndex.put(Integer.parseInt(key.substring(prefix.length(), key.length() - "#name".length())),
                        licenses.getProperty(key));
            } catch (NumberFormatException _) {
            }
        }
        return new ArrayList<>(byIndex.values());
    }
}
