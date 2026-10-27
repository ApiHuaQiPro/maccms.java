package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.LegacyPhp;
import com.maccms.backend.admin.application.PhpHtmlEntities;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * B15 后台表单的公共部分（24 文档第 4 节）：
 * <ul>
 * <li>{@link #input}：旧 {@code input()}（GET + POST + 路径参数，后者覆盖前者）按 PHP {@code parse_str} 还原成多维数组；
 * {@code htmlentities} 为真时每个值先经 {@link PhpHtmlEntities#encode}（旧 {@code input('', '', 'htmlentities')}）。值必须是合法 UTF-8
 * （经 htmlentities 的不合法值按 PHP 变成空串），否则 {@link BadInput}。</li>
 * <li>{@link #issue}/{@link #check}：旧 {@code {$Request.token}} 与 {@code \think\Loader::validate('Token')}（同 B12 管理员表单：缺失 →
 * "非法请求"，不一致 → "请不要重复提交表单"，令牌随之作废）。签发令牌时可以同时记下页面数据的版本（B15 防止后保存的一方无声覆盖），
 * 校验通过时一并取出。</li>
 * </ul>
 */
final class AdminForms {

    static final String VERSION = "admin.form.version";

    private AdminForms() {
    }

    /** 参数不能表示成旧配置的值（不是合法 UTF-8）。 */
    static final class BadInput extends RuntimeException {
        BadInput() {
            super("BadInput", null, false, false);
        }
    }

    /** 令牌校验的结果：{@code error} 为语言包键（通过时为 null），{@code version} 为签发时记下的版本（没有时为 null）。 */
    record TokenCheck(String error, String version) {
    }

    static PhpArray input(AdminParams params, boolean htmlentities) {
        LinkedHashMap<Object, PhpValue> out = new LinkedHashMap<>();
        for (Map.Entry<String, AdminParams.Value> e : params.values().entrySet()) {
            Object nested = params.nested(e.getKey());
            out.put(PhpArray.normalizeKey(e.getKey()), value(nested, htmlentities));
        }
        return new PhpArray(out);
    }

    @SuppressWarnings("unchecked")
    private static PhpValue value(Object v, boolean htmlentities) {
        if (v instanceof byte[] b) {
            byte[] x = htmlentities ? PhpHtmlEntities.encode(b) : b;
            String s = LegacyPhp.utf8(x);
            if (s == null) {
                throw new BadInput();
            }
            return new PhpValue.PhpString(s);
        }
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
            m.put(PhpArray.normalizeKey(e.getKey()), value(e.getValue(), htmlentities));
        }
        return new PhpArray(m);
    }

    /** 签发新的表单令牌（覆盖会话里的旧令牌），并记下页面数据的版本（可为 null）。 */
    static String issue(SessionState session, RandomGenerator random, String version) {
        byte[] b = new byte[16];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        session.put(AdminSessions.TOKEN, token);
        if (version == null) {
            session.take(VERSION, String.class);
        } else {
            session.put(VERSION, version);
        }
        return token;
    }

    /** 校验 {@code input()} 里的 {@code __token__}。 */
    static TokenCheck check(SessionState session, AdminParams params) {
        byte[] posted = params.scalar("__token__");
        if (posted == null || posted.length == 0) {
            return new TokenCheck("illegal_request", null);
        }
        String expected = session.take(AdminSessions.TOKEN, String.class).orElse(null);
        String version = session.take(VERSION, String.class).orElse(null);
        if (expected == null || !expected.equals(new String(posted, StandardCharsets.ISO_8859_1))) {
            return new TokenCheck("token_err", null);
        }
        return new TokenCheck(null, version);
    }
}
