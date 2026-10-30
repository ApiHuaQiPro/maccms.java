package com.maccms.backend.openapi.application;

import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.content.application.view.LegacyTypeDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * 视频 XML（旧 {@code vod_xml}，api/controller/Provide.php:267-342；播放组 {@code vod_url_deal}，:155-177）。元素顺序、CDATA 位置与
 * 旧版逐字节相同。与旧版的差异（16 文档第 3.5 节）：非 CDATA 文本与属性值做 XML 转义（{@code & < > "}），CDATA 中的 {@code ]]>}
 * 拆成 {@code ]]]]><![CDATA[>}，XML 1.0 不允许的控制字符去掉——旧版原样拼接，含这些字符的数据会产生不合法的 XML。
 */
final class ProvideXml {

    private ProvideXml() {
    }

    static String vod(LegacyRowPageDto page, String pageText, long pageCount, String limitText, boolean full, String from,
                      Map<Integer, LegacyTypeDto> typeById, List<LegacyTypeDto> types, Set<Integer> typeFilter,
                      Function<String, String> pic, LongFunction<String> datetime) {
        StringBuilder x = new StringBuilder(1024);
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        x.append("<rss version=\"5.1\">");
        x.append("<list page=\"").append(attr(pageText)).append("\" pagecount=\"").append(pageCount)
                .append("\" pagesize=\"").append(attr(limitText)).append("\" recordcount=\"").append(page.total())
                .append("\">");
        for (LegacyRowDto v : page.rows()) {
            LegacyTypeDto type = typeById.get((int) v.number("type_id"));
            x.append("<video>");
            x.append("<last>").append(datetime.apply(v.number("vod_time"))).append("</last>");
            x.append("<id>").append(v.number("vod_id")).append("</id>");
            x.append("<tid>").append(v.number("type_id")).append("</tid>");
            x.append("<name>").append(cdata(v.string("vod_name"))).append("</name>");
            x.append("<type>").append(text(type == null ? "" : type.row().string("type_name"))).append("</type>");
            if (full) {
                x.append("<pic>").append(text(pic.apply(v.string("vod_pic")))).append("</pic>");
                x.append("<lang>").append(text(v.string("vod_lang"))).append("</lang>");
                x.append("<area>").append(text(v.string("vod_area"))).append("</area>");
                x.append("<year>").append(text(v.string("vod_year"))).append("</year>");
                x.append("<state>").append(text(v.string("vod_serial"))).append("</state>");
                x.append("<note>").append(cdata(v.string("vod_remarks"))).append("</note>");
                x.append("<actor>").append(cdata(v.string("vod_actor"))).append("</actor>");
                x.append("<director>").append(cdata(v.string("vod_director"))).append("</director>");
                x.append("<dl>").append(urlGroups(v.string("vod_play_url"), v.string("vod_play_from"), from)).append("</dl>");
                x.append("<des>").append(cdata(v.string("vod_content"))).append("</des>");
            } else {
                String dt = !from.isEmpty() ? from : v.string("vod_play_from").replace("$$$", ",");
                x.append("<dt>").append(text(dt)).append("</dt>");
                x.append("<note>").append(cdata(v.string("vod_remarks"))).append("</note>");
            }
            x.append("</video>");
        }
        x.append("</list>");
        if (!full) {
            x.append("<class>");
            for (LegacyTypeDto t : types) {
                if (!t.visible() || t.mid() != 1 || (!typeFilter.isEmpty() && !typeFilter.contains(t.id()))) {
                    continue;
                }
                x.append("<ty id=\"").append(t.id()).append("\">").append(text(t.row().string("type_name"))).append("</ty>");
            }
            x.append("</class>");
        }
        x.append("</rss>");
        return x.toString();
    }

    /** {@code vod_url_deal($urls, $froms, $from, 'xml')}：from 非空时只保留等于它或是它的子串的播放组。 */
    static String urlGroups(String urls, String froms, String from) {
        String[] arr1 = urls.split("\\$\\$\\$", -1);
        String[] arr2 = froms.split("\\$\\$\\$", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr2.length; i++) {
            // 旧版 if ($arr1count >= $i)：播放组比地址组多出两个以上时，多出的组不输出
            if (arr1.length < i) {
                continue;
            }
            if (!from.isEmpty() && !(arr2[i].equals(from) || from.contains(arr2[i]))) {
                continue;
            }
            String url = i < arr1.length ? arr1[i] : "";
            sb.append("<dd flag=\"").append(attr(arr2[i])).append("\">").append(cdata(url)).append("</dd>");
        }
        return sb.toString();
    }

    static String cdata(String s) {
        return "<![CDATA[" + legal(s).replace("]]>", "]]]]><![CDATA[>") + "]]>";
    }

    static String text(String s) {
        return legal(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String attr(String s) {
        return text(s).replace("\"", "&quot;");
    }

    /** 去掉 XML 1.0 不允许的字符（制表、换行、回车以外的 C0 控制字符，以及不成对的代理项与 U+FFFE/U+FFFF）。 */
    static String legal(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok;
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                if (sb != null) {
                    sb.append(c).append(s.charAt(i + 1));
                }
                i++;
                continue;
            }
            ok = c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD);
            if (!ok && sb == null) {
                sb = new StringBuilder(s.length()).append(s, 0, i);
            }
            if (sb != null && ok) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }
}
