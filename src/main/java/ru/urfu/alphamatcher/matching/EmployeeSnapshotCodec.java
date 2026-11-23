package ru.urfu.alphamatcher.matching;

import ru.urfu.alphamatcher.demo.DemoOperationState.EmployeeSnapshot;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Keeps the legacy Java payload readable and protects new writes with a versioned integrity envelope. */
final class EmployeeSnapshotCodec {
    private static final int MAGIC = 0x414D4553;
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD = 10 * 1024 * 1024;
    private static final int HEADER = 2 * Integer.BYTES + 32;

    private EmployeeSnapshotCodec() { }

    static byte[] encode(EmployeeSnapshot snapshot) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(buffer)) {
            stream.writeObject(snapshot);
        }
        byte[] payload = buffer.toByteArray();
        if (payload.length > MAX_PAYLOAD) throw new IOException("Превышен размер снимка");
        return ByteBuffer.allocate(HEADER + payload.length)
                .putInt(MAGIC).putInt(VERSION).put(digest(payload)).put(payload).array();
    }

    static EmployeeSnapshot decode(byte[] bytes) throws IOException, ClassNotFoundException {
        if (bytes.length < Integer.BYTES || bytes.length > MAX_PAYLOAD + HEADER)
            throw new IOException("Недопустимый размер снимка");
        byte[] payload = bytes;
        if (ByteBuffer.wrap(bytes).getInt() == MAGIC) {
            if (bytes.length <= HEADER || ByteBuffer.wrap(bytes).getInt(Integer.BYTES) != VERSION)
                throw new IOException("Неподдерживаемая версия снимка");
            payload = Arrays.copyOfRange(bytes, HEADER, bytes.length);
            if (!MessageDigest.isEqual(Arrays.copyOfRange(bytes, 2 * Integer.BYTES, HEADER), digest(payload)))
                throw new IOException("Контрольная сумма снимка не совпадает");
        }
        if (payload.length > MAX_PAYLOAD) throw new IOException("Превышен размер снимка");
        try (var stream = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            stream.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "maxbytes=10485760;maxdepth=64;ru.urfu.alphamatcher.demo.DemoOperationState$*;java.base/*;!*"));
            if (!(stream.readObject() instanceof EmployeeSnapshot snapshot) || stream.read() != -1)
                throw new IOException("Недопустимое содержимое снимка");
            return snapshot;
        }
    }

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
