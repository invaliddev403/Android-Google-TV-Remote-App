package com.invaliddev403.gtvremote;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Proto {
    static final class Field {
        final int number;
        final int wireType;
        final long integer;
        final byte[] bytes;

        Field(int number, int wireType, long integer, byte[] bytes) {
            this.number = number;
            this.wireType = wireType;
            this.integer = integer;
            this.bytes = bytes;
        }

        String string() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        Reader message() throws IOException {
            return new Reader(bytes);
        }
    }

    static final class Reader {
        private final List<Field> fields = new ArrayList<>();

        Reader(byte[] data) throws IOException {
            int position = 0;
            while (position < data.length) {
                long key = readVarint(data, position);
                position += varintLength(data, position);
                int number = (int)(key >>> 3);
                int wireType = (int)(key & 7);
                if (number == 0) throw new IOException("Invalid protobuf field");
                if (wireType == 0) {
                    long value = readVarint(data, position);
                    position += varintLength(data, position);
                    fields.add(new Field(number, wireType, value, null));
                } else if (wireType == 1) {
                    if (position + 8 > data.length) throw new EOFException();
                    fields.add(new Field(number, wireType, 0, slice(data, position, 8)));
                    position += 8;
                } else if (wireType == 2) {
                    long length = readVarint(data, position);
                    position += varintLength(data, position);
                    if (length < 0 || length > data.length - position) throw new EOFException();
                    fields.add(new Field(number, wireType, 0, slice(data, position, (int)length)));
                    position += (int)length;
                } else if (wireType == 5) {
                    if (position + 4 > data.length) throw new EOFException();
                    fields.add(new Field(number, wireType, 0, slice(data, position, 4)));
                    position += 4;
                } else {
                    throw new IOException("Unsupported protobuf wire type " + wireType);
                }
            }
        }

        Field first(int number) {
            for (Field field : fields) {
                if (field.number == number) return field;
            }
            return null;
        }

        long integer(int number, long fallback) {
            Field field = first(number);
            return field == null || field.wireType != 0 ? fallback : field.integer;
        }

        String string(int number) {
            Field field = first(number);
            return field == null || field.wireType != 2 ? "" : field.string();
        }

        Reader message(int number) throws IOException {
            Field field = first(number);
            return field == null || field.wireType != 2 ? null : field.message();
        }

        boolean has(int number) {
            return first(number) != null;
        }
    }

    static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        Writer integer(int field, long value) {
            varint(field << 3);
            varint(value);
            return this;
        }

        Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Writer message(int field, Writer value) {
            return bytes(field, value.toByteArray());
        }

        Writer bytes(int field, byte[] value) {
            varint((field << 3) | 2);
            varint(value.length);
            output.write(value, 0, value.length);
            return this;
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }

        private void varint(long value) {
            while ((value & ~0x7fL) != 0) {
                output.write(((int)value & 0x7f) | 0x80);
                value >>>= 7;
            }
            output.write((int)value);
        }
    }

    static byte[] readFrame(InputStream input) throws IOException {
        int length = 0;
        int shift = 0;
        while (shift < 35) {
            int value = input.read();
            if (value < 0) throw new EOFException("TV closed the connection");
            length |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                if (length < 0 || length > 1024 * 1024) throw new IOException("Invalid message length");
                byte[] data = new byte[length];
                int position = 0;
                while (position < length) {
                    int count = input.read(data, position, length - position);
                    if (count < 0) throw new EOFException("TV closed the connection");
                    position += count;
                }
                return data;
            }
            shift += 7;
        }
        throw new IOException("Invalid message framing");
    }

    static byte[] frame(byte[] message) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int length = message.length;
        while ((length & ~0x7f) != 0) {
            output.write((length & 0x7f) | 0x80);
            length >>>= 7;
        }
        output.write(length);
        output.write(message, 0, message.length);
        return output.toByteArray();
    }

    private static long readVarint(byte[] data, int offset) throws IOException {
        long value = 0;
        for (int index = 0; index < 10; index++) {
            if (offset + index >= data.length) throw new EOFException();
            int current = data[offset + index] & 0xff;
            value |= (long)(current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) return value;
        }
        throw new IOException("Invalid protobuf integer");
    }

    private static int varintLength(byte[] data, int offset) throws IOException {
        for (int index = 0; index < 10; index++) {
            if (offset + index >= data.length) throw new EOFException();
            if ((data[offset + index] & 0x80) == 0) return index + 1;
        }
        throw new IOException("Invalid protobuf integer");
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        return result;
    }

    private Proto() {
    }
}
