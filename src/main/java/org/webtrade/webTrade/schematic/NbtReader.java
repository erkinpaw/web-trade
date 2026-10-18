package org.webtrade.webTrade.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Минимальный читатель NBT (формат Notch). Своя реализация, чтобы не тащить зависимость
 * и не лезть в NMS: нам нужно всего несколько типов тегов.
 * <p>
 * Compound возвращается как Map, список — как List, массивы — как byte[]/int[]/long[].
 */
public final class NbtReader {

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    /** Ограничение вложенности — защита от кривых/злонамеренных файлов. */
    private static final int MAX_DEPTH = 32;

    private NbtReader() {
    }

    /** Читает NBT, сам определяя, сжат файл gzip или нет. Возвращает корневой compound. */
    public static Map<String, Object> read(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();

        InputStream stream = (first == 0x1f && second == 0x8b)
                ? new GZIPInputStream(buffered)
                : buffered;

        try (DataInputStream in = new DataInputStream(stream)) {
            int type = in.readByte();
            if (type != TAG_COMPOUND) {
                throw new IOException("Это не NBT: корень должен быть compound, а тут тег " + type);
            }
            in.readUTF(); // имя корня — не нужно
            return readCompound(in, 0);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Слишком глубокая вложенность NBT");
        }
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = in.readByte();
            if (type == TAG_END) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Слишком глубокая вложенность NBT");
        }
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                byte[] bytes = new byte[readLength(in)];
                in.readFully(bytes);
                return bytes;
            }
            case TAG_STRING:
                return in.readUTF();
            case TAG_LIST: {
                int elementType = in.readByte();
                int length = readLength(in);
                List<Object> list = new ArrayList<>(Math.min(length, 1024));
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(in, elementType, depth + 1));
                }
                return list;
            }
            case TAG_COMPOUND:
                return readCompound(in, depth + 1);
            case TAG_INT_ARRAY: {
                int[] values = new int[readLength(in)];
                for (int i = 0; i < values.length; i++) {
                    values[i] = in.readInt();
                }
                return values;
            }
            case TAG_LONG_ARRAY: {
                long[] values = new long[readLength(in)];
                for (int i = 0; i < values.length; i++) {
                    values[i] = in.readLong();
                }
                return values;
            }
            default:
                throw new IOException("Неизвестный тег NBT: " + type);
        }
    }

    /** Длина не должна быть отрицательной или абсурдной — иначе схлопочем OOM на битом файле. */
    private static int readLength(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("Некорректная длина массива в NBT: " + length);
        }
        return length;
    }
}
