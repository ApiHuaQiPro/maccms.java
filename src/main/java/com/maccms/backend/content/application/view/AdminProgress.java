package com.maccms.backend.content.application.view;

import java.util.List;
import java.util.Map;

/**
 * 批量处理、附件检查等逐页推进的输出（旧 {@code mac_echo} 逐行输出、{@code mac_jump} 3 秒后跳到下一页，B13 22 文档第 8.4 节）。
 *
 * @param lines     依次输出的各段（每段由后台按 {@code mac_echo} 输出为"段 + <br>"）
 * @param jumpQuery 下一页的查询参数（{@code url('x/动作') . '?' . http_build_query($param)}）；空表表示跳到不带参数的
 *                  {@code url('x/动作')}；null 表示不跳转
 * @param error     中途按 {@code error()} 结束时的提示（旧版在已输出的内容后面接着输出错误页或 JSON）；没有时为 null
 */
public record AdminProgress(List<Line> lines, Map<String, String> jumpQuery, AdminMessage error) {

    public AdminProgress {
        lines = List.copyOf(lines);
    }

    /** 一段输出：依次拼接的片段。 */
    public record Line(List<Part> parts) {

        public Line {
            parts = List.copyOf(parts);
        }

        public static Line of(Part... parts) {
            return new Line(List.of(parts));
        }
    }

    /** 片段：{@code markup} 为真时是旧代码里写死的 HTML（如 {@code <font color=red>}、{@code &nbsp;}），否则是要转义输出的文字。 */
    public record Part(String value, boolean markup) {

        public static Part text(String v) {
            return new Part(v, false);
        }

        public static Part markup(String v) {
            return new Part(v, true);
        }
    }
}
