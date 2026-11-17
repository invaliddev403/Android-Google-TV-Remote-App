package com.invaliddev403.gtvremote;

import java.util.Locale;

final class HidKeyboard {
    static final int KEYBOARD_REPORT_ID = 1;
    static final int CONSUMER_REPORT_ID = 2;

    static final class Report {
        final int id;
        final byte[] data;

        Report(int id, byte[] data) {
            this.id = id;
            this.data = data;
        }
    }

    private HidKeyboard() {
    }

    static byte[] descriptor() {
        return bytes(
                0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, KEYBOARD_REPORT_ID,
                0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
                0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08,
                0x81, 0x01, 0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01,
                0x29, 0x05, 0x91, 0x02, 0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
                0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07,
                0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xC0,

                0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, CONSUMER_REPORT_ID,
                0x09, 0x02, 0xA1, 0x02, 0x05, 0x09, 0x19, 0x01, 0x29, 0x0A,
                0x15, 0x01, 0x25, 0x0A, 0x75, 0x04, 0x95, 0x01, 0x81, 0x00, 0xC0,
                0x05, 0x0C, 0x09, 0x86, 0x15, 0xFF, 0x25, 0x01, 0x75, 0x02,
                0x95, 0x01, 0x81, 0x46, 0x09, 0xE9, 0x09, 0xEA, 0x15, 0x00,
                0x75, 0x01, 0x95, 0x02, 0x81, 0x02, 0x09, 0xE2, 0x09, 0x30,
                0x09, 0xCD, 0x09, 0xB3, 0x09, 0xB4, 0x09, 0xB5, 0x09, 0xB6,
                0x09, 0xB7, 0x0A, 0x23, 0x02, 0x0A, 0x24, 0x02, 0x15, 0x01,
                0x25, 0x0A, 0x75, 0x04, 0x95, 0x01, 0x81, 0x00, 0x09, 0x80,
                0xA1, 0x02, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x01,
                0x25, 0x03, 0x75, 0x02, 0x95, 0x01, 0x81, 0x00, 0xC0, 0x81, 0x03, 0xC0);
    }

    static Report key(String key) {
        switch (key) {
            case "up": return keyboard(0, 0x52);
            case "down": return keyboard(0, 0x51);
            case "left": return keyboard(0, 0x50);
            case "right": return keyboard(0, 0x4F);
            case "ok": return keyboard(0, 0x28);
            case "back": return consumer(0x0A00);
            case "home": return consumer(0x0900);
            case "power": return consumer(0x0200);
            case "volume_up": return consumer(0x0040);
            case "volume_down": return consumer(0x0080);
            case "mute": return consumer(0x0100);
            case "play_pause": return consumer(0x0300);
            case "rewind": return consumer(0x0500);
            case "fast_forward": return consumer(0x0400);
            case "previous": return consumer(0x0700);
            case "next": return consumer(0x0600);
            case "stop": return consumer(0x0800);
            default: throw new IllegalArgumentException("Unknown key: " + key);
        }
    }

    static Report character(char value) {
        if (value >= 'a' && value <= 'z') return keyboard(0, 0x04 + value - 'a');
        if (value >= 'A' && value <= 'Z') return keyboard(2, 0x04 + value - 'A');
        if (value >= '1' && value <= '9') return keyboard(0, 0x1E + value - '1');

        switch (value) {
            case '0': return keyboard(0, 0x27);
            case ' ': return keyboard(0, 0x2C);
            case '`': return keyboard(0, 0x35);
            case '~': return keyboard(2, 0x35);
            case '-': return keyboard(0, 0x2D);
            case '_': return keyboard(2, 0x2D);
            case '=': return keyboard(0, 0x2E);
            case '+': return keyboard(2, 0x2E);
            case '[': return keyboard(0, 0x2F);
            case '{': return keyboard(2, 0x2F);
            case ']': return keyboard(0, 0x30);
            case '}': return keyboard(2, 0x30);
            case '\\': return keyboard(0, 0x31);
            case '|': return keyboard(2, 0x31);
            case ';': return keyboard(0, 0x33);
            case ':': return keyboard(2, 0x33);
            case '\'': return keyboard(0, 0x34);
            case '"': return keyboard(2, 0x34);
            case ',': return keyboard(0, 0x36);
            case '<': return keyboard(2, 0x36);
            case '.': return keyboard(0, 0x37);
            case '>': return keyboard(2, 0x37);
            case '/': return keyboard(0, 0x38);
            case '?': return keyboard(2, 0x38);
            case '!': return keyboard(2, 0x1E);
            case '@': return keyboard(2, 0x1F);
            case '#': return keyboard(2, 0x20);
            case '$': return keyboard(2, 0x21);
            case '%': return keyboard(2, 0x22);
            case '^': return keyboard(2, 0x23);
            case '&': return keyboard(2, 0x24);
            case '*': return keyboard(2, 0x25);
            case '(': return keyboard(2, 0x26);
            case ')': return keyboard(2, 0x27);
            default: throw new IllegalArgumentException(
                    String.format(Locale.US, "Unsupported character U+%04X", (int)value));
        }
    }

    static Report releaseKeyboard() {
        return new Report(KEYBOARD_REPORT_ID, new byte[8]);
    }

    static Report releaseConsumer() {
        return new Report(CONSUMER_REPORT_ID, new byte[2]);
    }

    private static Report keyboard(int modifier, int code) {
        byte[] data = new byte[8];
        data[0] = (byte)modifier;
        data[2] = (byte)code;
        return new Report(KEYBOARD_REPORT_ID, data);
    }

    private static Report consumer(int value) {
        return new Report(CONSUMER_REPORT_ID, bytes(value & 0xFF, value >> 8));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte)values[i];
        }
        return result;
    }
}
