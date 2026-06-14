pitest - mutation testing, discovered from a config file
========================================================

Coverage tells you which lines a test *executed*; mutation testing tells you
which behaviours a test actually *checks*. This demo runs [PIT](https://pitest.org)
(`pitest`): it seeds small faults into the code under test - a `+` becomes a `-`,
a return value is replaced with a constant - re-runs the tests against each
mutant, and reports which mutants the tests killed and which survived. A
surviving mutant is a change to the program that no test noticed.

Like the lint tools, PIT is **discovered from a config file**: when a module has
a `pitest.properties`, the assembler wires a `mutate` step into the build. There
is no flag and no plugin to register; the plain build runs it:

    java build/jenesis/Project.java

The build resolves PIT and its JUnit 5 plugin into their own `pitest` dependency
group (separate from the project's dependencies), runs the analysis against the
compiled classes and the test classpath, and writes an HTML and XML report under
the `mutate` step's `reports/pitest/`. The version of PIT's JUnit 5 plugin is
taken from the project's own resolved `junit-platform`, so it always lines up
with the test framework the project uses rather than floating independently.

The module
----------

`Calculator.add` is covered by `CalculatorTest`. Running the demo seeds two
mutants into `add` (replace the addition with subtraction; replace the return
value with `0`); the test computes `2 + 3` and asserts `5`, so it kills both, and
the report records `status='KILLED'`. Weaken the assertion (for example assert
nothing) and a mutant survives.

The configuration
-----------------

`pitest.properties` carries the options PIT needs, so the file is real
configuration rather than an empty marker:

    targetClasses=calc.Calculator   # which classes to mutate
    targetTests=calc.*              # which tests to run against the mutants
    outputFormats=XML,HTML          # report formats (an optional `mutators` key selects a mutator set)

For a single module the production code and its tests are compiled together, so
PIT sees both on one classpath; the production class is what gets mutated and the
test is what must catch it.
