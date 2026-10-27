package com.maccms.backend.collect.application;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.PhpText;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Receive 入站接口（旧 {@code api/controller/Receive.php}；B19，27 文档第 5 节）：{@code /api.php/receive/<操作>}。
 *
 * <p>构造函数的三道检查照旧且顺序不变：{@code interface.status != 1} → 3001；{@code interface.pass != 提交的 pass}（宽松比较）→ 3002；
 * {@code strlen(interface.pass) < 16} → 3003。参数是 ThinkPHP {@code input('', '', 'trim,urldecode')}：GET、POST、路径参数合并后每个值
 * 先 {@code trim} 再 {@code urldecode}，与查询表达式同名的值末尾补一个空格（{@code Request::filterExp}）。本增量实现 {@code vod}；
 * {@code index} 照旧输出空白；其他操作返回"由 B19 后续增量提供"（27 文档第 1 节）。
 */
public class ReceiveService {

    private static final Pattern EXP = Pattern.compile("(?i)^(EXP|NEQ|GT|EGT|LT|ELT|OR|XOR|LIKE|NOTLIKE|NOT LIKE|NOT BETWEEN|NOTBETWEEN"
            + "|BETWEEN|NOT EXISTS|NOTEXISTS|EXISTS|NOT NULL|NOTNULL|NULL|BETWEEN TIME|NOT BETWEEN TIME|NOTBETWEEN TIME|NOTIN|NOT IN|IN)$");
    private static final List<String> LATER = List.of("art", "actor", "role", "website", "comment");

    private final CollectIngestService ingest;

    public ReceiveService(CollectIngestService ingest) {
        this.ingest = ingest;
    }

    /** 旧版的响应头（入口文件与 ThinkPHP 响应都是 text/html、UTF-8）。 */
    static final String HTML = "text/html; charset=utf-8";

    /** 响应：{@code status} 为 HTTP 状态码，{@code body} 为正文（UTF-8）。 */
    public record Response(int status, String contentType, String body) {
    }

    /**
     * @param action 操作名（路径中 {@code receive/} 之后的一段，小写）
     * @param params GET、POST 与路径参数合并后的原始值（{@link PhpInput#merge}，尚未 trim、urldecode）
     * @return 不认识的操作为 empty（404）
     */
    public Optional<Response> handle(String action, Map<String, PhpInput.Value> params) {
        if (!action.equals("index") && !action.equals("vod") && !LATER.contains(action)) {
            return Optional.empty();
        }
        Map<Object, Object> maccms = ingest.maccms();
        UnaryOperator<String> lang = lang(maccms);
        Object iface = ProvideValues.get(maccms, "interface");
        if (!VodIngest.equalsOne(ProvideValues.get(iface, "status"))) {
            return json(3001, lang.apply("api/close_err"));
        }
        Map<String, Object> info;
        try {
            info = input(params);
        } catch (IllegalArgumentException e) {
            return json(1001, lang.apply("param_err"));
        }
        Object pass = ProvideValues.get(iface, "pass");
        if (!looseEquals(pass, info.get("pass"))) {
            return json(3002, lang.apply("api/pass_err"));
        }
        if (IngestPhp.str(pass).getBytes(StandardCharsets.UTF_8).length < 16) {
            return json(3003, lang.apply("api/pass_safe_err"));
        }
        return switch (action) {
            case "index" -> Optional.of(new Response(200, HTML, ""));
            case "vod" -> vod(info, maccms, lang);
            default -> Optional.of(new Response(501, "text/plain; charset=utf-8", "由 B19 后续增量提供"));
        };
    }

    private Optional<Response> vod(Map<String, Object> info, Map<Object, Object> maccms, UnaryOperator<String> lang) {
        if (IngestPhp.empty(info.get("vod_name"))) {
            return json(2001, lang.apply("api/require_name"));
        }
        if (IngestPhp.empty(info.get("type_id")) && IngestPhp.empty(info.get("type_name"))) {
            return json(2002, lang.apply("api/require_type"));
        }
        if (IngestPhp.empty(info.get("type_id"))) {
            info.put("type_id", interfaceTypes(maccms, "vodtype", ingest.typeIdsByName()).get(ProvideValues.key(IngestPhp.str(info.get("type_name")))));
        }
        Map<Object, Object> row = new LinkedHashMap<>();
        info.forEach(row::put);
        try {
            VodIngest.Outcome o = ingest.vod(row, VodIngest.Param.NONE, lang);
            return json(o.green() ? 1 : 1001, o.des());
        } catch (VodIngest.Rejected e) {
            return json(1001, lang.apply("param_err"));
        } catch (CollectIngestService.Busy e) {
            return Optional.of(new Response(503, HTML, encode(1001, "入库排队超时，请稍后重试")));
        }
    }

    /**
     * 旧 {@code mac_interface_type()[$kind]}（application/common.php:816-875）：配置值里的换行去掉、回车换成 {@code #}，按 {@code #} 拆开，
     * 每项 {@code 本站分类名=接口分类名} 记为 接口分类名 → 本站分类名（没有 {@code =} 时接口分类名为空串；同名时后者覆盖），再把本站分类名换成
     * 分类编号（{@code (int)$type_names[$name]}，没有这个分类时 0）。旧版把第一步的结果放在缓存里，配置改动后要等缓存失效；新版每次按当前配置
     * 计算（D-B19-7）。
     */
    static Map<Object, Object> interfaceTypes(Map<Object, Object> maccms, String kind, Map<String, Long> typeIdsByName) {
        String text = IngestPhp.str(ProvideValues.get(ProvideValues.get(maccms, "interface"), kind)).replace("\n", "").replace("\r", "#");
        Map<Object, Object> out = new LinkedHashMap<>();
        for (String item : IngestPhp.explode("#", text)) {
            List<String> p = IngestPhp.explode("=", item);
            Long id = typeIdsByName.get(p.get(0));
            out.put(ProvideValues.key(p.size() > 1 ? p.get(1) : ""), id == null ? 0L : id);
        }
        return out;
    }

    /** {@code input('', '', 'trim,urldecode')}；数组参数与非法 UTF-8 拒绝（D-B19-3）。 */
    static Map<String, Object> input(Map<String, PhpInput.Value> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, PhpInput.Value> e : params.entrySet()) {
            if (e.getValue().array()) {
                throw new IllegalArgumentException("数组参数");
            }
            byte[] v = PhpText.urldecode(PhpText.trim(e.getValue().bytes()));
            String s = utf8(v);
            if (EXP.matcher(s).matches()) {
                s += " ";
            }
            out.put(e.getKey(), s);
        }
        return out;
    }

    private static String utf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("非法 UTF-8");
        }
    }

    /** {@code $config['pass'] != $param['pass']}（PHP 8 宽松比较；没有提交时为 null）。 */
    private static boolean looseEquals(Object configured, Object submitted) {
        if (submitted == null) {
            return IngestPhp.empty(configured) && !(configured instanceof String s && s.equals("0"));
        }
        return IngestPhp.looseEquals(configured, IngestPhp.str(submitted));
    }

    private static UnaryOperator<String> lang(Map<Object, Object> maccms) {
        LegacyLang pack = LegacyLang.select(IngestPhp.str(ProvideValues.get(ProvideValues.get(maccms, "app"), "lang"))).pack();
        return pack::get;
    }

    /** {@code echo json_encode([...], JSON_UNESCAPED_UNICODE)}。 */
    private static Optional<Response> json(int code, String msg) {
        return Optional.of(new Response(200, HTML, encode(code, msg)));
    }

    private static String encode(int code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("msg", msg);
        return LegacyJson.encode(m);
    }
}
