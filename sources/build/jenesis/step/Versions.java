package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Versions implements BuildStep {

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, String> versions = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path requires = argument.folder().resolve(REQUIRES);
            if (!Files.exists(requires)) {
                continue;
            }
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(requires)) {
                properties.load(reader);
            }
            for (String property : properties.stringPropertyNames()) {
                int firstSlash = property.indexOf('/');
                if (firstSlash < 0) {
                    continue;
                }
                String rest = property.substring(firstSlash + 1);
                int secondSlash = rest.indexOf('/');
                if (secondSlash < 0 || rest.indexOf('/', secondSlash + 1) >= 0) {
                    continue;
                }
                versions.putIfAbsent(rest.substring(0, secondSlash), rest.substring(secondSlash + 1));
            }
        }
        Path target = Files.createDirectory(context.next().resolve(CLASSES));
        for (BuildStepArgument argument : arguments.values()) {
            Path source = argument.folder().resolve(CLASSES);
            if (!Files.exists(source)) {
                continue;
            }
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path destination = target.resolve(source.relativize(file));
                    if (file.getFileName().toString().equals("module-info.class")) {
                        Files.write(destination, stamp(Files.readAllBytes(file), versions));
                    } else {
                        Files.createLink(destination, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static byte[] stamp(byte[] bytes, Map<String, String> versions) {
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(bytes);
        return classFile.transformClass(model, (classBuilder, element) -> {
            if (element instanceof ModuleAttribute moduleAttribute) {
                classBuilder.with(rewrite(moduleAttribute, versions));
            } else {
                classBuilder.with(element);
            }
        });
    }

    private static ModuleAttribute rewrite(ModuleAttribute original, Map<String, String> versions) {
        return ModuleAttribute.of(original.moduleName(), builder -> {
            builder.moduleFlags(original.moduleFlagsMask());
            original.moduleVersion().ifPresent(version -> builder.moduleVersion(version.stringValue()));
            for (ModuleRequireInfo require : original.requires()) {
                String name = require.requires().name().stringValue();
                String version = versions.get(name);
                if (version != null) {
                    builder.requires(ModuleDesc.of(name), require.requiresFlagsMask(), version);
                } else {
                    builder.requires(require);
                }
            }
            original.exports().forEach(builder::exports);
            original.opens().forEach(builder::opens);
            original.uses().forEach(builder::uses);
            original.provides().forEach(builder::provides);
        });
    }
}
