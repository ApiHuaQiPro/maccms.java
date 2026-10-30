package com.maccms.spike.thymeleaf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 最小 HTTP/1.1 客户端：读到连接关闭为止，保留原始字节。
 * 用它而不是高层客户端，是为了在"响应已提交后渲染失败"时仍能看到真实状态行、头与被截断的正文，
 * 高层客户端遇到不完整的分块编码通常直接抛异常，看不到服务器实际发了什么。
 */
final class RawHttp {

    private RawHttp() {
    }

    static Response get(int port, String path) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nAccept: text/html\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[8192];
            boolean timedOut = false;
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    raw.write(buf, 0, n);
                }
            } catch (SocketTimeoutException e) {
                timedOut = true;
            } catch (IOException e) {
                // 连接被服务器重置：保留已读到的字节
            }
            return Response.parse(raw.toByteArray(), timedOut);
        }
    }

    record Response(int status, Map<String, String> headers, String body, boolean chunked,
                    boolean complete, int rawLength, boolean timedOut) {

        static Response parse(byte[] raw, boolean timedOut) {
            String all = new String(raw, StandardCharsets.ISO_8859_1);
            int split = all.indexOf("\r\n\r\n");
            if (split < 0) {
                return new Response(-1, Map.of(), "", false, false, raw.length, timedOut);
            }
            String[] lines = all.substring(0, split).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            byte[] body = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
            boolean chunked = "chunked".equalsIgnoreCase(headers.get("transfer-encoding"));
            if (chunked) {
                return decodeChunked(status, headers, body, raw.length, timedOut);
            }
            boolean complete = !headers.containsKey("content-length")
                    || Integer.parseInt(headers.get("content-length")) == body.length;
            return new Response(status, headers, new String(body, StandardCharsets.UTF_8), false, complete,
                    raw.length, timedOut);
        }

        private static Response decodeChunked(int status, Map<String, String> headers, byte[] body, int rawLength,
                                              boolean timedOut) {
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            int pos = 0;
            boolean terminated = false;
            while (pos < body.length) {
                int eol = indexOfCrlf(body, pos);
                if (eol < 0) {
                    break;
                }
                int size = Integer.parseInt(new String(body, pos, eol - pos, StandardCharsets.US_ASCII).split(";")[0].trim(), 16);
                pos = eol + 2;
                if (size == 0) {
                    terminated = true;
                    break;
                }
                int n = Math.min(size, body.length - pos);
                decoded.write(body, pos, n);
                pos += n + 2;
            }
            return new Response(status, headers, decoded.toString(StandardCharsets.UTF_8), true, terminated,
                    rawLength, timedOut);
        }

        private static int indexOfCrlf(byte[] b, int from) {
            for (int i = from; i + 1 < b.length; i++) {
                if (b[i] == '\r' && b[i + 1] == '\n') {
                    return i;
                }
            }
            return -1;
        }

        /** 供报告引用的一行摘要。 */
        String summary() {
            return "status=" + status + " chunked=" + chunked + " complete=" + complete + " bodyChars=" + body.length()
                    + " endsWithHtml=" + body.stripTrailing().endsWith("</html>") + " content-length="
                    + headers.get("content-length") + " content-type=" + headers.get("content-type");
        }
    }
}
