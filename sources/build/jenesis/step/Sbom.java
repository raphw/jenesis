package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.License;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDependencyKey;

public class Sbom implements BuildStep {

    public static final String SBOM = "sbom/";

    private final CycloneDxEmitter.Format format;

    public Sbom() {
        this(CycloneDxEmitter.Format.JSON);
    }

    private Sbom(CycloneDxEmitter.Format format) {
        this.format = format;
    }

    public Sbom format(CycloneDxEmitter.Format format) {
        return new Sbom(format);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(DEPENDENCIES),
                Path.of(METADATA)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<Path> folders = arguments.values().stream().map(BuildStepArgument::folder).toList();
        SequencedProperties metadata = SequencedProperties.ofFolders(folders, METADATA);
        String groupId = metadata.getProperty("project");
        String artifactId = metadata.getProperty("artifact");
        String version = metadata.getProperty("version");
        if (groupId == null || artifactId == null || version == null) {
            throw new IllegalStateException("Missing project/artifact/version in metadata.properties for the SBOM");
        }
        HashDigestFunction hash = new HashDigestFunction("SHA-256");
        SequencedMap<String, CycloneDxEmitter.Component> components = new LinkedHashMap<>();
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
                if (first < 0 || second < 0 || third < 0) {
                    continue;
                }
                String coordinate = key.substring(third + 1), licenseKey = key.substring(second + 1);
                if (components.containsKey(coordinate)) {
                    continue;
                }
                String value = dependencies.getProperty(key);
                int space = value.indexOf(' ');
                Path jar = argument.folder().resolve(space < 0 ? value : value.substring(0, space)).normalize();
                components.put(coordinate, component(coordinate,
                        Files.exists(jar) ? HexFormat.of().formatHex(hash.hash(jar)) : null,
                        readLicenses(licenses, licenseKey)));
            }
        }
        CycloneDxEmitter.Component project = new CycloneDxEmitter.Component(groupId,
                artifactId,
                version,
                "pkg:maven/" + groupId + "/" + artifactId + "@" + version,
                null,
                ownLicenses(metadata));
        String document = new CycloneDxEmitter().emit(format, project, new ArrayList<>(components.values()));

        Path embedded = Files.createDirectories(context.next()
                .resolve(RESOURCES).resolve("META-INF").resolve("sbom"));
        String fileName = artifactId + "." + format.extension();
        Files.writeString(embedded.resolve(fileName), document);
        SequencedProperties manifest = new SequencedProperties();
        manifest.setProperty("Sbom-Format", "CycloneDX");
        manifest.setProperty("Sbom-Location", "META-INF/sbom/" + fileName);
        manifest.store(context.next().resolve("manifest.mf"));
        Files.writeString(context.next().resolve(RESOURCES).resolve("META-INF").resolve("NOTICE"), notice(metadata));
        Path standalone = Files.createDirectories(context.next().resolve(SBOM));
        Files.writeString(standalone.resolve(artifactId + "-" + version + "." + format.extension()), document);
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static CycloneDxEmitter.Component component(String coordinate, String sha256, List<License> licenses) {
        try {
            MavenDependencyKey.Versioned parsed = MavenDependencyKey.tryParse(coordinate);
            if (parsed.version() != null) {
                MavenDependencyKey key = parsed.key();
                StringBuilder purl = new StringBuilder("pkg:maven/")
                        .append(key.groupId()).append("/").append(key.artifactId()).append("@").append(parsed.version());
                List<String> qualifiers = new ArrayList<>();
                if (key.type() != null && !key.type().equals("jar")) {
                    qualifiers.add("type=" + key.type());
                }
                if (key.classifier() != null) {
                    qualifiers.add("classifier=" + key.classifier());
                }
                if (!qualifiers.isEmpty()) {
                    purl.append("?").append(String.join("&", qualifiers));
                }
                return new CycloneDxEmitter.Component(key.groupId(), key.artifactId(), parsed.version(),
                        purl.toString(), sha256, licenses);
            }
        } catch (RuntimeException _) {
        }
        int last = coordinate.lastIndexOf('/');
        return new CycloneDxEmitter.Component(null,
                last < 0 ? coordinate : coordinate.substring(0, last),
                last < 0 ? "" : coordinate.substring(last + 1),
                null,
                sha256,
                licenses);
    }

    private static List<License> readLicenses(SequencedProperties licenses, String licenseKey) {
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
            if (rest.substring(hash + 1).equals("name")) {
                entry[0] = licenses.getProperty(key);
            } else if (rest.substring(hash + 1).equals("url")) {
                entry[1] = licenses.getProperty(key);
            }
        }
        return byIndex.values().stream().map(entry -> new License(entry[0], entry[1])).toList();
    }

    private static List<License> ownLicenses(SequencedProperties metadata) {
        SequencedMap<String, String[]> byId = new LinkedHashMap<>();
        for (String key : metadata.stringPropertyNames()) {
            if (!key.startsWith("license.")) {
                continue;
            }
            String suffix = key.substring("license.".length());
            int dot = suffix.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String[] entry = byId.computeIfAbsent(suffix.substring(0, dot), _ -> new String[2]);
            if (suffix.substring(dot + 1).equals("name")) {
                entry[0] = metadata.getProperty(key);
            } else if (suffix.substring(dot + 1).equals("url")) {
                entry[1] = metadata.getProperty(key);
            }
        }
        return byId.values().stream().map(entry -> new License(entry[0], entry[1])).toList();
    }

    private static String notice(SequencedProperties metadata) {
        String name = metadata.getProperty("name");
        StringBuilder builder = new StringBuilder(name == null
                ? metadata.getProperty("project") + ":" + metadata.getProperty("artifact")
                : name);
        builder.append("\n");
        String url = metadata.getProperty("url");
        if (url != null) {
            builder.append(url).append("\n");
        }
        for (License license : ownLicenses(metadata)) {
            builder.append("\nLicensed under ").append(license.name() == null ? "" : license.name());
            if (license.url() != null) {
                builder.append(" (").append(license.url()).append(")");
            }
            builder.append("\n");
        }
        return builder.toString();
    }
}
