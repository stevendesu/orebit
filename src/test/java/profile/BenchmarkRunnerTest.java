package profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.StackProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Drives JMH from inside the fabric-loader-junit Knot classloader so benchmarks
 * can touch bootstrapped Minecraft. Because the Knot setup only exists in this
 * JVM, JMH must run with forks(0) (embedded). Gated by -Djmh=true so a normal
 * `./gradlew test` does not run the slow benchmarks; invoke via `./gradlew jmh`.
 *
 * Optional filter:    `./gradlew jmh -Pbench=PathfinderBenchmark`.
 * Optional profilers: `./gradlew jmh -Pprof=gc,stack` (allocation rate + sampled
 * method hot-spots — both are JMH built-ins, so no native agent / OS perf needed).
 */
@EnabledIfSystemProperty(named = "jmh", matches = "true")
public class BenchmarkRunnerTest {

    @Test
    void runBenchmarks() throws Exception {
        String include = System.getProperty("bench", "BlockReadBenchmark");

        ChainedOptionsBuilder opt = new OptionsBuilder()
                .include(include)
                .forks(0) // must stay in this JVM (Knot classloader + bootstrapped MC)
                .resultFormat(ResultFormatType.TEXT)
                .result("build/jmh-results.txt");

        // -Pprof=gc,stack → attach JMH's built-in profilers. GCProfiler reports the
        // allocation rate (the direct read on whether StepEdits churn is gone); the
        // StackProfiler samples the running thread and ranks methods by share of
        // runtime — exactly "which methods eat the ns/node". forks(0) is fine for both.
        for (String p : System.getProperty("prof", "").split(",")) {
            switch (p.trim()) {
                case "gc" -> opt.addProfiler(GCProfiler.class);
                case "stack" -> opt.addProfiler(StackProfiler.class, "lines=8;top=25;period=1");
                case "" -> { /* none requested */ }
                default -> System.out.println("[bench] unknown profiler: " + p);
            }
        }

        new Runner(opt.build()).run();
    }
}
