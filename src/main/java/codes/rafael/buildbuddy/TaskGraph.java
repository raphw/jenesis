package codes.rafael.buildbuddy;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

public class TaskGraph<IDENTITY, INPUT, OUTPUT> implements Function<OUTPUT, CompletionStage<OUTPUT>> {

    private final Executor executor;
    private final BiFunction<OUTPUT, OUTPUT, OUTPUT> merge;
    private final BiFunction<Set<IDENTITY>, OUTPUT, INPUT> initiate;
    private final Map<IDENTITY, Registration<IDENTITY, INPUT, OUTPUT>> registrations = new LinkedHashMap<>();

    public TaskGraph(Executor executor, BiFunction<OUTPUT, OUTPUT, OUTPUT> merge, BiFunction<Set<IDENTITY>, OUTPUT, INPUT> initiate) {
        this.executor = executor;
        this.merge = merge;
        this.initiate = initiate;
    }

    public void add(
            IDENTITY identity,
            Function<INPUT, CompletionStage<OUTPUT>> step,
            IDENTITY... dependencies
    ) {
        if (!registrations.keySet().containsAll(List.of(dependencies))) {
            throw new IllegalArgumentException("Unknown dependencies: " + Arrays.stream(dependencies)
                    .filter(dependency -> !registrations.containsKey(dependency))
                    .distinct()
                    .toList());
        }
        if (registrations.putIfAbsent(identity, new Registration<>(step, Set.of(dependencies))) != null) {
            throw new IllegalArgumentException("Step already registered: " + identity);
        }
    }

    @Override
    public CompletionStage<OUTPUT> apply(OUTPUT output) {
        CompletionStage<OUTPUT> initial = CompletableFuture.completedStage(output);
        Map<IDENTITY, CompletionStage<OUTPUT>> dispatched = new HashMap<>();
        Set<IDENTITY> last = new HashSet<>();
        while (!dispatched.keySet().containsAll(registrations.keySet())) {
            registrations.entrySet().stream()
                    .filter(entry -> !dispatched.containsKey(entry.getKey()))
                    .filter(entry -> dispatched.keySet().containsAll(entry.getValue().dependencies()))
                    .forEach(registration -> {
                        CompletionStage<OUTPUT> future = initial;
                        for (IDENTITY dependency : registration.getValue().dependencies()) {
                            future = future.thenCombineAsync(dispatched.get(dependency), merge, executor);
                            last.remove(dependency);
                        }
                        dispatched.put(registration.getKey(), future
                                .thenApplyAsync(merged -> initiate.apply(registration.getValue().dependencies(), merged), executor)
                                .thenComposeAsync(input -> registration.getValue().step().apply(input), executor));
                        last.add(registration.getKey());
                    });
        }
        return last.stream()
                .map(dispatched::get)
                .reduce(initial, (left, right) -> left.thenCombineAsync(right, merge, executor));
    }

    record Registration<IDENTITY, INPUT, OUTPUT> (
            Function<INPUT, CompletionStage<OUTPUT>> step,
            Set<IDENTITY> dependencies
    ) { }
}
