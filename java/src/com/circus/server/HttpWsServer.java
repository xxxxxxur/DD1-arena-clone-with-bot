package com.circus.server;

import com.circus.util.Json;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight, zero-dependency JDK 26 Virtual-Thread HTTP/1.1 + RFC 6455 WebSocket server.
 * Supports Keep-Alive, HTTP Byte-Range (206 Partial Content for audio streaming),
 * Cache-Control headers, and concurrent thread-safe WebSocket text/JSON messaging.
 */
public final class HttpWsServer implements AutoCloseable {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    @FunctionalInterface
    public interface HttpHandler {
        void handle(HttpRequest req, HttpResponse res) throws Exception;
    }

    @FunctionalInterface
    public interface WsHandler {
        void handle(WsSession session, HttpRequest upgradeReq) throws Exception;
    }

    public static final class HttpRequest {
        public final String method;
        public final String rawPath;
        public final String path;
        public final Map<String, String> query;
        public final Map<String, String> headers; // lowercased keys
        public final byte[] body;

        HttpRequest(String method, String rawTarget, Map<String, String> headers, byte[] body) {
            this.method = method;
            int qIdx = rawTarget.indexOf('?');
            String rawP = (qIdx >= 0) ? rawTarget.substring(0, qIdx) : rawTarget;
            this.rawPath = rawP;
            this.path = urlDecode(rawP);
            this.query = (qIdx >= 0) ? parseQuery(rawTarget.substring(qIdx + 1)) : Collections.emptyMap();
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String bodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public Map<String, Object> bodyJson() {
            String s = bodyString().trim();
            return s.isEmpty() ? new LinkedHashMap<>() : Json.parseObject(s);
        }
    }

    public static final class HttpResponse {
        private final OutputStream out;
        private final boolean keepAlive;
        private final boolean headOnly;
        private boolean sent = false;

        HttpResponse(OutputStream out, boolean keepAlive, boolean headOnly) {
            this.out = out;
            this.keepAlive = keepAlive;
            this.headOnly = headOnly;
        }

        public boolean isSent() { return sent; }

        public void sendBytes(int status, String contentType, byte[] data, Map<String, String> extraHeaders) throws IOException {
            sent = true;
            StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
            sb.append("Content-Type: ").append(contentType).append("\r\n");
            sb.append("Content-Length: ").append(data.length).append("\r\n");
            sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!headOnly && data.length > 0) {
                out.write(data);
            }
            out.flush();
        }

        public void sendJson(int status, Object payload) throws IOException {
            sendJson(status, payload, null);
        }

        public void sendJson(int status, Object payload, Map<String, String> extraHeaders) throws IOException {
            byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
            sendBytes(status, "application/json; charset=utf-8", bytes, extraHeaders);
        }

        public void sendText(int status, String text, Map<String, String> extraHeaders) throws IOException {
            sendBytes(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8), extraHeaders);
        }

