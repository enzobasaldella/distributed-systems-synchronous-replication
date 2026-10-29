package br.edu.distribuidos.data;

import br.edu.distribuidos.common.Console;
import br.edu.distribuidos.common.Endpoint;
import br.edu.distribuidos.common.NamedThreadFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a data server as either the leader or follower in synchronous replication.
 *
 * <p>The follower has two ports: the public port forwards {@code STORE} to the leader,
 * while the private port receives {@code REPLICATE} and {@code SYNC}. This separation
 * prevents deadlock during replication.</p>
 */
public final class DataServer {
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    private enum Role { LEADER, FOLLOWER }

    private final String serverId;
    private final Role role;
    private final int publicPort;
    private final int replicationPort;
    private final Path dataFile;
    private final Endpoint peer;
    private final ExecutorService publicWorkers;
    private final ExecutorService replicationWorkers;

    private LeaderRepository leaderRepository;
    private SynchronousReplicator replicator;
    private FollowerReplica followerReplica;

    private DataServer(String serverId, Role role, int publicPort, int replicationPort,
                       Path dataFile, Endpoint peer) {
        this.serverId = serverId;
        this.role = role;
        this.publicPort = publicPort;
        this.replicationPort = replicationPort;
        this.dataFile = dataFile;
        this.peer = peer;
        this.publicWorkers = Executors.newFixedThreadPool(64,
                new NamedThreadFactory(serverId + "-dados"));
        this.replicationWorkers = Executors.newFixedThreadPool(4,
                new NamedThreadFactory(serverId + "-replica"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("Usage: DataServer <id> <leader|follower> <public-port> "
                    + "<replication-port-or-0> <file> <peer-host:peer-port>");
            System.err.println("Leader:   DataServer dados-1 leader 7001 0 file1 localhost:7102");
            System.err.println("Follower: DataServer dados-2 follower 7002 7102 file2 localhost:7001");
            System.exit(2);
        }
        Role role = switch (args[1].toLowerCase()) {
            case "leader" -> Role.LEADER;
            case "follower" -> Role.FOLLOWER;
            default -> throw new IllegalArgumentException("Role must be leader or follower");
        };
        int replicationPort = Integer.parseInt(args[3]);
        if (role == Role.FOLLOWER && replicationPort < 1) {
            throw new IllegalArgumentException("Follower requires a replication port");
        }
        DataServer server = new DataServer(args[0], role, Integer.parseInt(args[2]),
                replicationPort, Path.of(args[4]), Endpoint.parse(args[5]));
        server.run();
    }

    private void run() throws Exception {
        if (role == Role.LEADER) {
            runLeader();
        } else {
            runFollower();
        }
    }

    private void runLeader() throws IOException, InterruptedException {
        leaderRepository = new LeaderRepository(dataFile);
        replicator = new SynchronousReplicator(serverId, peer, leaderRepository::snapshot);
        Console.info(serverId, "Leader loaded " + leaderRepository.size()
                + " lines; waiting for initial synchronization with " + peer);
        replicator.synchronizeUntilSuccessful();
        try (ServerSocket publicServer = bind(publicPort)) {
            Console.info(serverId, "Leader listening on localhost:" + publicPort
                    + "; file=" + dataFile);
            acceptPublicConnections(publicServer);
        } finally {
            shutdownWorkers();
        }
    }

    private void runFollower() throws IOException {
        followerReplica = new FollowerReplica(dataFile);
        try (ServerSocket replicationServer = bind(replicationPort);
             ServerSocket publicServer = bind(publicPort)) {
            Thread replicationAcceptor = new Thread(
                    () -> acceptReplicationConnections(replicationServer),
                    serverId + "-aceite-replica");
            replicationAcceptor.setDaemon(true);
            replicationAcceptor.start();
            Console.info(serverId, "Follower listening for clients on localhost:" + publicPort
                    + " and replication on localhost:" + replicationPort + "; file=" + dataFile);
            acceptPublicConnections(publicServer);
        } finally {
            shutdownWorkers();
        }
    }

    private void acceptPublicConnections(ServerSocket server) throws IOException {
        while (true) {
            Socket connection = server.accept();
            connection.setTcpNoDelay(true);
            publicWorkers.execute(() -> handlePublicConnection(connection));
        }
    }

    private void handlePublicConnection(Socket connection) {
        try (connection;
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     connection.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     connection.getOutputStream(), StandardCharsets.UTF_8))) {
            StoreRequest request = StoreRequest.decode(input.readLine());
            String response;
            if (role == Role.LEADER) {
                StoreResult result = leaderRepository.store(request, replicator);
                response = result.encode();
                if (result.inserted()) {
                    Console.info(serverId, "Number " + result.number() + " stored and replicated at line "
                            + result.line());
                }
            } else {
                response = forwardToLeader(request);
            }
            output.write(response);
            output.newLine();
            output.flush();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            Console.error(serverId, "Failed to serve request: " + error.getMessage());
            sendErrorIfPossible(connection, error.getMessage());
        }
    }

    private String forwardToLeader(StoreRequest request) throws IOException {
        try (Socket socket = peer.connect(CONNECT_TIMEOUT_MS);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            output.write(request.encode());
            output.newLine();
            output.flush();
            String response = input.readLine();
            if (response == null) {
                throw new IOException("Leader closed without a response");
            }
            return response;
        }
    }

    private void acceptReplicationConnections(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket connection = server.accept();
                connection.setTcpNoDelay(true);
                replicationWorkers.execute(() -> handleReplicationConnection(connection));
            } catch (IOException error) {
                if (!server.isClosed()) {
                    Console.error(serverId, "Failed to accept replication: " + error.getMessage());
                }
            }
        }
    }

    private void handleReplicationConnection(Socket connection) {
        try (connection;
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     connection.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     connection.getOutputStream(), StandardCharsets.UTF_8))) {
            String firstLine = input.readLine();
            if (firstLine == null) {
                throw new IllegalArgumentException("Empty replication command");
            }
            String[] fields = firstLine.split("\\t", -1);
            String response;
            if (fields.length == 4 && "REPLICATE".equals(fields[0])) {
                String requestId = fields[1];
                int number = parseNumber(fields[2]);
                int line = Integer.parseInt(fields[3]);
                boolean applied = followerReplica.replicate(number, line);
                response = applied ? "REPLICATED\t" + requestId + "\t" + line
                        : "RESYNC_REQUIRED";
            } else if (fields.length == 2 && "SYNC".equals(fields[0])) {
                int count = Integer.parseInt(fields[1]);
                if (count < 0 || count > 1_000) {
                    throw new IllegalArgumentException("Invalid snapshot size");
                }
                List<Integer> snapshot = readSnapshot(input, count);
                followerReplica.replace(snapshot);
                response = "SYNCED\t" + count;
                Console.info(serverId, "Snapshot applied with " + count + " lines");
            } else {
                throw new IllegalArgumentException("Invalid replication command");
            }
            output.write(response);
            output.newLine();
            output.flush();
        } catch (Exception error) {
            Console.error(serverId, "Replication failure: " + error.getMessage());
        }
    }

    private static List<Integer> readSnapshot(BufferedReader input, int count) throws IOException {
        List<Integer> values = new ArrayList<>(count);
        Set<Integer> unique = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String line = input.readLine();
            String[] fields = line == null ? new String[0] : line.split("\\t", -1);
            if (fields.length != 2 || !"VALUE".equals(fields[0])) {
                throw new IllegalArgumentException("Invalid snapshot line");
            }
            int number = parseNumber(fields[1]);
            if (!unique.add(number)) {
                throw new IllegalArgumentException("Snapshot contains a duplicate number");
            }
            values.add(number);
        }
        if (!"END".equals(input.readLine())) {
            throw new IllegalArgumentException("Invalid snapshot terminator");
        }
        return values;
    }

    private static int parseNumber(String value) {
        int number = Integer.parseInt(value);
        if (number < 1 || number > 1_000) {
            throw new IllegalArgumentException("Number outside the 1..1000 range");
        }
        return number;
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("localhost", port));
        return server;
    }

    private static void sendErrorIfPossible(Socket connection, String message) {
        if (connection.isClosed()) {
            return;
        }
        try {
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    connection.getOutputStream(), StandardCharsets.UTF_8));
            output.write("ERROR\t" + String.valueOf(message).replace('\t', ' '));
            output.newLine();
            output.flush();
        } catch (IOException ignored) {
            // Lets the caller retry if the connection has already closed.
        }
    }

    private void shutdownWorkers() {
        publicWorkers.shutdownNow();
        replicationWorkers.shutdownNow();
    }
}
