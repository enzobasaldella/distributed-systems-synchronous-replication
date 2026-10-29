package br.edu.distribuidos;

import br.edu.distribuidos.common.CheckRequest;
import br.edu.distribuidos.common.Endpoint;
import br.edu.distribuidos.common.PrimeChecker;

/** Runs lightweight tests without dependencies outside the JDK. */
public final class TestRunner {
    private TestRunner() {
    }

    public static void main(String[] args) {
        testPrimeChecker();
        testProtocolRoundTrip();
        testEndpoint();
        System.out.println("All unit tests passed.");
    }

    private static void testPrimeChecker() {
        for (int number = 1; number <= 1_000; number++) {
            boolean expected = naivePrime(number);
            require(PrimeChecker.isPrime(number) == expected,
                    "Incorrect primality result for " + number);
        }
    }

    private static boolean naivePrime(int number) {
        if (number < 2) {
            return false;
        }
        for (int divisor = 2; divisor < number; divisor++) {
            if (number % divisor == 0) {
                return false;
            }
        }
        return true;
    }

    private static void testProtocolRoundTrip() {
        CheckRequest original = new CheckRequest("requisicao-1", "cliente-1", 997);
        CheckRequest decoded = CheckRequest.decode(original.encode());
        require(original.equals(decoded), "CHECK encoding did not preserve all fields");
    }

    private static void testEndpoint() {
        Endpoint endpoint = Endpoint.parse("localhost:7001");
        require(endpoint.host().equals("localhost") && endpoint.port() == 7001,
                "Incorrect endpoint parsing");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