        public void sendFile(Path file, String contentType, String rangeHeader, Map<String, String> extraHeaders) throws IOException {
            if (file == null || !Files.isRegularFile(file)) {
                sendText(404, "Not found", null);
                return;
            }
            long totalLen = Files.size(file);
            long start = 0;
            long end = totalLen - 1;
            boolean isPartial = false;

            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalLen > 0) {
                String spec = rangeHeader.substring(6).trim();
                int comma = spec.indexOf(',');
                if (comma >= 0) spec = spec.substring(0, comma).trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String sPart = spec.substring(0, dash).trim();
                    String ePart = spec.substring(dash + 1).trim();
                    try {
                        if (sPart.isEmpty() && !ePart.isEmpty()) {
                            long suffix = Long.parseLong(ePart);
                            start = Math.max(0, totalLen - suffix);
                        } else {
                            start = Long.parseLong(sPart);
                            if (!ePart.isEmpty()) {
                                end = Math.min(totalLen - 1, Long.parseLong(ePart));
                            }
                        }
                        if (start >= 0 && start <= end && start < totalLen) {
                            isPartial = true;
                        } else {
                            sent = true;
                            String hdr = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                                    + "Content-Range: bytes */" + totalLen + "\r\n"
                                    + "Content-Length: 0\r\n"
                                    + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n";
                            out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            out.flush();
                            return;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            long contentLen = (totalLen == 0) ? 0 : (end - start + 1);
            int status = isPartial ? 206 : 200;
            sent = true;

            StringBuilder sb = new StringBuilder(320);
            sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
            sb.append("Content-Type: ").append(contentType).append("\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Content-Length: ").append(contentLen).append("\r\n");
            if (isPartial) {
                sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(totalLen).append("\r\n");
            }
            sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));

            if (!headOnly && contentLen > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    raf.seek(start);
                    byte[] buf = new byte[65536];
                    long rem = contentLen;
                    while (rem > 0) {
                        int read = raf.read(buf, 0, (int) Math.min(buf.length, rem));
                        if (read < 0) break;
                        out.write(buf, 0, read);
                        rem -= read;
                    }
                }
            }
            out.flush();
        }
    }

    public static final class WsSession implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private volatile boolean closed = false;

        WsSession(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        public boolean isOpen() {
            return !closed && !socket.isClosed();
        }

        public void sendText(String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            sendFrame(0x1, payload);
        }

        public void sendJson(Object obj) throws IOException {
            sendText(Json.stringify(obj));
        }

        private void sendFrame(int opcode, byte[] payload) throws IOException {
            synchronized (writeLock) {
                if (closed) throw new IOException("WebSocket closed");
                out.write(0x80 | (opcode & 0x0F));
                int len = payload.length;
                if (len <= 125) {
                    out.write(len);
                } else if (len <= 65535) {
                    out.write(126);
                    out.write((len >>> 8) & 0xFF);
                    out.write(len & 0xFF);
                } else {
                    out.write(127);
                    long l = len;
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) ((l >>> (i * 8)) & 0xFF));
                    }
                }
                if (len > 0) out.write(payload);
                out.flush();
            }
        }

        /**
         * Reads the next complete text message, automatically answering PINGs and handling fragmentation.
         * Returns null on clean CLOSE or EOF.
         */
        public String receiveText() throws IOException {
            ByteArrayOutputStream msgBuf = null;
            while (!closed) {
                int b0 = in.read();
                if (b0 < 0) {
                    closed = true;
                    return null;
                }
                int b1 = in.read();
                if (b1 < 0) {
                    closed = true;
                    return null;
                }
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7F;
                if (payloadLen == 126) {
                    int h = in.read();
                    int l = in.read();
                    if (h < 0 || l < 0) return null;
                    payloadLen = ((h & 0xFF) << 8) | (l & 0xFF);
                } else if (payloadLen == 127) {
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++) {
                        int x = in.read();
                        if (x < 0) return null;
                        payloadLen = (payloadLen << 8) | (x & 0xFF);
                    }
                }
                if (payloadLen > 16 * 1024 * 1024) {
                    close();
                    throw new IOException("WebSocket frame too large: " + payloadLen);
                }
                byte[] maskKey = null;
                if (masked) {
                    maskKey = readFully(in, 4);
                    if (maskKey == null) return null;
                }
                byte[] payload = readFully(in, (int) payloadLen);
                if (payload == null) return null;
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= maskKey[i & 3];
                    }
                }

                if (opcode == 0x8) { // CLOSE
                    try { sendFrame(0x8, new byte[0]); } catch (IOException ignored) {}
                    close();
                    return null;
                } else if (opcode == 0x9) { // PING
                    sendFrame(0xA, payload);
                    continue;
                } else if (opcode == 0xA) { // PONG
                    continue;
                }

                if (fin && msgBuf == null) {
                    return new String(payload, StandardCharsets.UTF_8);
                }
                if (msgBuf == null) msgBuf = new ByteArrayOutputStream();
                msgBuf.write(payload);
                if (fin) {
                    return msgBuf.toString(StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        @Override
        public void close() {
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private final String host;
    private final int port;
    private final HttpHandler httpHandler;
    private final WsHandler wsHandler;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    public HttpWsServer(String host, int port, HttpHandler httpHandler, WsHandler wsHandler) {
        this.host = host;
        this.port = port;
        this.httpHandler = httpHandler;
        this.wsHandler = wsHandler;
    }

    public int start() throws IOException {
        InetAddress addr = InetAddress.getByName(host);
        this.serverSocket = new ServerSocket(port, 256, addr);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.running = true;
        int actualPort = serverSocket.getLocalPort();

        Thread acceptThread = Thread.ofVirtual().name("http-accept").start(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(60_000);
                    executor.submit(() -> handleConnection(client));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[HTTP] Accept error: " + e.getMessage());
                    }
                }
            }
        });
        return actualPort;
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 32768);

            while (running && !socket.isClosed()) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) return;

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0].toUpperCase(Locale.ROOT);
                String target = parts[1];
                String version = parts.length >= 3 ? parts[2] : "HTTP/1.1";

                Map<String, String> headers = new HashMap<>();
                while (true) {
                    String line = readLine(in);
                    if (line == null || line.isEmpty()) break;
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                                line.substring(colon + 1).trim());
                    }
                }

                // Check WebSocket upgrade
                String upgrade = headers.get("upgrade");
                if (upgrade != null && upgrade.equalsIgnoreCase("websocket") && wsHandler != null) {
                    String wsKey = headers.get("sec-websocket-key");
                    if (wsKey == null || wsKey.isBlank()) {
                        out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        return;
                    }
                    String accept = computeWsAccept(wsKey);
                    String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                    out.write(handshake.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    socket.setSoTimeout(0); // WebSocket stays open indefinitely
                    HttpRequest req = new HttpRequest(method, target, headers, new byte[0]);
                    try (WsSession session = new WsSession(socket, in, out)) {
                        wsHandler.handle(session, req);
                    }
                    return;
                }

                // Read HTTP body if Content-Length present
                byte[] body = new byte[0];
                String cl = headers.get("content-length");
                if (cl != null) {
                    int len = Integer.parseInt(cl.trim());
                    if (len > 0) {
                        body = readFully(in, len);
                        if (body == null) return;
                    }
                }

                String conn = headers.getOrDefault("connection", "");
                boolean keepAlive = "HTTP/1.1".equals(version)
                        ? !conn.equalsIgnoreCase("close")
                        : conn.equalsIgnoreCase("keep-alive");

                HttpRequest req = new HttpRequest(method, target, headers, body);
                HttpResponse res = new HttpResponse(out, keepAlive, "HEAD".equals(method));
                try {
                    httpHandler.handle(req, res);
                    if (!res.isSent()) {
                        res.sendText(404, "Not found", null);
                    }
                } catch (Exception e) {
                    if (!res.isSent()) {
                        res.sendJson(500, Map.of("error", String.valueOf(e.getMessage())));
                    }
                }
                if (!keepAlive) return;
            }
        } catch (SocketTimeoutException | SocketException ignored) {
            // Normal idle keep-alive close or client disconnect
        } catch (Exception e) {
            if (running) {
                System.err.println("[HTTP] Connection error: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return null;
            off += n;
        }
        return buf;
    }

    private static String computeWsAccept(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((key.trim() + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } else {
                map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return map;
    }

    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 206 -> "Partial Content";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 416 -> "Range Not Satisfiable";
            case 500 -> "Internal Server Error";
            default -> "OK";
        };
    }

    public static String guessMimeType(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".txt") || name.endsWith(".atlas")) return "text/plain; charset=utf-8";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}
