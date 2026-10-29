package br.edu.distribuidos.data;

/** Descreve se o número foi inserido e a linha em que ele está armazenado. */
record StoreResult(String requestId, int number, int line, boolean inserted) {
    StoreResult {
        if (line < 1) {
            throw new IllegalArgumentException("A linha deve ser positiva");
        }
    }

    String encode() {
        return "RESULT\t" + requestId + "\t" + (inserted ? "INSERTED" : "EXISTS")
                + "\t" + number + "\t" + line;
    }
}
