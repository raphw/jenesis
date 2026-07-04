package build.jenesis.module;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

public class JenesisModuleRepository implements JenesisRepository {

    private final URI root;
    private final String token;

    public static JenesisRepository of(Scope scope) {
        String token = System.getProperty("jenesis.module.token", System.getenv("JENESIS_REPOSITORY_TOKEN"));
        String property = System.getProperty("jenesis.module.uri");
        String environment = System.getenv("JENESIS_REPOSITORY_URI");
        Set<String> visited = new HashSet<>();
        String text;
        if (property != null) {
            text = property;
        } else if (environment != null) {
            text = environment;
            visited.add("JENESIS_REPOSITORY_URI");
        } else {
            text = "https://repo.jenesis.build/";
        }
        JenesisRepository repository = chain(text, visited, scope, token, null);
        if (repository == null) {
            throw new IllegalStateException("No Jenesis module repository is configured by: " + text);
        }
        return repository;
    }

    private static JenesisRepository chain(String text,
                                           Set<String> visited,
                                           Scope scope,
                                           String token,
                                           JenesisRepository repository) {
        for (String entry : text.split(",")) {
            String candidate = entry.strip();
            if (candidate.isEmpty()) {
                continue;
            }
            int separator = candidate.indexOf('|');
            String location = (separator < 0 ? candidate : candidate.substring(0, separator)).strip();
            if (location.isEmpty()) {
                throw new IllegalStateException("No URI in Jenesis module repository entry: " + candidate);
            }
            JenesisRepository current;
            if (location.startsWith("@")) {
                String name = location.substring(1);
                String value;
                if (name.isEmpty()) {
                    String environment = System.getenv("JENESIS_REPOSITORY_URI");
                    if (environment != null && visited.add("JENESIS_REPOSITORY_URI")) {
                        name = "JENESIS_REPOSITORY_URI";
                        value = environment;
                    } else {
                        name = null;
                        value = "https://repo.jenesis.build/";
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
                current = chain(value, visited, scope, token, null);
                if (name != null) {
                    visited.remove(name);
                }
                if (current == null) {
                    throw new IllegalStateException("No Jenesis module repository is configured by: " + value);
                }
            } else {
                current = new JenesisModuleRepository(
                        URI.create((location.endsWith("/") ? location : location + "/")
                                + (scope == Scope.MODULE ? "module/" : "artifact/")),
                        token);
            }
            List<String> modules = new ArrayList<>();
            if (separator >= 0) {
                for (String argument : candidate.substring(separator + 1).split("\\|")) {
                    String module = argument.strip();
                    if (!module.isEmpty()) {
                        modules.add(module);
                    }
                }
            }
            if (!modules.isEmpty()) {
                current = current.filter(value -> {
                    for (String module : modules) {
                        if (value.equals(module) || value.startsWith(module + ".")) {
                            return true;
                        }
                    }
                    return false;
                });
            }
            repository = repository == null ? current : current.prepend(repository);
        }
        return repository;
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
        String fileName = (classifier == null ? module : module + "-" + classifier) + "." + type;
        String relative = version == null
                ? module + "/" + fileName
                : module + "/" + version + "/" + fileName;
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
