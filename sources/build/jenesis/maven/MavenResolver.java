package build.jenesis.maven;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public interface MavenResolver extends Resolver {

    SequencedMap<Path, MavenLocalPom> local(Executor executor, Repository repository, Path root) throws IOException;

    SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies(Executor executor,
                                                                        MavenRepository repository,
                                                                        List<RootPom> rootPoms,
                                                                        MavenDependencyScope scope) throws IOException;

    static MavenResolver of(Resolver resolver) {
        if (resolver instanceof MavenResolver mavenResolver) {
            return mavenResolver;
        }
        throw new IllegalArgumentException("Resolver "
                + (resolver == null ? "null" : resolver.getClass().getName())
                + " is not a MavenResolver");
    }

    record RootPom(InputStream pom, String checksum) {

        public RootPom(InputStream pom) {
            this(pom, null);
        }
    }
}
