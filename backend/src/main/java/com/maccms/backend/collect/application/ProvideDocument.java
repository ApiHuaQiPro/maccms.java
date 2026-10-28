package com.maccms.backend.collect.application;

import java.util.List;
import java.util.Map;

/**
 * 一份 Provide 文件的解析结果，与分类绑定无关（B18，26 文档第 3 节）。绑定在每次预览时按当前的 {@code bind.php} 换算
 * （{@link ProvideParser#bind}），所以本对象可以缓存。
 *
 * @param kind      资源种类
 * @param format    {@code "json"} 或 {@code "xml"}（旧结果的 {@code msg}：视频为 json/xml，其他为 ok）
 * @param code      1 成功；1001 内容为空（旧 {@code get_html_err}）；1002 解析失败（旧 {@code json_err}/{@code xml_err}）
 * @param errorKey  失败时的语言包键
 * @param detail    失败的原因（新版附加的说明，可为空串）
 * @param page      旧结果的 {@code page}（不含 {@code url}）
 * @param types     旧结果的 {@code type}（远程分类，未换算绑定）；{@code null} 表示旧结果没有这个键（角色、评论）
 * @param rows      数据行
 */
public record ProvideDocument(ProvideKind kind, String format, int code, String errorKey, String detail, Map<Object, Object> page,
                              List<Map<Object, Object>> types, List<Row> rows) {

    /**
     * 一行数据。
     *
     * @param key    行在旧结果 {@code data} 里的键（JSON 的 {@code list} 是对象时保留它的键，旧版 {@code foreach($json['list'] as $key=>$v)}）
     * @param remote 远程分类编号的字符串形式（绑定键 {@code cjflag . '_' . remote}）；不换算绑定的种类为 null
     * @param data   行（数组，或角色、评论里原样保留的标量）；换算绑定的种类里 {@code type_id} 已占好位置
     */
    public record Row(Object key, String remote, Object data) {
    }

    public boolean ok() {
        return code == 1;
    }

    static ProvideDocument failure(ProvideKind kind, String format, int code, String key, String detail) {
        return new ProvideDocument(kind, format, code, key, detail, Map.of(), List.of(), List.of());
    }
}
