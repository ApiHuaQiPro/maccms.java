package com.maccms.backend.content.application;

import com.maccms.backend.content.application.AdminTable.Column;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 旧版 {@code allowField(true)->insert/update($data)} 的取值规则（B13，22 文档第 4.2 节）：
 * <ul>
 * <li>只取 {@code $data} 中表里有的列（ThinkPHP 按表结构过滤，{@code fields_strict=false}），按出现顺序；</li>
 * <li>数值列（整数、decimal）的空串写 0（ThinkPHP 按列类型以 {@code PDO::PARAM_INT} 绑定，空串转为 0）；其余原样作为字符串交给 MySQL，
 * 由 MySQL 按列类型转换（{@code '1.5'} → 2、{@code ' 7'} → 7、{@code '1e2'} → 100，与旧版经 PDO 绑定的结果相同）；</li>
 * <li>新版另外先拒绝旧版会在 MySQL 严格模式下报错（旧版整页 500）的取值：数组、非法 UTF-8、4 字节字符（表是 3 字节的 utf8）、
 * 超过列长度的字符串（CHAR/VARCHAR 按字符，TEXT/MEDIUMTEXT 按字节）——返回"参数错误"（D-B13-3）。数值列的非法取值由 MySQL 报错，
 * 写入方把这类错误同样归为"参数错误"（{@link AdminWriteException}）。</li>
 * </ul>
 */
public final class AdminRowValues {

    private AdminRowValues() {
    }

    /**
     * {@code $data} 中表里有的列 → 写给 MySQL 的字符串。
     *
     * @param skip 不写的列（如新增时的空主键）
     * @throws AdminRejectedException 有不合规的取值
     */
    public static Map<String, String> of(AdminTable table, PhpForm data, Set<String> skip) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : data.names()) {
            Column c = table.column(name);
            if (c == null || skip.contains(name)) {
                continue;
            }
            if (data.isArray(name) || data.isNull(name)) {
                // 数组：旧版按 ThinkPHP 表达式解释（可注入 SQL）或报错；null：NOT NULL 列在严格模式下报错（旧版 500）
                throw AdminRejectedException.paramError();
            }
            out.put(name, value(c, data.scalar(name)));
        }
        return out;
    }

    /** 一列的取值（规则见类说明）。 */
    public static String value(Column c, byte[] raw) {
        if (c.numeric() && raw.length == 0) {
            return "0";
        }
        String s = utf8(raw);
        if (s == null) {
            throw AdminRejectedException.paramError();
        }
        switch (c.kind()) {
            case CHAR -> {
                if (s.codePointCount(0, s.length()) > c.length()) {
                    throw AdminRejectedException.paramError();
                }
            }
            case TEXT -> {
                if (raw.length > c.length()) {
                    throw AdminRejectedException.paramError();
                }
            }
            default -> {
            }
        }
        return s;
    }

    /** 合法 UTF-8 且没有 4 字节字符时的文字，否则 null。 */
    static String utf8(byte[] raw) {
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isSurrogate(s.charAt(i))) {
                return null;
            }
        }
        return s;
    }
}
