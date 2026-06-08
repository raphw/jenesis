/**
 * Annotation processor demo.
 *
 * Runs the Immutables annotation processor over a modular Java source. The
 * processor is declared by module name with an @jenesis.plugin tag and is
 * resolved onto the processor path; it is never discovered implicitly from the
 * module path, even though the very same module is also a compile dependency.
 *
 * @jenesis.release 25
 * @jenesis.plugin org.immutables.value
 * @jenesis.pin org.immutables.value 2.12.2
 * @jenesis.pin org.immutables/value 2.12.2 SHA-256/fa9582d54d079bae233f3e580b5a1241417fcdd3e7049ece9f8ca85c8edd49e1
 */
module demo.annotations {
    requires static org.immutables.value;

    exports demo.annotations;
}
