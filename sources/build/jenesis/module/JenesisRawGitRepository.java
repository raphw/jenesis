package build.jenesis.module;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
public class JenesisRawGitRepository implements JenesisRepository {

    private static final String GITHUB_DATA =
            "https://raw.githubusercontent.com/raphw/jenesis-modules/main/data/modules/";

    private final Scope scope;
    private final URI data;
    private final URI repository;
    private final String token;
    private final Predicate<String> predicate;

    public JenesisRawGitRepository(Scope scope, URI data, URI repository) {
        this(scope, data, repository, null);
    }

    public JenesisRawGitRepository(Scope scope, URI data, URI repository, String token) {
        this(scope, trailingSlash(data), trailingSlash(repository), token, _ -> true);
    }

    private JenesisRawGitRepository(Scope scope,
                                    URI data,
                                    URI repository,
                                    String token,
                                    Predicate<String> predicate) {
        this.scope = scope;
        this.data = data;
        this.repository = repository;
        this.token = token;
        this.predicate = predicate;
    }

    public JenesisRawGitRepository groups(Predicate<String> predicate) {
        return new JenesisRawGitRepository(scope, data, repository, token, predicate);
    }

    public static JenesisRepository of(Scope scope) {
        String token = System.getProperty("jenesis.maven.token", System.getenv("MAVEN_REPOSITORY_TOKEN"));
        String property = System.getProperty("jenesis.maven.uri");
        String environment = System.getenv("MAVEN_REPOSITORY_URI");
        Set<String> visited = new HashSet<>();
        String text;
        if (property != null) {
            text = property;
        } else if (environment != null) {
            text = environment;
            visited.add("MAVEN_REPOSITORY_URI");
        } else {
            text = "https://repo1.maven.org/maven2/";
        }
        JenesisRepository repository = chain(text, visited, scope, token, null, null);
        if (repository == null) {
            throw new IllegalStateException("No Maven repository is configured by: " + text);
        }
        return repository;
    }

    private static JenesisRepository chain(String text,
                                           Set<String> visited,
                                           Scope scope,
                                           String token,
                                           Predicate<String> inherited,
                                           JenesisRepository repository) {
        for (String entry : text.split(",")) {
            String candidate = entry.strip();
            if (candidate.isEmpty()) {
                continue;
            }
            int separator = candidate.indexOf('|');
            String location = (separator < 0 ? candidate : candidate.substring(0, separator)).strip();
            if (location.isEmpty()) {
                throw new IllegalStateException("No URI in Maven repository entry: " + candidate);
            }
            List<String> groups = new ArrayList<>();
            if (separator >= 0) {
                for (String argument : candidate.substring(separator + 1).split("\\|")) {
                    String group = argument.strip();
                    if (!group.isEmpty()) {
                        groups.add(group);
                    }
                }
            }
            Predicate<String> own = groups.isEmpty() ? null : value -> {
                for (String group : groups) {
                    if (value.equals(group) || value.startsWith(group + ".")) {
                        return true;
                    }
                }
                return false;
            };
            Predicate<String> effective;
            if (inherited == null) {
                effective = own;
            } else if (own == null) {
                effective = inherited;
            } else {
                Predicate<String> combining = own;
                effective = value -> inherited.test(value) && combining.test(value);
            }
            JenesisRepository current;
            if (location.startsWith("@")) {
                String name = location.substring(1);
                String value;
                if (name.isEmpty()) {
                    String environment = System.getenv("MAVEN_REPOSITORY_URI");
                    if (environment != null && visited.add("MAVEN_REPOSITORY_URI")) {
                        name = "MAVEN_REPOSITORY_URI";
                        value = environment;
                    } else {
                        name = null;
                        value = "https://repo1.maven.org/maven2/";
                    }
                } else {
                    value = System.getProperty(name, System.getenv(name));
                    if (value == null) {
                        throw new IllegalStateException("Unresolved repository reference: @" + name);
                    }
                    if (!visited.add(name)) {
                        throw new IllegalStateException("Circular repository reference: @" + name);
                    }
                }
                current = chain(value, visited, scope, token, effective, null);
                if (name != null) {
                    visited.remove(name);
                }
                if (current == null) {
                    throw new IllegalStateException("No Maven repository is configured by: " + value);
                }
            } else {
                JenesisRawGitRepository base = new JenesisRawGitRepository(scope,
                        URI.create(GITHUB_DATA),
                        URI.create(location),
                        token);
                current = effective == null ? base : base.groups(effective);
            }
            repository = repository == null ? current : current.prepend(repository);
        }
        return repository;
    }

