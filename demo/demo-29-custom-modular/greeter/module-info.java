/**
 * The library module of a multi-module modular build wired with
 * ModularProject.make. It declares no dependencies and exports one package for
 * the consumer module.
 *
 * @jenesis.release 25
 */
module demo.greeter {
    exports sample.greeter;
}
