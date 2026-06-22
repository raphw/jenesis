package build.jenesis;

import module java.base;

public final class BuildExecutorLayeredCache implements BuildExecutorCache {

    private final BuildExecutorCache front;
    private final BuildExecutorCache back;

    public BuildExecutorLayeredCache(BuildExecutorCache front, BuildExecutorCache back) {
        this.front = front;
        this.back = back;
    }

    @Override
    public Optional<BuildStepResult> fetch(Executor executor,
                                           String identity,
                                           byte[] step,
                                           SequencedMap<String, Map<Path, byte[]>> inputs,
                                           Path target) throws IOException {
        Optional<BuildStepResult> local = front.fetch(executor, identity, step, inputs, target);
        if (local.isPresent()) {
            try {
                back.touch(executor, identity, step, inputs);
            } catch (IOException | RuntimeException _) {
            }
            return local;
        }
        Optional<BuildStepResult> remote = back.fetch(executor, identity, step, inputs, target);
        if (remote.isPresent()) {
            try {
                front.store(executor, identity, step, inputs, target);
            } catch (IOException | RuntimeException _) {
            }
        }
        return remote;
    }

    @Override
    public void store(Executor executor,
                      String identity,
                      byte[] step,
                      SequencedMap<String, Map<Path, byte[]>> inputs,
                      Path output) throws IOException {
        try {
            front.store(executor, identity, step, inputs, output);
        } catch (IOException | RuntimeException _) {
        }
        back.store(executor, identity, step, inputs, output);
    }

    @Override
    public boolean stores() {
        return front.stores() || back.stores();
    }

    @Override
    public void touch(Executor executor,
                      String identity,
                      byte[] step,
                      SequencedMap<String, Map<Path, byte[]>> inputs) {
        try {
            front.touch(executor, identity, step, inputs);
        } catch (IOException | RuntimeException _) {
        }
        try {
            back.touch(executor, identity, step, inputs);
        } catch (IOException | RuntimeException _) {
        }
    }
}
