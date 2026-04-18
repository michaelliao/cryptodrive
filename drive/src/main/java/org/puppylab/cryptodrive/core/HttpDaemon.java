package org.puppylab.cryptodrive.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.puppylab.cryptodrive.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * HTTP service on 127.0.0.1:37432 for the rpc.
 */
public class HttpDaemon implements HttpHandler {

    public static final int PORT = 37432;

    final Logger logger = LoggerFactory.getLogger(getClass());

    private HttpServer httpServer;
    private Runnable   onActivate;

    /**
     * Binds the server socket. Call on the main thread before {@link #start()}.
     * Returns {@code false} if the port is already in use (duplicate instance).
     */
    public boolean listen() {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            this.httpServer.createContext("/", this);
            logger.info("HTTP service listening on 127.0.0.1:{}", PORT);
            return true;
        } catch (IOException e) {
            logger.error("Failed to bind port {} — another instance may be running", PORT, e);
            return false;
        }
    }

    /**
     * Blocking accept loop — call from a dedicated background thread after
     * {@link #listen()}.
     */
    public void start() {
        this.httpServer.start();
    }

    /** Set the callback invoked when another instance sends {@code POST /activate}. */
    public void setOnActivate(Runnable onActivate) {
        this.onActivate = onActivate;
    }

    /** Called by MainWindow when the SWT shell is disposed. */
    public void stop() {
        this.httpServer.stop(0);
    }

    // -------- http handler --------

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        String query = uri.getRawQuery();
        String body = null;
        logger.info("http {}: {}", method, path);
        if ("POST".equals(method)) {
            body = readRequestBody(exchange);
        }
        if ("OPTIONS".equals(method)) {
            sendCors(exchange);
        } else {
            processHttp(exchange, method, path, query, body);
        }
        exchange.close();
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (var in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // -------- cors response --------

    private void sendCors(HttpExchange exchange) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers",
                "Content-Type, X-Extension-Id, X-Extension-Timestamp, X-Extension-Signature");
        exchange.sendResponseHeaders(204, -1);
    }

    private void processHttp(HttpExchange exchange, String method, String path, String query, String body)
            throws IOException {
        if ("POST".equals(method) && "/activate".equals(path)) {
            logger.info("received /activate from another instance");
            if (onActivate != null) {
                onActivate.run();
            }
            sendResponse(exchange, "application/json", "\"ok\"");
            return;
        }
        sendResponse(exchange, "application/json", JsonUtils.toJson("Hello world"));
    }

    private void sendResponse(HttpExchange exchange, String contentType, String content) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
