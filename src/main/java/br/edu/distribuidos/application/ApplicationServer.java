package br.edu.distribuidos.application;

import br.edu.distribuidos.common.CheckRequest;
import br.edu.distribuidos.common.Console;
import br.edu.distribuidos.common.Endpoint;
import br.edu.distribuidos.common.NamedThreadFactory;
import br.edu.distribuidos.common.PrimeChecker;

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
import java.util.concurrent.ThreadLocalRandom;

/** Recebe requisições do balanceador e delega cada uma a uma worker independente. */
public final class ApplicationServer {
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int RETRY_DELAY_MS = 300;

    private final String serverId;
    private final int listenPort;
    private final List<Endpoint> dataServers;
    private final ExecutorService intakeWorkers;
    private final ExecutorService requestWorkers;

    private ApplicationServer(String serverId, int listenPort, List<Endpoint> dataServers) {
        if (dataServers.size() != 2) {
            throw new IllegalArgumentException("Devem ser informados exatamente 2 servidores de dados");
        }
        this.serverId = serverId;
        this.listenPort = listenPort;
        this.dataServers = List.copyOf(dataServers);
        this.intakeWorkers = Executors.newFixedThreadPool(16,
                new NamedThreadFactory(serverId + "-entrada"));
        this.requestWorkers = Executors.newFixedThreadPool(16,
                new NamedThreadFactory(serverId + "-worker"));
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 3) {
            System.err.println("Uso: ApplicationServer <id> <porta> [dados1:porta,dados2:porta]");
            System.exit(2);
        }
        String endpoints = args.length == 3 ? args[2] : "localhost:7001,localhost:7002";
        List<Endpoint> dataServers = Arrays.stream(endpoints.split(","))
                .map(Endpoint::parse)
                .toList();
        new ApplicationServer(args[0], Integer.parseInt(args[1]), dataServers).run();
    }

    private void run() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("localhost", listenPort));
            Console.info(serverId, "Ouvindo localhost:" + listenPort + "; dados=" + dataServers);
            while (true) {
                Socket connection = server.accept();
                connection.setTcpNoDelay(true);
                intakeWorkers.execute(() -> acceptRequest(connection));
            }
        } finally {
            intakeWorkers.shutdownNow();
            requestWorkers.shutdownNow();
        }
    }

    private void acceptRequest(Socket connection) {
        try (connection;
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     connection.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     connection.getOutputStream(), StandardCharsets.UTF_8))) {
            CheckRequest request = CheckRequest.decode(input.readLine());
            requestWorkers.execute(() -> processRequest(request));
            output.write("ACCEPTED\t" + request.requestId());
            output.newLine();
            output.flush();
        } catch (IllegalArgumentException error) {
            Console.error(serverId, error.getMessage());
        } catch (IOException error) {
            Console.error(serverId, "Falha ao receber requisicao: " + error.getMessage());
        }
    }

    private void processRequest(CheckRequest request) {
        // Testa a primalidade antes de consultar os dados, preservando a ordem exigida.
        boolean prime = PrimeChecker.isPrime(request.number());
        DataResult result = storeUntilSuccessful(request);
        String primality = prime ? "é primo" : "não é primo";
        if (result.inserted()) {
            Console.info(serverId, "O número " + request.number() + " " + primality
                    + ", não está presente no arquivo, e foi inserido na linha " + result.line());
        } else {
            Console.info(serverId, "O número " + request.number() + " " + primality
                    + ", e já se encontra no arquivo na linha " + result.line());
        }
    }

    private DataResult storeUntilSuccessful(CheckRequest request) {
        int first = ThreadLocalRandom.current().nextInt(dataServers.size());
        int attempt = 0;
        while (!Thread.currentThread().isInterrupted()) {
            Endpoint endpoint = dataServers.get((first + attempt) % dataServers.size());
            attempt++;
            try {
                DataResult result = store(request, endpoint);
                Console.info(serverId, "Requisicao " + request.requestId() + " atendida por " + endpoint);
                return result;
            } catch (IOException | IllegalArgumentException error) {
                Console.error(serverId, endpoint + " indisponivel; requisicao mantida para nova tentativa");
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("Worker interrompida antes de armazenar a requisicao");
    }

    private DataResult store(CheckRequest request, Endpoint endpoint) throws IOException {
        try (Socket socket = endpoint.connect(CONNECT_TIMEOUT_MS);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            // Mantém a worker aguardando enquanto a replicação síncrona estiver pendente.
            output.write("STORE\t" + request.requestId() + "\t" + request.number());
            output.newLine();
            output.flush();
            return DataResult.decode(input.readLine(), request.requestId(), request.number());
        }
    }

    private record DataResult(boolean inserted, int line) {
        private static DataResult decode(String response, String requestId, int number) {
            if (response == null) {
                throw new IllegalArgumentException("Servidor de dados encerrou sem resposta");
            }
            String[] fields = response.split("\\t", -1);
            if (fields.length != 5 || !"RESULT".equals(fields[0])
                    || !requestId.equals(fields[1]) || Integer.parseInt(fields[3]) != number) {
                throw new IllegalArgumentException("Resposta invalida do servidor de dados");
            }
            boolean inserted = switch (fields[2]) {
                case "INSERTED" -> true;
                case "EXISTS" -> false;
                default -> throw new IllegalArgumentException("Status de dados invalido");
            };
            int line = Integer.parseInt(fields[4]);
            if (line < 1) {
                throw new IllegalArgumentException("Linha invalida na resposta");
            }
            return new DataResult(inserted, line);
        }
    }
}
