package br.edu.distribuidos.common;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Cria threads nomeadas e registra exceções que escapam de suas tarefas. */
public final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
        thread.setUncaughtExceptionHandler((ignored, error) ->
                Console.error(prefix, "Falha nao tratada: " + error.getMessage()));
        return thread;
    }
}
