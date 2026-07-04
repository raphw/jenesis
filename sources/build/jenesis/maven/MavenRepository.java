package build.jenesis.maven;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

@FunctionalInterface
public interface MavenRepository extends Repository {

    @Override
    default Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException {
        MavenDependencyKey.Versioned parsed = MavenDependencyKey.parse(coordinate);
        return fetch(executor,
                parsed.key().groupId(),
                parsed.key().artifactId(),
                parsed.version(),
                parsed.key().type(),
                parsed.key().classifier(),
                null);
    }

    @Override
    default MavenRepository cached(Path folder) {
        return folder == null ? this : caching(Repository.super.cached(folder));
    }

    @Override
    default MavenRepository materialized(Path folder) {
        return folder == null ? this : caching(Repository.super.materialized(folder));
    }

    private MavenRepository caching(Repository cached) {
        return new MavenRepository() {
            @Override
            public Optional<RepositoryItem> fetch(Executor executor,
                                                  String groupId,
                                                  String artifactId,
                                                  String version,
                                                  String type,
                                                  String classifier,
                                                  String checksum) throws IOException {
                if (checksum != null) {
                    return MavenRepository.this.fetch(executor, groupId, artifactId, version, type, classifier, checksum);
                }
                return cached.fetch(executor,
                        new MavenDependencyKey(groupId, artifactId, type, classifier).coordinate(null, version));
            }

            @Override
            public Optional<RepositoryItem> fetchMetadata(Executor executor,
                                                          String groupId,
                                                          String artifactId,
                                                          String checksum) throws IOException {
                return MavenRepository.this.fetchMetadata(executor, groupId, artifactId, checksum);
            }
        };
    }

    @Override
    default MavenRepository prepend(Repository repository) {
        MavenRepository mavenRepository = of(repository);
        return new MavenRepository() {
            @Override
            public Optional<RepositoryItem> fetch(Executor executor,
                                                  String groupId,
                                                  String artifactId,
                                                  String version,
                                                  String type,
                                                  String classifier,
                                                  String checksum) throws IOException {
                Optional<RepositoryItem> candidate = mavenRepository.fetch(executor,
                        groupId,
                        artifactId,
                        version,
                        type,
                        classifier,
                        checksum);
                return candidate.isPresent()
                        ? candidate
                        : MavenRepository.this.fetch(executor, groupId, artifactId, version, type, classifier, checksum);
            }

            @Override
            public Optional<RepositoryItem> fetchMetadata(Executor executor,
                                                          String groupId,
                                                          String artifactId,
                                                          String checksum) throws IOException {
                Optional<RepositoryItem> candidate = mavenRepository.fetchMetadata(executor,
                        groupId,
                        artifactId,
                        checksum);
                return candidate.isPresent()
                        ? candidate
                        : MavenRepository.this.fetchMetadata(executor, groupId, artifactId, checksum);
            }
        };
    }

    default MavenRepository filter(Predicate<String> predicate) {
        return new MavenRepository() {
            @Override
            public Optional<RepositoryItem> fetch(Executor executor,
                                                  String groupId,
                                                  String artifactId,
                                                  String version,
                                                  String type,
                                                  String classifier,
                                                  String checksum) throws IOException {
                return predicate.test(groupId)
                        ? MavenRepository.this.fetch(executor, groupId, artifactId, version, type, classifier, checksum)
                        : Optional.empty();
            }

            @Override
            public Optional<RepositoryItem> fetchMetadata(Executor executor,
                                                          String groupId,
                                                          String artifactId,
                                                          String checksum) throws IOException {
                return predicate.test(groupId)
                        ? MavenRepository.this.fetchMetadata(executor, groupId, artifactId, checksum)
                        : Optional.empty();
            }
        };
    }

    Optional<RepositoryItem> fetch(Executor executor,
                                   String groupId,
                                   String artifactId,
                                   String version,
                                   String type,
                                   String classifier,
                                   String checksum) throws IOException;


    default Optional<RepositoryItem> fetchMetadata(Executor executor,
                                                   String groupId,
                                                   String artifactId,
                                                   String checksum) throws IOException {
        return Optional.empty();
    }

    static MavenRepository of(Repository repository) {
        return repository instanceof MavenRepository mavenRepository ? mavenRepository : (executor,
                                                                                          groupId,
                                                                                          artifactId,
                                                                                          version,
                                                                                          type,
                                                                                          classifier,
                                                                                          checksum) -> {
            if (checksum != null) {
                return Optional.empty();
            }
            Optional<RepositoryItem> candidate = repository.fetch(executor,
                    new MavenDependencyKey(groupId, artifactId, type, classifier).coordinate(null, version));
            if (candidate.isEmpty() && classifier == null && (type == null || "jar".equals(type))) {
                candidate = repository.fetch(executor, groupId + "/" + artifactId + "/jar/" + version);
            }
            return candidate;
        };
    }
}
