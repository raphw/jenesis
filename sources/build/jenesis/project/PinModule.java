package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;

public class PinModule implements BuildExecutorModule {

    private static final String RESOLVED_SUFFIX = "/dependencies/" + DependenciesModule.RESOLVED;
    private static final String MODULE_PREFIX = "module-", TEST_MODULE_PREFIX = "test-module-";

    private final Path root;
    private final String fileName;
    private final Function<Path, BuildStep> stepFactory;

    public PinModule(Path root, String fileName, Function<Path, BuildStep> stepFactory) {
        this.root = root;
        this.fileName = fileName;
        this.stepFactory = stepFactory;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedMap<String, List<String>> depsByEncoded = new LinkedHashMap<>();
        for (String key : inherited.keySet()) {
            if (!key.endsWith(RESOLVED_SUFFIX)) {
                continue;
            }
            String encoded = null;
            for (String segment : key.split("/")) {
                if (segment.startsWith(TEST_MODULE_PREFIX)) {
                    encoded = segment.substring(TEST_MODULE_PREFIX.length());
                    break;
                }
                if (segment.startsWith(MODULE_PREFIX)) {
                    encoded = segment.substring(MODULE_PREFIX.length());
                    break;
                }
            }
            if (encoded == null) {
                continue;
            }
            depsByEncoded.computeIfAbsent(encoded, _ -> new ArrayList<>()).add(key);
        }
        for (Map.Entry<String, List<String>> entry : depsByEncoded.entrySet()) {
            String encoded = entry.getKey();
            Path file = root
                    .resolve(URLDecoder.decode(encoded, StandardCharsets.UTF_8))
                    .resolve(fileName);
            buildExecutor.addStep(MODULE_PREFIX + encoded,
                    stepFactory.apply(file),
                    entry.getValue().toArray(String[]::new));
        }
    }
}
