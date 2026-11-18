package com.invaliddev403.gtvremote;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

final class TvRemoteController {
    enum State { DISCONNECTED, PAIRING, AWAITING_CODE, CONNECTING, CONNECTED, ERROR }

    interface Listener {
        void onStateChanged(State state, String host, String message);
        void onDeviceInfoChanged();
        void onError(String message);
    }

    private static final String KEY_ALIAS = "gtv_remote_identity";
    private static final int CONTROL_PORT = 6466;
    private static final int PAIRING_PORT = 6467;
    private static final int FEATURES = 1 | 2 | 4 | 32 | 64 | 512;
    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        KEYS.put("up", 19);
        KEYS.put("down", 20);
        KEYS.put("left", 21);
        KEYS.put("right", 22);
        KEYS.put("ok", 23);
        KEYS.put("back", 4);
        KEYS.put("home", 3);
        KEYS.put("power", 26);
        KEYS.put("volume_up", 24);
        KEYS.put("volume_down", 25);
        KEYS.put("mute", 164);
        KEYS.put("play_pause", 85);
        KEYS.put("stop", 86);
        KEYS.put("next", 87);
        KEYS.put("previous", 88);
        KEYS.put("rewind", 89);
        KEYS.put("fast_forward", 90);
    }

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private volatile State state = State.DISCONNECTED;
    private volatile String currentHost = "";
    private volatile String model = "";
    private volatile String vendor = "";
    private volatile String softwareVersion = "";
    private volatile String currentApp = "";
    private volatile Boolean poweredOn;
    private SSLSocket socket;
    private int activeFeatures = FEATURES;
    private int imeCounter;
    private int fieldCounter;

    TvRemoteController(Listener listener) {
        this.listener = listener;
    }

    State getState() {
        return state;
    }

    String getHost() {
        return currentHost;
    }

    boolean isConnected() {
        return state == State.CONNECTED;
    }

    String deviceSummary() {
        StringBuilder result = new StringBuilder();
        if (!vendor.isEmpty() || !model.isEmpty()) {
            result.append("TV: ").append(vendor).append(vendor.isEmpty() || model.isEmpty() ? "" : " ")
                    .append(model).append('\n');
        }
        if (!softwareVersion.isEmpty()) result.append("Remote service: ").append(softwareVersion).append('\n');
        result.append("Current app: ").append(currentApp.isEmpty() ? "Not reported" : currentApp).append('\n');
        result.append("TV power: ").append(poweredOn == null ? "Not reported" : poweredOn ? "On" : "Off");
        return result.toString();
    }

    void pair(String host) {
        String cleanHost = cleanHost(host);
        if (cleanHost.isEmpty()) {
            listener.onError("Enter the TV's IP address");
            return;
        }
        int operation = begin(cleanHost);
        postState(operation, State.PAIRING, "Connecting to TV pairing service…");
        executor.execute(() -> {
            try {
                SSLSocket pairingSocket = openTls(cleanHost, PAIRING_PORT, operation);
                Proto.Writer request = outerMessage()
                        .message(10, new Proto.Writer().string(1, "atvremote").string(2, "GTV Remote"));
                send(pairingSocket, request.toByteArray());

                Proto.Reader acknowledgement = readMessage(pairingSocket);
                checkPairingStatus(acknowledgement);
                if (!acknowledgement.has(11)) throw new IOException("TV did not accept the pairing request");
                Proto.Writer encoding = new Proto.Writer().integer(1, 3).integer(2, 6);
                send(pairingSocket, outerMessage().message(20, new Proto.Writer()
                        .message(1, encoding).integer(3, 1)).toByteArray());

                Proto.Reader options = readMessage(pairingSocket);
                checkPairingStatus(options);
                if (!options.has(20)) throw new IOException("TV rejected the pairing options");
                send(pairingSocket, outerMessage().message(30, new Proto.Writer()
                        .message(1, encoding).integer(2, 1)).toByteArray());

                Proto.Reader configured = readMessage(pairingSocket);
                checkPairingStatus(configured);
                if (!configured.has(31)) throw new IOException("TV did not complete pairing setup");
                postState(operation, State.AWAITING_CODE, "Enter the six-character code shown on the TV");
            } catch (Exception exception) {
                fail(operation, pairingMessage(exception));
            }
        });
    }

    void finishPairing(String code) {
        String cleanCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!cleanCode.matches("[0-9A-F]{6}")) {
            listener.onError("Enter the six hexadecimal characters shown on the TV");
            return;
        }
        int operation = generation;
        if (state != State.AWAITING_CODE || socket == null) {
            listener.onError("Pairing is no longer active; start again");
            return;
        }
        postState(operation, State.PAIRING, "Checking the pairing code…");
        executor.execute(() -> {
            try {
                SSLSocket pairingSocket = socket;
                byte[] secret = pairingSecret(pairingSocket, cleanCode);
                send(pairingSocket, outerMessage().message(40,
                        new Proto.Writer().bytes(1, secret)).toByteArray());
                Proto.Reader response = readMessage(pairingSocket);
                checkPairingStatus(response);
                if (!response.has(41)) throw new IOException("TV rejected the pairing code");
                closeSocket(pairingSocket);
                connectRemote(operation, currentHost);
            } catch (Exception exception) {
                fail(operation, exception instanceof IllegalArgumentException
                        ? exception.getMessage() : pairingMessage(exception));
            }
        });
    }

    void connect(String host) {
        String cleanHost = cleanHost(host);
        if (cleanHost.isEmpty()) {
            listener.onError("Enter the TV's IP address");
            return;
        }
        int operation = begin(cleanHost);
        postState(operation, State.CONNECTING, "Connecting to Google TV…");
        executor.execute(() -> connectRemote(operation, cleanHost));
    }

    void disconnect() {
        int operation = begin("");
        closeSocket(socket);
        postState(operation, State.DISCONNECTED, "Disconnected");
    }

    void sendKey(String key) {
        Integer keyCode = KEYS.get(key);
        if (keyCode == null) {
            listener.onError("Unknown remote key");
            return;
        }
        int operation = generation;
        executor.execute(() -> {
            if (!canSend(operation)) return;
            try {
                send(socket, new Proto.Writer().message(10, new Proto.Writer()
                        .integer(1, keyCode).integer(2, 3)).toByteArray());
            } catch (IOException exception) {
                fail(operation, "Could not send the key: " + exception.getMessage());
            }
        });
    }

    void sendText(String text) {
        if (text == null || text.isEmpty() || text.length() > 96) {
            listener.onError("Enter between 1 and 96 characters");
            return;
        }
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value < 32 || value > 126) {
                listener.onError("Only standard printable ASCII characters are supported");
                return;
            }
        }
        int operation = generation;
        executor.execute(() -> {
            if (!canSend(operation)) return;
            try {
                Proto.Writer field = new Proto.Writer().integer(1, text.length())
                        .integer(2, text.length()).string(3, text);
                Proto.Writer edit = new Proto.Writer().integer(1, 1).message(2, field);
                send(socket, new Proto.Writer().message(21, new Proto.Writer()
                        .integer(1, imeCounter).integer(2, fieldCounter).message(3, edit)).toByteArray());
            } catch (IOException exception) {
                fail(operation, "Could not send text: " + exception.getMessage());
            }
        });
    }

    private void connectRemote(int operation, String host) {
        try {
            postState(operation, State.CONNECTING, "Opening the secure remote connection…");
            SSLSocket remoteSocket = openTls(host, CONTROL_PORT, operation);
            remoteSocket.setSoTimeout(20000);
            activeFeatures = FEATURES;
            imeCounter = 0;
            fieldCounter = 0;
            model = "";
            vendor = "";
            softwareVersion = "";
            currentApp = "";
            poweredOn = null;
            while (operation == generation) {
                Proto.Reader message = readMessage(remoteSocket);
                if (message.has(1)) {
                    Proto.Reader configure = message.message(1);
                    activeFeatures = FEATURES & (int)configure.integer(1, FEATURES);
                    Proto.Reader info = configure.message(2);
                    if (info != null) {
                        model = info.string(1);
                        vendor = info.string(2);
                        softwareVersion = info.string(6);
                    }
                    send(remoteSocket, new Proto.Writer().message(1, new Proto.Writer()
                            .integer(1, activeFeatures).message(2, new Proto.Writer()
                                    .integer(3, 1).string(4, "1").string(5, "atvremote")
                                    .string(6, BuildConfig.VERSION_NAME))).toByteArray());
                    postDeviceInfo(operation);
                } else if (message.has(2)) {
                    send(remoteSocket, new Proto.Writer().message(2,
                            new Proto.Writer().integer(1, activeFeatures)).toByteArray());
                } else if (message.has(8)) {
                    Proto.Reader ping = message.message(8);
                    send(remoteSocket, new Proto.Writer().message(9,
                            new Proto.Writer().integer(1, ping.integer(1, 0))).toByteArray());
                } else if (message.has(20)) {
                    Proto.Reader inject = message.message(20);
                    Proto.Reader app = inject.message(1);
                    Proto.Reader field = inject.message(2);
                    if (app != null) {
                        currentApp = app.string(12);
                        imeCounter = (int)app.integer(1, imeCounter);
                    }
                    if (field != null) fieldCounter = (int)field.integer(1, fieldCounter);
                    postDeviceInfo(operation);
                } else if (message.has(21)) {
                    Proto.Reader batch = message.message(21);
                    if (imeCounter == 0) imeCounter = (int)batch.integer(1, 0);
                    if (fieldCounter == 0) fieldCounter = (int)batch.integer(2, 0);
                } else if (message.has(22)) {
                    Proto.Reader show = message.message(22);
                    Proto.Reader field = show.message(2);
                    if (field != null) fieldCounter = (int)field.integer(1, fieldCounter);
                } else if (message.has(40)) {
                    Proto.Reader start = message.message(40);
                    poweredOn = start.integer(1, 0) != 0;
                    postState(operation, State.CONNECTED, "Connected over Wi-Fi");
                    postDeviceInfo(operation);
                }
            }
        } catch (Exception exception) {
            fail(operation, remoteMessage(exception));
        }
    }

    private boolean canSend(int operation) {
        if (operation == generation && state == State.CONNECTED && socket != null) return true;
        if (operation == generation) handler.post(() -> listener.onError("Connect to Google TV first"));
        return false;
    }

    private int begin(String host) {
        int operation = ++generation;
        closeSocket(socket);
        currentHost = host;
        return operation;
    }

    private SSLSocket openTls(String host, int port, int operation) throws Exception {
        if (operation != generation) throw new IOException("Connection cancelled");
        KeyStore keyStore = identity();
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, null);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), new TrustManager[] {trustManager()}, new SecureRandom());
        SSLSocket created = (SSLSocket)context.getSocketFactory().createSocket();
        socket = created;
        created.connect(new InetSocketAddress(host, port), 10000);
        created.setSoTimeout(15000);
        created.setUseClientMode(true);
        created.startHandshake();
        return created;
    }

    private KeyStore identity() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return keyStore;
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1);
        Date start = calendar.getTime();
        calendar.add(Calendar.YEAR, 10);
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setCertificateSubject(new X500Principal("CN=GTV Remote"))
                .setCertificateSerialNumber(BigInteger.valueOf(1000))
                .setCertificateNotBefore(start)
                .setCertificateNotAfter(calendar.getTime())
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build();
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        generator.initialize(specification);
        generator.generateKeyPair();
        return keyStore;
    }

    private byte[] pairingSecret(SSLSocket pairingSocket, String code) throws Exception {
        X509Certificate client = (X509Certificate)identity().getCertificate(KEY_ALIAS);
        Certificate[] peers = pairingSocket.getSession().getPeerCertificates();
        if (peers.length == 0 || !(peers[0] instanceof X509Certificate)) {
            throw new IOException("TV did not provide a pairing certificate");
        }
        RSAPublicKey clientKey = (RSAPublicKey)client.getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey)peers[0].getPublicKey();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(unsigned(clientKey.getModulus()));
        digest.update(unsigned(clientKey.getPublicExponent()));
        digest.update(unsigned(serverKey.getModulus()));
        digest.update(unsigned(serverKey.getPublicExponent()));
        byte[] codeBytes = new byte[2];
        codeBytes[0] = (byte)Integer.parseInt(code.substring(2, 4), 16);
        codeBytes[1] = (byte)Integer.parseInt(code.substring(4, 6), 16);
        digest.update(codeBytes);
        byte[] secret = digest.digest();
        if ((secret[0] & 0xff) != Integer.parseInt(code.substring(0, 2), 16)) {
            throw new IllegalArgumentException("The pairing code does not match; check it and try again");
        }
        return secret;
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] unsigned = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, unsigned, 0, unsigned.length);
            return unsigned;
        }
        return bytes;
    }

    private static Proto.Writer outerMessage() {
        return new Proto.Writer().integer(1, 2).integer(2, 200);
    }

    private static void checkPairingStatus(Proto.Reader message) throws IOException {
        long status = message.integer(2, 400);
        if (status != 200) throw new IOException("TV pairing failed with status " + status);
    }

    private static Proto.Reader readMessage(Socket socket) throws IOException {
        return new Proto.Reader(Proto.readFrame(socket.getInputStream()));
    }

    private static void send(Socket socket, byte[] message) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(Proto.frame(message));
        output.flush();
    }

    private void closeSocket(Socket closing) {
        if (closing == null) return;
        try {
            closing.close();
        } catch (IOException ignored) {
        }
        if (socket == closing) socket = null;
    }

    private void fail(int operation, String message) {
        if (operation != generation) return;
        closeSocket(socket);
        postState(operation, State.ERROR, message);
    }

    private void postState(int operation, State newState, String message) {
        if (operation != generation) return;
        state = newState;
        handler.post(() -> {
            if (operation == generation) listener.onStateChanged(newState, currentHost, message);
        });
    }

    private void postDeviceInfo(int operation) {
        handler.post(() -> {
            if (operation == generation) listener.onDeviceInfoChanged();
        });
    }

    private static String cleanHost(String host) {
        return host == null ? "" : host.trim();
    }

    private static String pairingMessage(Exception exception) {
        String message = exception.getMessage();
        return "Could not pair. Check the TV IP and that it is awake on the same Wi-Fi network"
                + (message == null ? "" : ": " + message);
    }

    private static String remoteMessage(Exception exception) {
        String message = exception.getMessage();
        return "Remote connection failed. Pair again if the TV rejected this phone"
                + (message == null ? "" : ": " + message);
    }

    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    private static X509TrustManager trustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
