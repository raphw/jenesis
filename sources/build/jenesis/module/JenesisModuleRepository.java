package build.jenesis.module;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

public class JenesisModuleRepository implements Repository {

    private final URI root;
    private final String token;

    public JenesisModuleRepository(boolean requireNamedModules) {
        String uri = System.getProperty("jenesis.module.uri", System.getenv("JENESIS_REPOSITORY_URI"));
        if (uri == null) {
            uri = "https://repo.jenesis.build/";
        } else if (!uri.endsWith("/")) {
            uri = uri + "/";
        }
        this.root = URI.create(uri + (requireNamedModules ? "module/" : "artifact/"));
        this.token = System.getProperty("jenesis.module.token", System.getenv("JENESIS_REPOSITORY_TOKEN"));
    }

    public JenesisModuleRepository(URI root) {
        this(root, null);
    }

    public JenesisModuleRepository(URI root, String token) {
        String text = root.toString();
        this.root = text.endsWith("/") ? root : URI.create(text + "/");
        this.token = token;
    }

    public static JenesisModuleRepository ofLocal() {
        String override = System.getProperty("jenesis.module.local", System.getenv("JENESIS_REPOSITORY_LOCAL"));
        Path path = override == null
                ? Path.of(System.getProperty("user.home")).resolve(".jenesis")
                : Path.of(override);
        return new JenesisModuleRepository(path.toUri());
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
        String fileName = (classifier == null ? moduleName : moduleName + "-" + classifier) + "." + type;
        String relative = version == null
                ? moduleName + "/" + fileName
                : moduleName + "/" + version + "/" + fileName;
        URI base = root.normalize();
        URI uri = base.resolve(relative).normalize();
        URI contained = base.relativize(uri);
        if (contained.isAbsolute() || contained.getPath().startsWith("..")) {
            throw new IllegalArgumentException("Resolved location " + uri + " escapes repository root " + root);
        }
        if ("file".equals(uri.getScheme())) {
            Path file = Path.of(uri);
            return Files.isRegularFile(file)
                    ? Optional.of(RepositoryItem.ofFile(file, true))
                    : Optional.empty();
        }
        InputStream stream = null;
        IOException failure = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                stream = Repository.open(uri, token);
                failure = null;
                break;
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
        if (failure != null) {
            throw failure;
        }
        InputStream fetched = stream;
        return Optional.of(() -> fetched);
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
}
