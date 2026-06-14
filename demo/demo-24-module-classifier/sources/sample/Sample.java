package sample;

import mutiny.zero.ZeroPublisher;

import java.lang.module.ModuleDescriptor;
import java.util.concurrent.Flow;

public class Sample {

    public static void main(String[] args) {
        ModuleDescriptor descriptor = ZeroPublisher.class.getModule().getDescriptor();
        if (descriptor == null) {
            throw new IllegalStateException("Expected mutiny.zero to run as a named module");
        }
        for (ModuleDescriptor.Requires requires : descriptor.requires()) {
            if (requires.name().equals("org.reactivestreams")) {
                throw new IllegalStateException("Resolved the unclassified mutiny.zero artifact: "
                        + "it requires org.reactivestreams, so the jdk-flow classifier was not applied");
            }
        }
        Flow.Publisher<String> publisher = ZeroPublisher.fromItems("the jdk-flow variant of mutiny.zero");
        String value = ZeroPublisher.toCompletionStage(publisher)
                .toCompletableFuture()
                .join()
                .orElseThrow();
        System.out.println("Published through java.util.concurrent.Flow: " + value);
        System.out.println("Requires of mutiny.zero: " + descriptor.requires());
    }
}
