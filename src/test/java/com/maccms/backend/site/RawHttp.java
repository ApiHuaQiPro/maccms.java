package com.maccms.backend.site;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 最小 HTTP/1.1 客户端（沿用 MACC-7 spike）：读到连接关闭为止，保留原始字节，
 * 用来确认真实 Tomcat 发出的状态行、头与正文是否完整（高层客户端遇到截断的响应通常直接抛异常）。
 */
final class RawHttp {

    private RawHttp() {
    }

    /** extraHeaders 为原样追加的请求头行（不含 CRLF）。Host 头可被覆盖。 */
    static Response get(int port, String path, String... extraHeaders) throws IOException {
        return request("GET", port, path, extraHeaders);
    }

    /** HEAD 响应只有头：解析结果的 body 为空，complete 不适用。 */
    static Response head(int port, String path) throws IOException {
        return request("HEAD", port, path);
    }

    private static Response request(String method, int port, String path, String... extraHeaders)
            throws IOException {
        StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        boolean hasHost = Arrays.stream(extraHeaders).anyMatch(h -> h.toLowerCase(Locale.ROOT).startsWith("host:"));
        if (!hasHost) {
            req.append("Host: 127.0.0.1:").append(port).append("\r\n");
        }
        for (String h : extraHeaders) {
            req.append(h).append("\r\n");
        }
        req.append("Accept: text/html\r\nConnection: close\r\n\r\n");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    raw.write(buf, 0, n);
                }
            } catch (SocketTimeoutException e) {
                // 超时：按已读到的字节判断完整性
            } catch (IOException e) {
                // 连接被重置：保留已读到的字节
            }
            return Response.parse(raw.toByteArray());
        }
    }

    record Response(int status, Map<String, String> headers, String body, boolean chunked, boolean complete) {

        static Response parse(byte[] raw) {
            String all = new String(raw, StandardCharsets.ISO_8859_1);
            int split = all.indexOf("\r\n\r\n");
            if (split < 0) {
                return new Response(-1, Map.of(), "", false, false);
            }
            String[] lines = all.substring(0, split).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            byte[] body = Arrays.copyOfRange(raw, split + 4, raw.length);
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                return decodeChunked(status, headers, body);
            }
            boolean complete = headers.containsKey("content-length")
                    && Integer.parseInt(headers.get("content-length")) == body.length;
            return new Response(status, headers, new String(body, StandardCharsets.UTF_8), false, complete);
        }

        private static Response decodeChunked(int status, Map<String, String> headers, byte[] body) {
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            int pos = 0;
            boolean terminated = false;
            while (pos < body.length) {
                int eol = indexOfCrlf(body, pos);
                if (eol < 0) {
                    break;
                }
                int size = Integer.parseInt(
                        new String(body, pos, eol - pos, StandardCharsets.US_ASCII).split(";")[0].trim(), 16);
                pos = eol + 2;
                if (size == 0) {
                    terminated = true;
                    break;
                }
                int n = Math.min(size, body.length - pos);
                decoded.write(body, pos, n);
                pos += n + 2;
            }
            return new Response(status, headers, decoded.toString(StandardCharsets.UTF_8), true, terminated);
        }

        private static int indexOfCrlf(byte[] b, int from) {
            for (int i = from; i + 1 < b.length; i++) {
                if (b[i] == '\r' && b[i + 1] == '\n') {
                    return i;
                }
            }
            return -1;
        }
    }
}
