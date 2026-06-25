package profile;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.StackProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import jdk.jfr.Recording;

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

        // -Pscenario=TOWER pins the @Param so a profiler attributes ONE scenario (otherwise a higher-rate
        // scenario like OPEN dominates the in-process allocation aggregate).
        String scenario = System.getProperty("scenario");
        if (scenario != null && !scenario.isBlank()) opt.param("scenario", scenario.split(","));

        // -Pprof=gc,stack → attach JMH's built-in profilers. GCProfiler reports the
        // allocation rate (the direct read on whether StepEdits churn is gone); the
        // StackProfiler samples the running thread and ranks methods by share of
        // runtime — exactly "which methods eat the ns/node". forks(0) is fine for both.
        boolean jfrAlloc = false;
        boolean jfrCpu = false;
        for (String p : System.getProperty("prof", "").split(",")) {
            switch (p.trim()) {
                case "gc" -> opt.addProfiler(GCProfiler.class);
                case "stack" -> opt.addProfiler(StackProfiler.class, "lines=8;top=25;period=1");
                case "jfr" -> jfrAlloc = true;
                case "cpu" -> jfrCpu = true;
                case "" -> { /* none requested */ }
                default -> System.out.println("[bench] unknown profiler: " + p);
            }
        }

        // forks(0) (the bootstrapped-MC requirement) rules out JMH's external JFR/async profilers, which
        // inject JVM flags at fork time. So record allocation IN-PROCESS with the jdk.jfr API:
        // ObjectAllocationSample is a low-overhead sampling event that attributes heap allocation by object
        // TYPE and stack — exactly what pins down the remaining bytes/op a GCProfiler only totals. Dump to
        // build/alloc.jfr; read with `jfr print --events jdk.ObjectAllocationSample build/alloc.jfr`.
        Recording rec = null;
        if (jfrAlloc) {
            rec = new Recording();
            rec.enable("jdk.ObjectAllocationSample").withStackTrace();
            rec.start();
        }

        // -Pprof=cpu → in-process CPU execution sampling (the on-CPU counterpart to the alloc recording
        // above). JMH's StackProfiler samples EVERY thread, so a forks(0) run drowns the benchmark thread in
        // idle Gradle-daemon/socket frames (~⅞ of samples). jdk.ExecutionSample carries the owning thread and
        // a deep stack, so the dump can be filtered to the benchmark thread and folded into a real self-time
        // histogram / icicle. Dump to build/cpu.jfr; read with
        // `jfr print --events jdk.ExecutionSample build/cpu.jfr`. 1 ms is the standard JFR method-sample rate.
        Recording cpu = null;
        if (jfrCpu) {
            cpu = new Recording();
            cpu.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
            cpu.start();
        }

        new Runner(opt.build()).run();

        if (rec != null) {
            rec.stop();
            Path out = Path.of("build/alloc.jfr");
            rec.dump(out);
            rec.close();
            System.out.println("[bench] JFR allocation recording -> " + out.toAbsolutePath());
        }
        if (cpu != null) {
            cpu.stop();
            Path out = Path.of("build/cpu.jfr");
            cpu.dump(out);
            cpu.close();
            System.out.println("[bench] JFR CPU recording -> " + out.toAbsolutePath());
        }
    }
}
