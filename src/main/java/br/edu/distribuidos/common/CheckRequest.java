package br.edu.distribuidos.common;

/** Represents a request that crosses the client, load balancer, and application. */
public record CheckRequest(String requestId, String clientId, int number) {
    public CheckRequest {
        if (requestId == null || requestId.isBlank() || requestId.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("Invalid request identifier");
        }
        if (clientId == null || clientId.isBlank() || clientId.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("Invalid client identifier");
        }
        if (number < 1 || number > 1_000) {
            throw new IllegalArgumentException("Number must be between 1 and 1000");
        }
    }

    public String encode() {
        return "CHECK\t" + requestId + "\t" + clientId + "\t" + number;
    }

    public static CheckRequest decode(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Connection closed without a request");
        }
        String[] fields = line.split("\\t", -1);
        if (fields.length != 4 || !"CHECK".equals(fields[0])) {
            throw new IllegalArgumentException("Invalid request");
        }
        return new CheckRequest(fields[1], fields[2], Integer.parseInt(fields[3]));
    }
}
