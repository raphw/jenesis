package build.jenesis.module;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
public class JenesisRawGitRepository implements Repository {

    private static final String GITHUB_DATA =
            "https://raw.githubusercontent.com/raphw/jenesis-modules/main/data/modules/";

    private final boolean requireNamedModules;
    private final URI data;
    private final URI repository;
    private final String token;

    public JenesisRawGitRepository(boolean requireNamedModules) {
        this(requireNamedModules, URI.create(GITHUB_DATA), mavenRepository(),
                System.getProperty("jenesis.maven.token", System.getenv("MAVEN_REPOSITORY_TOKEN")));
    }

    public JenesisRawGitRepository(boolean requireNamedModules, URI data, URI repository) {
        this(requireNamedModules, data, repository, null);
    }

    public JenesisRawGitRepository(boolean requireNamedModules, URI data, URI repository, String token) {
        this.requireNamedModules = requireNamedModules;
        this.data = trailingSlash(data);
        this.repository = trailingSlash(repository);
        this.token = token;
    }

    private static URI mavenRepository() {
        String environment = System.getProperty("jenesis.maven.uri", System.getenv("MAVEN_REPOSITORY_URI"));
        if (environment != null && !environment.endsWith("/")) {
            environment += "/";
        }
        return URI.create(environment == null ? "https://repo1.maven.org/maven2/" : environment);
    }

    private static URI trailingSlash(URI uri) {
        String text = uri.toString();
        return text.endsWith("/") ? uri : URI.create(text + "/");
    }

    @Override
    public Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException {
        int colon = coordinate.lastIndexOf(':');
        String type = colon < 0 ? "jar" : coordinate.substring(colon + 1);
        String identifier = colon < 0 ? coordinate : coordinate.substring(0, colon);
        Optional<RepositoryItem> item = fetch(identifier, type);
        if (item.isEmpty() && type.equals("jmod")) {
            return fetch(identifier, "jar");
        }
        return item;
    }

    private Optional<RepositoryItem> fetch(String identifier, String type) throws IOException {
        int slash = identifier.indexOf('/');
        String moduleName = slash < 0 ? identifier : identifier.substring(0, slash);
        String version = slash < 0 ? null : identifier.substring(slash + 1);
        // Module names cannot contain a dash, so a dash always introduces a classifier.
        int dash = moduleName.indexOf('-');
        String classifier = dash < 0 ? null : moduleName.substring(dash + 1);
        if (dash >= 0) {
            moduleName = moduleName.substring(0, dash);
        }
        requireSafeSegment("module name", moduleName);
        if (classifier != null) {
            requireSafeSegment("classifier", classifier);
        }
        if (version != null) {
            requireSafeSegment("version", version);
        }
        Coordinate resolved = resolve(moduleName, classifier, version);
        if (resolved == null) {
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
        String tsvName = (requireNamedModules ? "modules" : "artifacts")
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
            Coordinate row = requireNamedModules
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
