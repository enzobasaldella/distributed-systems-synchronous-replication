package br.edu.distribuidos.loadbalancer;

import br.edu.distribuidos.common.CheckRequest;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Distributes requests using Round Robin with circular application-server failover. */
public final class LoadBalancer {
    private static final int CONNECT_TIMEOUT_MS = 1_000;
    private static final int RESPONSE_TIMEOUT_MS = 3_000;

    private final int listenPort;
    private final List<Endpoint> applicationServers;
    private final AtomicLong nextServer = new AtomicLong();
    private final ExecutorService workers = Executors.newFixedThreadPool(32,
            new NamedThreadFactory("balanceador-worker"));

    private LoadBalancer(int listenPort, List<Endpoint> applicationServers) {
        if (applicationServers.isEmpty()) {
            throw new IllegalArgumentException("At least one application server is required");
        }
        this.listenPort = listenPort;
        this.applicationServers = List.copyOf(applicationServers);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 0 && args.length != 2) {
            System.err.println("Usage: LoadBalancer [port host1:port1,host2:port2,host3:port3]");
            System.exit(2);
        }
        int port = args.length == 2 ? Integer.parseInt(args[0]) : 5_000;
        String endpoints = args.length == 2 ? args[1]
                : "localhost:6001,localhost:6002,localhost:6003";
        List<Endpoint> servers = Arrays.stream(endpoints.split(","))
                .map(Endpoint::parse)
                .toList();
        new LoadBalancer(port, servers).run();
    }

    private void run() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("localhost", listenPort));
            Console.info("load-balancer", "Listening on localhost:" + listenPort
                    + " with Round Robin across " + applicationServers);
            while (true) {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                workers.execute(() -> handleClient(client));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private void handleClient(Socket client) {
        try (client;
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     client.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     client.getOutputStream(), StandardCharsets.UTF_8))) {
            CheckRequest request = CheckRequest.decode(input.readLine());
            Endpoint selected = forwardWithFailover(request);
            if (selected == null) {
                output.write("RETRY\t" + request.requestId());
                Console.error("load-balancer", "No server available for " + request.requestId());
            } else {
                output.write("ACCEPTED\t" + request.requestId());
                Console.info("load-balancer", "Number " + request.number() + " -> " + selected);
            }
            output.newLine();
            output.flush();
        } catch (IllegalArgumentException error) {
            Console.error("load-balancer", error.getMessage());
        } catch (IOException error) {
            Console.error("load-balancer", "Client connection closed: " + error.getMessage());
        }
    }

    private Endpoint forwardWithFailover(CheckRequest request) {
        int first = Math.floorMod(nextServer.getAndIncrement(), applicationServers.size());
        for (int offset = 0; offset < applicationServers.size(); offset++) {
            Endpoint endpoint = applicationServers.get((first + offset) % applicationServers.size());
            if (forward(request, endpoint)) {
                return endpoint;
            }
        }
        return null;
    }

    private boolean forward(CheckRequest request, Endpoint endpoint) {
        try (Socket socket = endpoint.connect(CONNECT_TIMEOUT_MS);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            output.write(request.encode());
            output.newLine();
            output.flush();
            String response = input.readLine();
            return response != null && response.startsWith("ACCEPTED\t" + request.requestId());
        } catch (IOException error) {
            Console.error("load-balancer", endpoint + " unavailable; trying the next server");
            return false;
        }
    }
}
