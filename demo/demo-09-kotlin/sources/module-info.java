/**
 * @jenesis.release 25
 * @jenesis.pin kotlin.stdlib 1.9.10
 * @jenesis.pin kotlin/maven/org.jetbrains.kotlin/kotlin-build-tools-api 2.4.0-RC2 SHA-256/ca5f829936a739cb90146359159a8ecdfe2f1a0ae21f8e2da5868b6413320a1d
 * @jenesis.pin kotlin/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable 2.4.0-RC2 SHA-256/923181ab0d2dc773b36d526081567bd7755f06e26d32c0f8a6537a31cf0a0229
 * @jenesis.pin kotlin/maven/org.jetbrains.kotlin/kotlin-daemon-embeddable 2.4.0-RC2 SHA-256/2fc1f1ee5f7fccda091bf34473c6cee44f131ea1b7da9f600a706fdc68716dba
 * @jenesis.pin kotlin/maven/org.jetbrains.kotlin/kotlin-reflect 1.6.10 SHA-256/3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203
 * @jenesis.pin kotlin/maven/org.jetbrains.kotlin/kotlin-script-runtime 2.4.0-RC2 SHA-256/676934238966d037834d8285bfd7bd14c0c57f3b7ddd597f7ebd9e3c574a812f
 * @jenesis.pin kotlin/maven/org.jetbrains.kotlin/kotlin-stdlib 2.4.0-RC2 SHA-256/c67ed4aa99b5766e016f7c1a5d76424b67512cc8b6ca3a9f4ea97526bfee7a5e
 * @jenesis.pin kotlin/maven/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 1.8.0 SHA-256/9860906a1937490bf5f3b06d2f0e10ef451e65b95b269f22daf68a3d1f5065c5
 * @jenesis.pin org.jetbrains.kotlin/kotlin-stdlib 1.9.10 SHA-256/55e989c512b80907799f854309f3bc7782c5b3d13932442d0379d5c472711504
 * @jenesis.pin org.jetbrains.kotlin/kotlin-stdlib-common 1.9.10 SHA-256/cde3341ba18a2ba262b0b7cf6c55b20c90e8d434e42c9a13e6a3f770db965a88
 * @jenesis.pin org.jetbrains/annotations 13.0 SHA-256/ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478
 */
module sample.kotlin {
    requires kotlin.stdlib;

    exports sample;
    exports sample.pure;
}
