package br.edu.distribuidos.data;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Mantém o estado autoritativo sob exclusão mútua justa de leitura e escrita. */
final class LeaderRepository {
    private static final int REQUEST_CACHE_SIZE = 10_000;

    private final Path file;
    private final List<Integer> numbers;
    private final Map<Integer, Integer> lineByNumber = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Map<String, StoreResult> recentRequests = Collections.synchronizedMap(
            new LinkedHashMap<>(REQUEST_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StoreResult> eldest) {
                    return size() > REQUEST_CACHE_SIZE;
                }
            });

    LeaderRepository(Path file) throws IOException {
        this.file = file;
        this.numbers = NumberFile.load(file);
        for (int index = 0; index < numbers.size(); index++) {
            lineByNumber.put(numbers.get(index), index + 1);
        }
    }

    StoreResult store(StoreRequest request, SynchronousReplicator replicator)
            throws IOException, InterruptedException {
        // Permite leituras paralelas sem deixar que ultrapassem uma escrita pendente.
        lock.readLock().lockInterruptibly();
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(100, 301));
            StoreResult repeated = recentRequests.get(request.requestId());
            if (repeated != null) {
                requireSameNumber(request, repeated);
                return repeated;
            }
            Integer existingLine = lineByNumber.get(request.number());
            if (existingLine != null) {
                StoreResult result = new StoreResult(request.requestId(), request.number(),
                        existingLine, false);
                recentRequests.put(request.requestId(), result);
                return result;
            }
        } finally {
            lock.readLock().unlock();
        }

        // Mantém a revalidação, a gravação e o ACK remoto na mesma região crítica.
        lock.writeLock().lockInterruptibly();
        try {
            StoreResult repeated = recentRequests.get(request.requestId());
            if (repeated != null) {
                requireSameNumber(request, repeated);
                return repeated;
            }
            Integer existingLine = lineByNumber.get(request.number());
            if (existingLine != null) {
                StoreResult result = new StoreResult(request.requestId(), request.number(),
                        existingLine, false);
                recentRequests.put(request.requestId(), result);
                return result;
            }

            int line = numbers.size() + 1;
            NumberFile.append(file, request.number());
            numbers.add(request.number());
            lineByNumber.put(request.number(), line);

            // Responde à aplicação somente depois da confirmação remota.
            replicator.replicateUntilSuccessful(request.requestId(), request.number(), line);
            StoreResult result = new StoreResult(request.requestId(), request.number(), line, true);
            recentRequests.put(request.requestId(), result);
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    List<Integer> snapshot() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(numbers);
        } finally {
            lock.readLock().unlock();
        }
    }

    int size() {
        lock.readLock().lock();
        try {
            return numbers.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void requireSameNumber(StoreRequest request, StoreResult result) {
        if (request.number() != result.number()) {
            throw new IllegalArgumentException("O requestId foi reutilizado com outro numero");
        }
    }
}
