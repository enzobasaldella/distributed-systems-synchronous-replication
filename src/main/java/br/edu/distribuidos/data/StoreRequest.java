package br.edu.distribuidos.data;

/** Representa uma consulta ou tentativa de inserção enviada ao subsistema de dados. */
record StoreRequest(String requestId, int number) {
    StoreRequest {
        if (requestId == null || requestId.isBlank() || requestId.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("Identificador de requisicao invalido");
        }
        if (number < 1 || number > 1_000) {
            throw new IllegalArgumentException("O numero deve estar entre 1 e 1000");
        }
    }

    static StoreRequest decode(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Conexao encerrada sem requisicao");
        }
        String[] fields = line.split("\\t", -1);
        if (fields.length != 3 || !"STORE".equals(fields[0])) {
            throw new IllegalArgumentException("Requisicao de armazenamento invalida");
        }
        return new StoreRequest(fields[1], Integer.parseInt(fields[2]));
    }

    String encode() {
        return "STORE\t" + requestId + "\t" + number;
    }
}
