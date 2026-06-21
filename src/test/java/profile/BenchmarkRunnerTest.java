package profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
 * Optional filter: `./gradlew jmh -Pbench=reflectForEach`.
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

        new Runner(opt.build()).run();
    }
}
