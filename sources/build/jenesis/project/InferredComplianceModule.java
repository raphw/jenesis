package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Bind;
import build.jenesis.step.LicenseCheck;
import build.jenesis.step.OsvDownload;
import build.jenesis.step.VulnerabilityCheck;

public class InferredComplianceModule implements BuildExecutorModule {

    private static final String LICENSE = "license", VULNERABILITY = "vulnerability";

    private static final Set<String> LICENSING_KEYS = Set.of("allowed", "denied", "unknown");
    private static final Set<String> VULNERABILITY_KEYS = Set.of("severity", "warn", "osv.endpoint");

    private final SequencedSet<Path> configuration;
    private final boolean enabled;

    public InferredComplianceModule(SequencedSet<Path> configuration) {
        this(configuration, Boolean.parseBoolean(System.getProperty("jenesis.compliance", "true")));
    }

    private InferredComplianceModule(SequencedSet<Path> configuration, boolean enabled) {
        this.configuration = configuration;
        this.enabled = enabled;
    }

    public InferredComplianceModule enabled(boolean enabled) {
        return new InferredComplianceModule(configuration, enabled);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        Bind.configuredByProperties(buildExecutor, inherited.sequencedKeySet(), LICENSE, enabled,
                BuildStep.locate(configuration, "licensing.properties"),
                properties -> {
                    if (properties.stringPropertyNames().isEmpty()) {
                        return null;
                    }
                    return (nested, nestedInherited) -> nested.addStep("check",
                            licenseCheck(properties), nestedInherited.sequencedKeySet().stream());
                });
        Bind.configuredByProperties(buildExecutor, inherited.sequencedKeySet(), VULNERABILITY, enabled,
                BuildStep.locate(configuration, "vulnerability.properties"),
                properties -> {
                    if (properties.stringPropertyNames().isEmpty()) {
                        return null;
                    }
                    return (nested, nestedInherited) -> {
                        nested.addStep("osv", osvDownload(properties), nestedInherited.sequencedKeySet().stream());
                        nested.addStep("check", vulnerabilityCheck(properties),
                                Stream.concat(nestedInherited.sequencedKeySet().stream(), Stream.of("osv")));
                    };
                });
    }

    private static LicenseCheck licenseCheck(SequencedProperties properties) {
        SequencedMap<String, String> overrides = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("override.")) {
                overrides.put(key.substring("override.".length()), properties.getProperty(key));
            } else if (!LICENSING_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown licensing property: " + key);
            }
        }
        return new LicenseCheck()
                .allowed(licenses(properties.getProperty("allowed")))
                .denied(licenses(properties.getProperty("denied")))
                .unknown(unknown(properties.getProperty("unknown")))
                .overrides(overrides);
    }

    private static OsvDownload osvDownload(SequencedProperties properties) {
        String endpoint = properties.getProperty("osv.endpoint");
        return endpoint == null ? new OsvDownload() : new OsvDownload().endpoint(URI.create(endpoint));
    }

    private static VulnerabilityCheck vulnerabilityCheck(SequencedProperties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (!VULNERABILITY_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown vulnerability property: " + key);
            }
        }
        return new VulnerabilityCheck()
                .failOn(severity(properties.getProperty("severity")))
                .warn(Boolean.parseBoolean(properties.getProperty("warn", "false")));
    }

    private static SequencedSet<String> licenses(String value) {
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

    private static LicenseCheck.Unknown unknown(String value) {
        if (value == null) {
            return LicenseCheck.Unknown.FAIL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "ignore" -> LicenseCheck.Unknown.IGNORE;
            case "warn" -> LicenseCheck.Unknown.WARN;
            default -> LicenseCheck.Unknown.FAIL;
        };
    }

    private static VulnerabilityCheck.Severity severity(String value) {
        if (value == null) {
            return null;
        }
        try {
            return VulnerabilityCheck.Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
