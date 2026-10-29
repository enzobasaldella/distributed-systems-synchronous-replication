package br.edu.distribuidos.common;

/** Fornece o teste de primalidade usado pelas workers de aplicação. */
public final class PrimeChecker {
    private PrimeChecker() {
    }

    /** Testa divisores ímpares até a raiz quadrada, o suficiente para o domínio 1..1000. */
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
