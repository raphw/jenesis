/**
 * @jenesis.main sample.Sample
 * @jenesis.pin @tool/org.json 20251224 SHA-256/2a3438fbf9293174ea82bdfa35e83d8b965cfe6bddc034a1ac5d60107174c209
 */
// TODO Pinning is incomplete: the @tool/build.jenesis dependency resolves from the
//  local export with a 0-SNAPSHOT hash that would not match a released artifact, so
//  it is left unpinned (allowed as an internal, locally-served dependency). Pin it
//  once build.jenesis is published with a stable version and checksum.
module demo.internal {
    exports sample;
}
