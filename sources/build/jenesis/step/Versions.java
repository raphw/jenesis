package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;

public class Versions implements BuildStep {

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(DEPENDENCIES),
                Path.of(CLASSES)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, String> versions = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path requires = argument.folder().resolve(DEPENDENCIES);
            if (!Files.exists(requires)) {
                continue;
            }
            SequencedProperties properties = SequencedProperties.ofFiles(requires);
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
            Path manifest = argument.folder().resolve("manifest.mf");
            if (Files.exists(manifest)) {
                BuildStep.linkOrCopy(context.next().resolve("manifest.mf"), manifest);
            }
        }
        boolean requiresChanged = arguments.values().stream().anyMatch(arg -> {
            Checksum status = arg.files().get(Path.of(REQUIRES));
            return status != null && status.status() != ChecksumStatus.RETAINED;
        });
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
                        if (!requiresChanged && context.previous() != null) {
                            Path argumentRelative = argument.folder().relativize(file);
                            Checksum status = argument.files().get(argumentRelative);
                            if (status != null && status.status() == ChecksumStatus.RETAINED) {
                                Path priorOutput = context.previous().resolve(CLASSES).resolve(source.relativize(file));
                                if (Files.exists(priorOutput)) {
                                    BuildStep.linkOrCopy(destination, priorOutput);
                                    return FileVisitResult.CONTINUE;
                                }
                            }
                        }
                        ClassFile classFile = ClassFile.of();
                        ClassModel model = classFile.parse(Files.readAllBytes(file));
                        Files.write(destination, classFile.transformClass(model, (classBuilder, element) -> {
                            if (element instanceof ModuleAttribute moduleAttribute) {
                                classBuilder.with(ModuleAttribute.of(moduleAttribute.moduleName(), builder -> {
                                    builder.moduleFlags(moduleAttribute.moduleFlagsMask());
                                    moduleAttribute.moduleVersion().ifPresent(
                                            version -> builder.moduleVersion(version.stringValue()));
                                    for (ModuleRequireInfo require : moduleAttribute.requires()) {
                                        String name = require.requires().name().stringValue();
                                        String version = versions.get(name);
                                        if (version != null) {
                                            builder.requires(ModuleDesc.of(name), require.requiresFlagsMask(), version);
                                        } else {
                                            builder.requires(require);
                                        }
                                    }
                                    moduleAttribute.exports().forEach(builder::exports);
                                    moduleAttribute.opens().forEach(builder::opens);
                                    moduleAttribute.uses().forEach(builder::uses);
                                    moduleAttribute.provides().forEach(builder::provides);
                                }));
                            } else {
                                classBuilder.with(element);
                            }
                        }));
                    } else {
                        BuildStep.linkOrCopy(destination, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
