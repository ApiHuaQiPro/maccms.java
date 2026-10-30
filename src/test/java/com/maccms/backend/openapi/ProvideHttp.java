package com.maccms.backend.openapi;

import com.maccms.backend.common.clientip.IpAddress;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.openapi.application.ProvideSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provide 真实 HTTP 测试的支持：原始套接字客户端（可指定连接的本机地址，IPv4 或 IPv6 回环；请求头原样发送，包括伪造的转发头），
 * 以及按 var_export 文本构造旧配置。只连接本机回环地址。
 */
final class ProvideHttp {

    private ProvideHttp() {
    }

    record Response(int status, Map<String, String> headers, String body) {
    }

    /** {@code host} 只能是回环地址字面量（127.0.0.1 或 ::1）。 */
    static Response get(String host, int port, String path, String... headers) throws IOException {
        return request("GET", host, port, path, headers);
    }

    static Response request(String method, String host, int port, String path, String... headers) throws IOException {
        IpAddress ip = IpAddress.parse(host).orElseThrow();
        if (!ip.toString().equals("127.0.0.1") && !ip.toString().equals("::1")) {
            throw new IllegalArgumentException("只连接本机回环地址");
        }
        StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: maccms.local.test\r\n");
        for (String h : headers) {
            req.append(h).append("\r\n");
        }
        req.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(bytes(host)), port), 5_000);
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            in.transferTo(raw);
            String all = raw.toString(StandardCharsets.UTF_8);
            int split = all.indexOf("\r\n\r\n");
            String[] lines = all.substring(0, split).split("\r\n");
            Map<String, String> h = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            return new Response(Integer.parseInt(lines[0].split(" ")[1]), h, all.substring(split + 4));
        }
    }

    private static byte[] bytes(String host) {
        return host.equals("::1") ? new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
                : new byte[] {127, 0, 0, 1};
    }

    /** 旧配置：vod 打开且 charge=1（auth 为给定值），art 打开、不收费，其余关闭。 */
    static ProvideSettings settings(String auth) {
        String php = "<?php\nreturn array (\n  'api' => array (\n"
                + "    'vod' => array ('status' => '1', 'charge' => '1', 'auth' => '" + auth + "', 'pagesize' => '20',"
                + " 'datafilter' => 'vod_status=1', 'cachetime' => '60'),\n"
                + "    'art' => array ('status' => '1', 'charge' => '0', 'pagesize' => '20'),\n"
                + "  ),\n);\n";
        return ProvideSettings.from(VarExportParser.parse(php.getBytes(StandardCharsets.UTF_8)));
    }

    static LegacyRowPageDto empty() {
        return new LegacyRowPageDto(0, List.of());
    }
}
