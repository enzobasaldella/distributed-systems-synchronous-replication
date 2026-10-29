package br.edu.distribuidos.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Executa operações duráveis sobre arquivos que armazenam um número por linha. */
final class NumberFile {
    private NumberFile() {
    }

    static List<Integer> load(Path path) throws IOException {
        ensureParent(path);
        if (Files.notExists(path)) {
            Files.createFile(path);
            return new ArrayList<>();
        }

        List<Integer> numbers = new ArrayList<>();
        Set<Integer> unique = new HashSet<>();
        int physicalLine = 0;
        for (String text : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            physicalLine++;
            if (text.isBlank()) {
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(text.trim());
            } catch (NumberFormatException error) {
                throw new IOException("Valor invalido em " + path + ", linha " + physicalLine, error);
            }
            if (number < 1 || number > 1_000) {
                throw new IOException("Numero fora do intervalo em " + path + ", linha " + physicalLine);
            }
            if (!unique.add(number)) {
                throw new IOException("Numero duplicado em " + path + ": " + number);
            }
            numbers.add(number);
        }
        return numbers;
    }

    static void append(Path path, int number) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(number + System.lineSeparator());
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    static void replace(Path path, List<Integer> numbers) throws IOException {
        ensureParent(path);
        Path temporary = path.resolveSibling(path.getFileName() + ".sync.tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (int number : numbers) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(number + System.lineSeparator());
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
