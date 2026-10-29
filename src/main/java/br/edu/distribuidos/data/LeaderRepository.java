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

/** Maintains authoritative state under fair read-write mutual exclusion. */
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
        // Allows parallel reads without letting them bypass a pending write.
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

        // Keeps revalidation, persistence, and the remote ACK in one critical section.
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

            // Responds to the application only after remote acknowledgment.
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
            throw new IllegalArgumentException("requestId was reused with a different number");
        }
    }
}
