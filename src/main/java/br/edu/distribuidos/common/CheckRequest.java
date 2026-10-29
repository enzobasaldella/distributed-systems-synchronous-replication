package br.edu.distribuidos.common;

/** Representa a requisição que atravessa cliente, balanceador e aplicação. */
public record CheckRequest(String requestId, String clientId, int number) {
    public CheckRequest {
        if (requestId == null || requestId.isBlank() || requestId.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("Identificador de requisicao invalido");
        }
        if (clientId == null || clientId.isBlank() || clientId.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("Identificador de cliente invalido");
        }
        if (number < 1 || number > 1_000) {
            throw new IllegalArgumentException("O numero deve estar entre 1 e 1000");
        }
    }

    public String encode() {
        return "CHECK\t" + requestId + "\t" + clientId + "\t" + number;
    }

    public static CheckRequest decode(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Conexao encerrada sem requisicao");
        }
        String[] fields = line.split("\\t", -1);
        if (fields.length != 4 || !"CHECK".equals(fields[0])) {
            throw new IllegalArgumentException("Requisicao invalida");
        }
        return new CheckRequest(fields[1], fields[2], Integer.parseInt(fields[3]));
    }
}
