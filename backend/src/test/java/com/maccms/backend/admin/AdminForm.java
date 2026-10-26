package com.maccms.backend.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * 浏览器里旧后台脚本提交表单时实际发出的字段（jQuery {@code $(form).serialize()}，B15 黄金比对用，24 文档第 10 节）：按文档顺序取表单里有
 * {@code name}、没有 {@code disabled} 的 input/select/textarea；跳过 submit/button/image/reset/file；复选框与单选框只取勾选的（同名单选框
 * 按 HTML 解析的规则只有最后一个 checked 生效）；单选下拉框取最后一个 selected 的选项，没有时取第一项；textarea 的值是文本（解析时去掉紧跟
 * 开始标签的一个换行）；值里的换行写成 \r\n。两边（PHP 黄金页面与新版页面）用同一个解析器（jsoup，HTML5 规则）取值后比较。
 */
public final class AdminForm {

    private AdminForm() {
    }

    /** 第 {@code index} 个表单的字段（名, 值），按提交顺序。没有该表单时为空。 */
    public static List<String[]> fields(String html, int index) {
        Document doc = Jsoup.parse(html);
        Elements forms = doc.select("form");
        if (forms.size() <= index) {
            return List.of();
        }
        Element form = forms.get(index);
        List<String[]> out = new ArrayList<>();
        Map<String, Integer> lastRadio = new LinkedHashMap<>();
        List<Boolean> isRadio = new ArrayList<>();
        for (Element el : form.select("input, select, textarea")) {
            String name = el.attr("name");
            if (name.isEmpty() || el.hasAttr("disabled")) {
                continue;
            }
            switch (el.tagName()) {
                case "input" -> {
                    String type = el.attr("type").toLowerCase(java.util.Locale.ROOT);
                    if (List.of("submit", "button", "image", "reset", "file").contains(type)) {
                        continue;
                    }
                    String value = el.hasAttr("value") ? el.attr("value") : (type.equals("checkbox") || type.equals("radio") ? "on" : "");
                    if (type.equals("radio")) {
                        if (el.hasAttr("checked")) {
                            lastRadio.put(name, out.size());
                            out.add(new String[] {name, value});
                            isRadio.add(true);
                        }
                        continue;
                    }
                    if (type.equals("checkbox") && !el.hasAttr("checked")) {
                        continue;
                    }
                    out.add(new String[] {name, crlf(value)});
                    isRadio.add(false);
                }
                case "select" -> {
                    Elements options = el.select("option");
                    Element chosen = null;
                    for (Element o : options) {
                        if (o.hasAttr("selected")) {
                            chosen = o;
                        }
                    }
                    if (chosen == null && !options.isEmpty() && !el.hasAttr("multiple")) {
                        chosen = options.first();
                    }
                    if (chosen != null) {
                        out.add(new String[] {name, chosen.hasAttr("value") ? chosen.attr("value") : chosen.text()});
                        isRadio.add(false);
                    }
                }
                default -> {
                    out.add(new String[] {name, crlf(el.wholeText())});
                    isRadio.add(false);
                }
            }
        }
        List<String[]> result = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            if (isRadio.get(i) && lastRadio.get(out.get(i)[0]) != i) {
                continue;
            }
            result.add(out.get(i));
        }
        return result;
    }

    /** 字段列表的文字形式（便于比较与输出差异）：每行 {@code 名=值}，令牌的值写成 {@code <token>}。 */
    public static String describe(List<String[]> fields) {
        StringBuilder sb = new StringBuilder();
        for (String[] f : fields) {
            sb.append(f[0]).append('=').append(f[0].equals("__token__") ? "<token>" : f[1].replace("\r", "\\r").replace("\n", "\\n"))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String crlf(String v) {
        return v.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
    }
}
