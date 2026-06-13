/**
 * The consumer module. It requires the sibling demo.greeter module built within
 * this same project, so the build resolves an intra-project sibling with no
 * external dependencies.
 *
 * @jenesis.release 25
 */
module demo.app {
    requires demo.greeter;
}
