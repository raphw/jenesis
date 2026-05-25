package build.jenesis.module;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

public class JenesisModuleRepository implements Repository {

    private final URI root;
    private final String token;

    public JenesisModuleRepository(boolean requireNamedModules) {
        String uri = System.getenv("JENESIS_REPOSITORY_URI");
        if (uri == null) {
            uri = "https://repo.jenesis.build/";
        } else if (!uri.endsWith("/")) {
            uri = uri + "/";
        }
        this.root = URI.create(uri + (requireNamedModules ? "module/" : "artifact/"));
        this.token = System.getenv("JENESIS_REPOSITORY_TOKEN");
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
        String override = System.getenv("JENESIS_REPOSITORY_LOCAL");
        Path path = override == null
                ? Path.of(System.getProperty("user.home")).resolve(".jenesis")
                : Path.of(override);
        return new JenesisModuleRepository(path.toUri());
    }

    @Override
    public Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException {
        int slash = coordinate.indexOf('/');
        String moduleName = slash < 0 ? coordinate : coordinate.substring(0, slash);
        String version = slash < 0 ? null : coordinate.substring(slash + 1);
        String relative = version == null
                ? moduleName + "/" + moduleName + ".jar"
                : moduleName + "/" + version + "/" + moduleName + ".jar";
        URI uri = root.resolve(relative);
        if ("file".equals(uri.getScheme())) {
            Path file = Path.of(uri);
            return Files.isRegularFile(file)
                    ? Optional.of(RepositoryItem.ofFile(file, true))
                    : Optional.empty();
        }
        InputStream stream;
        try {
            URLConnection connection = uri.toURL().openConnection();
            if (token != null && connection instanceof HttpURLConnection http) {
                http.setRequestProperty("Authorization", token);
            }
            stream = connection.getInputStream();
        } catch (FileNotFoundException _) {
            return Optional.empty();
        }
        return Optional.of(() -> stream);
    }
}
