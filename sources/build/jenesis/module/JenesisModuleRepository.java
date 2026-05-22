package build.jenesis.module;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

public class JenesisModuleRepository implements Repository {

    private final URI root;
    private final String token;

    public JenesisModuleRepository() {
        String override = System.getenv("JENESIS_MODULE_REPOSITORY");
        Path path = override == null
                ? Path.of(System.getProperty("user.home")).resolve(".jenesis")
                : Path.of(override);
        this(path.toUri());
    }

    public JenesisModuleRepository(URI root) {
        this(root, null);
    }

    public JenesisModuleRepository(URI root, String token) {
        String text = root.toString();
        this.root = text.endsWith("/") ? root : URI.create(text + "/");
        this.token = token;
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
