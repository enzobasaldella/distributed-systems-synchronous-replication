package br.edu.distribuidos.client;

import br.edu.distribuidos.common.CheckRequest;
import br.edu.distribuidos.common.Console;
import br.edu.distribuidos.common.Endpoint;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Gera números, persiste o log local e os envia até receber a confirmação de aceite. */
public final class Client {
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int RESPONSE_TIMEOUT_MS = 5_000;
    private static final int RETRY_DELAY_MS = 500;

    private final String clientId;
    private final Endpoint loadBalancer;
    private final Path logFile;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);

    private Client(String clientId, Endpoint loadBalancer, Path logFile) {
        this.clientId = clientId;
        this.loadBalancer = loadBalancer;
        this.logFile = logFile;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 4) {
            System.err.println("Uso: Client <id> [host-balanceador porta-balanceador arquivo-log]");
            System.err.println("Exemplo: Client cliente-1 localhost 5000 data/cliente-1.log");
            System.exit(2);
        }

        String id = args[0];
        String host = args.length == 4 ? args[1] : "localhost";
        int port = args.length == 4 ? Integer.parseInt(args[2]) : 5_000;
        Path log = args.length == 4 ? Path.of(args[3]) : Path.of("data", id + ".log");
        new Client(id, new Endpoint(host, port), log).run();
    }

    private void run() throws IOException, InterruptedException {
        Path parent = logFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            try {
                // Permite que um número já persistido no log termine de ser aceito.
                stopped.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, clientId + "-encerramento"));

        Console.info(clientId, "Iniciado; balanceador=" + loadBalancer + ", log=" + logFile);
        long sequence = 0;
        try (FileChannel log = FileChannel.open(logFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int number = ThreadLocalRandom.current().nextInt(1, 1_001);
                String requestId = clientId + "-" + (++sequence) + "-" + UUID.randomUUID();
                CheckRequest request = new CheckRequest(requestId, clientId, number);

                appendAndForce(log, number);
                sendUntilAccepted(request);
                if (running.get()) {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(50, 201));
                }
            }
        } finally {
            stopped.countDown();
        }
    }

    private void sendUntilAccepted(CheckRequest request) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try (Socket socket = loadBalancer.connect(CONNECT_TIMEOUT_MS);
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader input = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
                output.write(request.encode());
                output.newLine();
                output.flush();

                String response = input.readLine();
                if (response != null && response.startsWith("ACCEPTED\t" + request.requestId())) {
                    Console.info(clientId, "Numero " + request.number() + " aceito pelo balanceador");
                    return;
                }
                Console.error(clientId, "Balanceador pediu nova tentativa para " + request.number());
            } catch (IOException error) {
                Console.error(clientId, "Balanceador indisponivel; a requisicao sera reenviada");
            }
            Thread.sleep(RETRY_DELAY_MS);
        }
    }

    private static void appendAndForce(FileChannel channel, int number) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(number + System.lineSeparator());
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
        channel.force(false);
    }
}
