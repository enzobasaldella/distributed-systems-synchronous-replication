package br.edu.distribuidos.data;

import br.edu.distribuidos.common.Console;
import br.edu.distribuidos.common.Endpoint;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/** Replicates every write and waits for the follower ACK before lock release. */
final class SynchronousReplicator {
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int RESPONSE_TIMEOUT_MS = 5_000;
    private static final int RETRY_DELAY_MS = 500;

    private final String serverId;
    private final Endpoint follower;
    private final Supplier<List<Integer>> snapshotSupplier;

    SynchronousReplicator(String serverId, Endpoint follower,
                          Supplier<List<Integer>> snapshotSupplier) {
        this.serverId = serverId;
        this.follower = follower;
        this.snapshotSupplier = snapshotSupplier;
    }

    void synchronizeUntilSuccessful() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                synchronizeOnce();
                return;
            } catch (IOException | IllegalArgumentException error) {
                Console.error(serverId, "Follower " + follower
                        + " unavailable for synchronization; retrying");
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        throw new InterruptedException("Synchronization interrupted");
    }

    void replicateUntilSuccessful(String requestId, int number, int line)
            throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String response = replicateOnce(requestId, number, line);
                if (response.equals("REPLICATED\t" + requestId + "\t" + line)) {
                    return;
                }
                if (response.equals("RESYNC_REQUIRED")) {
                    Console.error(serverId, "Replica diverged; sending a complete snapshot");
                    synchronizeOnce();
                    return;
                }
                throw new IOException("Invalid replication response");
            } catch (IOException | IllegalArgumentException error) {
                // The caller retains the write lock until the follower returns.
                Console.error(serverId, "Replication pending for line " + line
                        + "; retaining mutual exclusion and retrying");
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        throw new InterruptedException("Replication interrupted");
    }

    private String replicateOnce(String requestId, int number, int line) throws IOException {
        try (Socket socket = follower.connect(CONNECT_TIMEOUT_MS);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            output.write("REPLICATE\t" + requestId + "\t" + number + "\t" + line);
            output.newLine();
            output.flush();
            String response = input.readLine();
            if (response == null) {
                throw new IOException("Follower closed without an ACK");
            }
            return response;
        }
    }

    private void synchronizeOnce() throws IOException {
        List<Integer> snapshot = snapshotSupplier.get();
        try (Socket socket = follower.connect(CONNECT_TIMEOUT_MS);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            output.write("SYNC\t" + snapshot.size());
            output.newLine();
            for (int number : snapshot) {
                output.write("VALUE\t" + number);
                output.newLine();
            }
            output.write("END");
            output.newLine();
            output.flush();

            String expected = "SYNCED\t" + snapshot.size();
            String response = input.readLine();
            if (!expected.equals(response)) {
                throw new IOException("Invalid synchronization ACK: " + response);
            }
            Console.info(serverId, "Replica synchronized with " + snapshot.size() + " lines");
        }
    }
}
