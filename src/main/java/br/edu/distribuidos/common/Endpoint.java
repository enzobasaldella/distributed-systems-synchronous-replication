package br.edu.distribuidos.common;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;

/** Represents a TCP endpoint in {@code host:port} format. */
public record Endpoint(String host, int port) {
    public Endpoint {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be empty");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
    }

    public static Endpoint parse(String value) {
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("Invalid endpoint: " + value + " (use host:port)");
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
