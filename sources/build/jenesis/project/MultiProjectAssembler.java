package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

@FunctionalInterface
public interface MultiProjectAssembler<D extends ProjectModule> {

    BuildExecutorModule apply(D descriptor,
                              Map<String, Repository> repositories,
                              Map<String, Resolver> resolvers);
}