    private static URI trailingSlash(URI uri) {
        String text = uri.toString();
        return text.endsWith("/") ? uri : URI.create(text + "/");
    }

    @Override
    public Optional<RepositoryItem> fetch(Executor executor,
                                          String module,
                                          String classifier,
                                          String version,
                                          String type) throws IOException {
        requireSafeSegment("module name", module);
        if (classifier != null) {
            requireSafeSegment("classifier", classifier);
        }
        if (version != null) {
            requireSafeSegment("version", version);
        }
        Coordinate resolved = resolve(module, classifier, version);
        if (resolved == null || !predicate.test(resolved.groupId())) {
            return Optional.empty();
        }
        String path = resolved.groupId().replace('.', '/')
                + "/" + resolved.artifactId()
                + "/" + resolved.version()
                + "/" + resolved.artifactId() + "-" + resolved.version()
                + (classifier == null ? "" : "-" + classifier) + "." + type;
        return open(repository.resolve(path), token).map(stream -> (RepositoryItem) () -> stream);
    }

    private Coordinate resolve(String moduleName, String classifier, String version) throws IOException {
        String tsvName = (scope == Scope.MODULE ? "modules" : "artifacts")
                + (classifier == null ? "" : "-" + classifier) + ".tsv";
        URI tsvUri = data.resolve(moduleName.replace('.', '/') + "/" + tsvName);
        Optional<InputStream> stream = open(tsvUri, null);
        if (stream.isEmpty()) {
            return null;
        }
        String tsv;
        try (InputStream open = stream.get()) {
            tsv = new String(open.readAllBytes(), StandardCharsets.UTF_8);
        }
        return pickRow(tsv, version);
    }

    private Coordinate pickRow(String tsv, String version) {
        Coordinate newest = null;
        for (String line : tsv.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] columns = line.split("\t");
            if (columns.length < 4) {
                continue;
            }
            Coordinate row = scope == Scope.MODULE
                    ? new Coordinate(columns[1], columns[2], columns[3])
                    : new Coordinate(columns[2], columns[3], columns[0]);
            if (newest == null) {
                newest = row;
            }
            if (version == null || columns[0].equals(version)) {
                return row;
            }
        }
        if (version != null && newest != null) {
            return new Coordinate(newest.groupId(), newest.artifactId(), version);
        }
        return null;
    }

    private static Optional<InputStream> open(URI uri, String token) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return Optional.of(Repository.open(uri, token));
            } catch (FileNotFoundException _) {
                return Optional.empty();
            } catch (IOException e) {
                failure = e;
                if (attempt < 3) {
                    try {
                        Thread.sleep(500L << attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while fetching " + uri, interrupted);
                    }
                }
            }
        }
        throw failure;
    }

    private static void requireSafeSegment(String role, String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blank " + role + " is not a valid coordinate");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Illegal " + role + " '" + value + "': path traversal is not permitted");
            }
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean permitted = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == '-'
                    || character == '_'
                    || character == '+';
            if (!permitted) {
                throw new IllegalArgumentException(
                        "Illegal " + role + " '" + value + "': character '" + character + "' is not permitted");
            }
        }
    }

    private record Coordinate(String groupId, String artifactId, String version) {
    }
}
