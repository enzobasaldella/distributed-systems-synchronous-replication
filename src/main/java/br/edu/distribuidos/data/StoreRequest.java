package br.edu.distribuidos.data;

/** Represents a lookup or insertion attempt sent to the data subsystem. */
record StoreRequest(String requestId, int number) {
    StoreRequest {
        if (requestId == null || requestId.isBlank() || requestId.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("Invalid request identifier");
        }
        if (number < 1 || number > 1_000) {
            throw new IllegalArgumentException("Number must be between 1 and 1000");
        }
    }

    static StoreRequest decode(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Connection closed without a request");
        }
        String[] fields = line.split("\\t", -1);
        if (fields.length != 3 || !"STORE".equals(fields[0])) {
            throw new IllegalArgumentException("Invalid store request");
        }
        return new StoreRequest(fields[1], Integer.parseInt(fields[2]));
    }

    String encode() {
        return "STORE\t" + requestId + "\t" + number;
    }
}
