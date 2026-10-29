package br.edu.distribuidos.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Centraliza saídas de console curtas, legíveis e serializadas entre threads. */
public final class Console {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Console() {
    }

    public static synchronized void info(String component, String message) {
        System.out.printf("%s [%s] %s%n", LocalTime.now().format(TIME), component, message);
    }

    public static synchronized void error(String component, String message) {
        System.err.printf("%s [%s] ERRO: %s%n", LocalTime.now().format(TIME), component, message);
    }
}
