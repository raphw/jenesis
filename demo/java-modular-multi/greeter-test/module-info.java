/**
 * The test module for demo.greeter. The @jenesis.test tag marks it as a test
 * variant of demo.greeter, so it is compiled and run but never staged as a
 * published artifact. Its JUnit closure is pinned on the plain module trail.
 *
 * @jenesis.release 25
 * @jenesis.test demo.greeter
 * @jenesis.pin org.apiguardian.api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.junit.jupiter 5.11.3 SHA-256/ac7578efed162367c3ddc006338e07d4571510fd9866642ea93d5b9e4ed2f665
 * @jenesis.pin org.junit.jupiter.api 5.11.3 SHA-256/5d8147a60f49453973e250ed68701b7ff055964fe2462fc2cb1ec1d6d44889ba
 * @jenesis.pin org.junit.jupiter.engine 5.11.3 SHA-256/e62420c99f7c0d59a2159a2ef63e61877e9c80bd722c03ca8bf3bdcea050a589
 * @jenesis.pin org.junit.jupiter.params 5.11.3 SHA-256/0f798ebec744c4e6605fd4f2072f41a8e989e2d469e21db5aa67cf799c0b51ec
 * @jenesis.pin org.junit.platform.commons 1.11.3 SHA-256/be262964b0b6b48de977c61d4f931df8cf61e80e750cc3f3a0a39cdd21c1008c
 * @jenesis.pin org.junit.platform.console 1.11.4 SHA-256/a9c3309cdfded3542200de85da6cb274864439d6b02ba80bb45ecc8e0bdf1be7
 * @jenesis.pin org.junit.platform.engine 1.11.3 SHA-256/0043f72f611664735da8dc9a308bf12ecd2236b05339351c4741edb4d8fab0da
 * @jenesis.pin org.junit.platform.launcher 1.11.4 SHA-256/d7430bd029e7fcced53ee445e4d2d1a8a1e043ea4c4df43b6335a857f79761ae
 * @jenesis.pin org.junit.platform.reporting 1.11.4 SHA-256/df6896109bfaef4de8d2fa9e3371a6176936d1a45a6c0e7fd8f7e6dd6f4c5597
 * @jenesis.pin org.opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 */
open module demo.greeter.test {
    requires demo.greeter;
    requires org.junit.jupiter;
}
