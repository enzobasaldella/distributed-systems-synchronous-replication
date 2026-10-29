package br.edu.distribuidos.data;

/** Describes whether the number was inserted and the line where it is stored. */
record StoreResult(String requestId, int number, int line, boolean inserted) {
    StoreResult {
        if (line < 1) {
            throw new IllegalArgumentException("Line number must be positive");
        }
    }

    String encode() {
        return "RESULT\t" + requestId + "\t" + (inserted ? "INSERTED" : "EXISTS")
                + "\t" + number + "\t" + line;
    }
}
