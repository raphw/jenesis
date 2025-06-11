package build.buildbuddy.maven;

import build.buildbuddy.Repository;
import build.buildbuddy.RepositoryItem;

import module java.base;

@FunctionalInterface
public interface MavenRepository extends Repository {

    @Override
    default Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException {
        String[] elements = coordinate.split("/", 5);
        return switch (elements.length) {
            case 3 -> fetch(executor, elements[0], elements[1], elements[2], "jar", null, null);
            case 4 -> fetch(executor, elements[0], elements[1], elements[3], elements[2], null, null);
            case 5 -> fetch(executor, elements[0], elements[1], elements[4], elements[2], elements[3], null);
            default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + coordinate);
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
                return MavenRepository.this.fetchMetadata(executor, groupId, artifactId, checksum); // TODO: update?
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
            Optional<RepositoryItem> candidate = repository.fetch(executor, groupId
                    + "/" + artifactId
                    + (type == null ? "/jar" : "/" + type)
                    + "/" + version
                    + (classifier == null ? "" : "/" + classifier));
            if (type == null && candidate.isEmpty()) {
                candidate = repository.fetch(executor, groupId
                        + "/" + artifactId
                        + "/" + version
                        + (classifier == null ? "" : "/" + classifier));
            }
            return candidate;
        };
    }
}
