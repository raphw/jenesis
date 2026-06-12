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
        String version = descriptor.rawVersion().orElse(null);
        String variant;
        if ("1.1.1".equals(version)) {
            variant = "the modern mutiny.zero 1.1.1";
        } else if (version == null) {
            variant = "the legacy jdk-flow variant of mutiny.zero 0.4.3";
        } else {
            throw new IllegalStateException("Unexpected mutiny.zero version: " + version);
        }
        Flow.Publisher<String> publisher = ZeroPublisher.fromItems(variant);
        String value = ZeroPublisher.toCompletionStage(publisher)
                .toCompletableFuture()
                .join()
                .orElseThrow();
        System.out.println("Selected variant: " + value);
    }
}
