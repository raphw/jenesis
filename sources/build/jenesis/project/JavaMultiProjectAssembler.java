package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Jar;
import build.jenesis.step.Javadoc;

public class JavaMultiProjectAssembler implements MultiProjectAssembler<ProjectModuleDescriptor> {

    @Override
    public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                     Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers) {
        return (sub, outerInherited) -> {
            BuildExecutorModule java;
            if (descriptor.tests()) {
                Properties module = new Properties();
                Path moduleFile = outerInherited.get(descriptor.manifests()).resolve(BuildStep.MODULE);
                if (Files.isRegularFile(moduleFile)) {
                    try (Reader reader = Files.newBufferedReader(moduleFile)) {
                        module.load(reader);
                    }
                }
                boolean test = module.getProperty("tests") != null;
                java = new JavaModule().test(test, null, repositories, resolvers);
            } else {
                java = new JavaModule();
            }
            sub.addModule("java", java,
                    descriptor.sources(),
                    descriptor.manifests(),
                    descriptor.resolved(DependencyScope.COMPILE),
                    descriptor.resolved(DependencyScope.RUNTIME),
                    descriptor.artifacts(DependencyScope.COMPILE),
                    descriptor.artifacts(DependencyScope.RUNTIME));
            if (descriptor.source()) {
                sub.addStep("sources", Jar.tool(Jar.Sort.SOURCES), descriptor.sources());
            }
            if (descriptor.javadoc()) {
                sub.addModule("javadoc", (module, inherited) -> {
                    module.addStep("classes", Javadoc.tool(), inherited.sequencedKeySet().stream());
                    module.addStep("artifacts", Jar.tool(Jar.Sort.JAVADOC), "classes");
                },
                descriptor.sources(),
                descriptor.manifests(),
                descriptor.resolved(DependencyScope.COMPILE),
                descriptor.resolved(DependencyScope.RUNTIME),
                descriptor.artifacts(DependencyScope.COMPILE),
                descriptor.artifacts(DependencyScope.RUNTIME));
            }
        };
    }
}
