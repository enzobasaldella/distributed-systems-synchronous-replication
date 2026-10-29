package br.edu.distribuidos.data;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Keeps the passive copy updated exclusively through the private replication channel. */
final class FollowerReplica {
    private final Path file;
    private final ReentrantLock mutationLock = new ReentrantLock(true);
    private List<Integer> numbers;
    private Map<Integer, Integer> lineByNumber;

    FollowerReplica(Path file) throws IOException {
        this.file = file;
        setInMemory(NumberFile.load(file));
    }

    boolean replicate(int number, int expectedLine) throws IOException {
        mutationLock.lock();
        try {
            Integer existing = lineByNumber.get(number);
            if (existing != null) {
                return existing == expectedLine;
            }
            if (expectedLine != numbers.size() + 1) {
                return false;
            }
            NumberFile.append(file, number);
            numbers.add(number);
            lineByNumber.put(number, expectedLine);
            return true;
        } finally {
            mutationLock.unlock();
        }
    }

    void replace(List<Integer> replacement) throws IOException {
        mutationLock.lock();
        try {
            NumberFile.replace(file, replacement);
            setInMemory(replacement);
        } finally {
            mutationLock.unlock();
        }
    }

    int size() {
        mutationLock.lock();
        try {
            return numbers.size();
        } finally {
            mutationLock.unlock();
        }
    }

    private void setInMemory(List<Integer> values) {
        List<Integer> copy = new ArrayList<>(values);
        Map<Integer, Integer> lines = new HashMap<>();
        for (int index = 0; index < copy.size(); index++) {
            Integer previous = lines.put(copy.get(index), index + 1);
            if (previous != null) {
                throw new IllegalArgumentException("Snapshot contains duplicate number: " + copy.get(index));
            }
        }
        this.numbers = copy;
        this.lineByNumber = lines;
    }
}
