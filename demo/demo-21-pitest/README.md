pitest - mutation testing, discovered from a config file
========================================================

Coverage tells you which lines a test *executed*; mutation testing tells you
which behaviours a test actually *checks*. This demo runs [PIT](https://pitest.org)
(`pitest`): it seeds small faults into the code under test - a `+` becomes a `-`,
a return value is replaced with a constant - re-runs the tests against each
mutant, and reports which mutants the tests killed and which survived. A
surviving mutant is a change to the program that no test noticed.

Like the lint tools, PIT is **discovered from a config file**: when a tested
module also has a `pitest.properties`, a `mutate` step is wired into the test
block alongside the normal test run, so the suite runs as usual *and* PIT then
assesses how good it is. There is no flag and no plugin to register; the plain
build runs it:

    java build/jenesis/Project.java

The build resolves PIT and its JUnit 5 plugin into their own `pitest` dependency
group (separate from the project's dependencies), runs the analysis against the
compiled classes and the test classpath, and writes an HTML and XML report under
the `mutate` step's `reports/pitest/`. The version of PIT's JUnit 5 plugin is
taken from the project's own resolved `junit-platform`, so it always lines up
with the test framework the project uses rather than floating independently.

The modules
-----------

The production module `demo.mutation` exports `calc.Calculator`; a separate test
module (`@jenesis.test demo.mutation`) holds `CalculatorTest`. The normal test
step runs the suite, and PIT then re-runs it against mutants of the code under
test. The demo seeds two mutants into `Calculator.add` (replace the addition with
subtraction; replace the return value with `0`); the test computes `2 + 3` and
asserts `5`, so it kills both, and the report records `status='KILLED'`. Weaken
the assertion (for example assert nothing) and a mutant survives.

The configuration
-----------------

`pitest.properties` carries the options PIT needs, so the file is real
configuration rather than an empty marker:

    targetClasses=calc.Calculator   # which classes to mutate
    targetTests=calctest.*          # which tests to run against the mutants
    outputFormats=XML,HTML          # report formats (an optional `mutators` key selects a mutator set)

PIT runs in the test module's context, where the production module is on the
classpath as a dependency and the test classes are local: `targetClasses` selects
the production class to mutate and `targetTests` the tests that must catch it.
