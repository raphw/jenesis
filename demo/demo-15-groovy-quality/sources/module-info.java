/**
 * The Groovy counterpart to the Java code-quality demo. A {@code codenarc.xml}
 * activates CodeNarc, which lints the Groovy sources. There is no inferred
 * Groovy formatter, so this demo is lint-only. The Groovy compiler is pinned in
 * its own {@code groovyc} group while CodeNarc floats its own {@code RELEASE}.
 *
 * @jenesis.release 25
 * @jenesis.pin groovyc/maven/org.apache.groovy/groovy 6.0.0-alpha-1 SHA-256/f98453919a23cb8cfa36dcf7176fdcf13350cb2baa65236b081a601848f0350f
 * @jenesis.pin org.apache.groovy 5.0.6
 * @jenesis.pin org.apache.groovy/groovy 5.0.6 SHA-256/32338cdd9f6d842a534ea086242bf874385ee5be6973dc3de72f7605bf600394
 */
module sample {
    requires org.apache.groovy;
    exports sample;
}
