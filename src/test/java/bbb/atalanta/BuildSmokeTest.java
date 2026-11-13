package bbb.atalanta;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the Java 17 compiler and JUnit/AssertJ test infrastructure.
 * Replace with behavior-focused tests when the first API is implemented.
 */
class BuildSmokeTest {

    private record SampleSymbol(String value) {
    }

    @Test
    void compilesJava17RecordsAndRunsTheTestFramework() {
        assertThat(new SampleSymbol("BTC/USD").value()).isEqualTo("BTC/USD");
    }
}
