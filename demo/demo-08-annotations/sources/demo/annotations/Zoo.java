package demo.annotations;

public final class Zoo {

    public Animal lion() {
        return ImmutableAnimal.builder()
                .name("lion")
                .legs(4)
                .build();
    }
}
