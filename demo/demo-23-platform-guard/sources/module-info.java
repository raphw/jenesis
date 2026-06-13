/**
 * A modular Java sample that selects a dependency variant per platform. A
 * {@code @jenesis.pin} line may end with a bracketed guard that is matched
 * against the active platform: the detected operating system and chipset,
 * plus any token a {@code -Djenesis.platform.<token>=true} flag adds on top.
 * The most specific matching guard wins and the unguarded line is the
 * fallback. This demo guards with the neutral token {@code legacy} instead
 * of an OS name, so the selection is observable on any machine: the default
 * build resolves the modern {@code mutiny.zero} 1.1.1, while
 * {@code -Djenesis.platform.legacy=true} adds the {@code legacy} token and
 * resolves the classified {@code jdk-flow} variant of 0.4.3.
 *
 * @jenesis.release 25
 * @jenesis.main sample.Sample
 * @jenesis.pin mutiny.zero 1.1.1 SHA-256/2ba037374ea75e29921726d34a2ac426b88bd425a9e646802f905c117457a7a8
 * @jenesis.pin mutiny.zero :jdk-flow:0.4.3 SHA-256/0556f076191921250e5c9e21b9674d252bf2c4c515491e087fec93f383292b17 [legacy]
 */
module demo.platform {
    requires mutiny.zero;

    exports sample;
}
