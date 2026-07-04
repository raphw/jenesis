package build.jenesis.module;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

@FunctionalInterface
public interface JenesisRepository extends Repository {

    enum Scope {
        MODULE,
        ARTIFACT
    }

    @Override
    default Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException {
        int colon = coordinate.lastIndexOf(':');
        String type = colon < 0 ? "jar" : coordinate.substring(colon + 1);
        String identifier = colon < 0 ? coordinate : coordinate.substring(0, colon);
        int slash = identifier.indexOf('/');
        String module = slash < 0 ? identifier : identifier.substring(0, slash);
        String version = slash < 0 ? null : identifier.substring(slash + 1);
        // Module names cannot contain a dash, so a dash always introduces a classifier.
        int dash = module.indexOf('-');
        String classifier = dash < 0 ? null : module.substring(dash + 1);
        if (dash >= 0) {
            module = module.substring(0, dash);
        }
        Optional<RepositoryItem> item = fetch(executor, module, classifier, version, type);
        if (item.isEmpty() && type.equals("jmod")) {
            return fetch(executor, module, classifier, version, "jar");
        }
        return item;
    }

    @Override
    default JenesisRepository cached(Path folder) {
        if (folder == null) {
            return this;
        }
        Repository cached = Repository.super.cached(folder);
        return (executor, module, classifier, version, type) -> cached.fetch(
                executor,
                coordinate(module, classifier, version, type));
    }

    @Override
    default JenesisRepository materialized(Path folder) {
        if (folder == null) {
            return this;
        }
        Repository materialized = Repository.super.materialized(folder);
        return (executor, module, classifier, version, type) -> materialized.fetch(
                executor,
                coordinate(module, classifier, version, type));
    }

    @Override
    default JenesisRepository prepend(Repository repository) {
        JenesisRepository jenesisRepository = of(repository);
        return (executor, module, classifier, version, type) -> {
            Optional<RepositoryItem> candidate = jenesisRepository.fetch(executor, module, classifier, version, type);
            return candidate.isPresent()
                    ? candidate
                    : fetch(executor, module, classifier, version, type);
        };
    }

    default JenesisRepository filter(Predicate<String> predicate) {
        return (executor, module, classifier, version, type) -> predicate.test(module)
                ? fetch(executor, module, classifier, version, type)
                : Optional.empty();
    }

    Optional<RepositoryItem> fetch(Executor executor,
                                   String module,
                                   String classifier,
                                   String version,
                                   String type) throws IOException;

    static JenesisRepository of(Repository repository) {
        return repository instanceof JenesisRepository jenesisRepository ? jenesisRepository : (executor,
                                                                                                module,
                                                                                                classifier,
                                                                                                version,
                                                                                                type) -> repository.fetch(executor, coordinate(module, classifier, version, type));
    }

    private static String coordinate(String module, String classifier, String version, String type) {
        return (classifier == null ? module : module + "-" + classifier)
                + (version == null ? "" : "/" + version)
                + (type == null || type.equals("jar") ? "" : ":" + type);
    }
}
