package br.edu.distribuidos.common;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;

/** Representa um endpoint TCP no formato {@code host:porta}. */
public record Endpoint(String host, int port) {
    public Endpoint {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("O host nao pode ser vazio");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Porta invalida: " + port);
        }
    }

    public static Endpoint parse(String value) {
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("Endereco invalido: " + value + " (use host:porta)");
        }
        return new Endpoint(value.substring(0, separator),
                Integer.parseInt(value.substring(separator + 1)));
    }

    public Socket connect(int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setTcpNoDelay(true);
        return socket;
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
