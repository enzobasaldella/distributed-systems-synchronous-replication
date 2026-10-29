package br.edu.distribuidos.common;

/** Provides the primality test used by application workers. */
public final class PrimeChecker {
    private PrimeChecker() {
    }

    /** Tests odd divisors up to the square root, sufficient for the 1..1000 domain. */
    public static boolean isPrime(int number) {
        if (number < 2) {
            return false;
        }
        if (number == 2) {
            return true;
        }
        if (number % 2 == 0) {
            return false;
        }
        for (int divisor = 3; divisor <= number / divisor; divisor += 2) {
            if (number % divisor == 0) {
                return false;
            }
        }
        return true;
    }
}
