package com.maccms.backend.staticgen.web;

import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.common.clientip.IpAddress;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 定时任务入口的部署级鉴权（D2 ③，路线图第 5.5 节，25 文档第 7.1 节）。旧 {@code api.php/timming/index} 不做任何鉴权，任何人都能触发，
 * 带 {@code enforce=1} 还能无视时间表强制执行并回写 runtime。新版只在下列两种情况之一时放行：
 * <ol>
 * <li><b>口令</b>：部署方配置了 {@code MACCMS_TIMMING_TOKEN}，请求头 {@value #TOKEN_HEADER} 与之相同（按 SHA-256 摘要做定长比较）；</li>
 * <li><b>本机</b>：客户端地址（{@link ClientIpResolver}，同 B06 的可信代理规则）是回环地址；并且请求带有转发头时，连接对端必须是已登记的
 * 可信代理——否则（例如本机 nginx 转发了公网请求、但没有登记为可信代理）转发头说明请求来自别处，不算本机。</li>
 * </ol>
 * 其余一律拒绝：不执行任务、不回写 {@code timming.php}。口令只在内存里保存摘要，不出现在日志与输出中。
 */
public final class TimmingAccess {

    public static final String TOKEN_HEADER = "X-Maccms-Timming-Token";
    /** 口令的最短长度。 */
    public static final int MIN_TOKEN_LENGTH = 32;
    /** 表示"经过了代理"的请求头（出现任一即视为转发过的请求）。 */
    static final List<String> FORWARD_HEADERS = List.of("Forwarded", "X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP",
            "True-Client-IP", "Ali-CDN-Real-IP", "Client-IP", "X-Client-IP", "X-Cluster-Client-IP", "Fastly-Client-IP");

    private final ClientIpResolver ips;
    private final byte[] tokenDigest;

    /**
     * @param token 部署口令；null 或空串表示不接受口令
     * @throws IllegalStateException 口令短于 32 个字符或含空白、控制字符、非 ASCII 字符（信息只含参数名）
     */
    public TimmingAccess(ClientIpResolver ips, String token) {
        this.ips = ips;
        String t = token == null ? "" : token;
        if (t.isEmpty()) {
            this.tokenDigest = null;
            return;
        }
        if (t.length() < MIN_TOKEN_LENGTH
                || !t.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new IllegalStateException("maccms.timming.token（MACCMS_TIMMING_TOKEN）须为至少 32 个可见 ASCII 字符", null);
        }
        this.tokenDigest = sha256(t);
    }

    public boolean tokenConfigured() {
        return tokenDigest != null;
    }

    public boolean allowed(HttpServletRequest request) {
        String presented = request.getHeader(TOKEN_HEADER);
        if (tokenDigest != null && presented != null && MessageDigest.isEqual(sha256(presented), tokenDigest)) {
            return true;
        }
        boolean loopback = IpAddress.parse(ips.resolve(request)).map(IpAddress::isLoopback).orElse(false);
        if (!loopback) {
            return false;
        }
        boolean forwarded = FORWARD_HEADERS.stream().anyMatch(h -> request.getHeader(h) != null);
        return !forwarded || ips.trusts(request.getRemoteAddr());
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
