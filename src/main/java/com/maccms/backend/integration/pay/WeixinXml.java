package com.maccms.backend.integration.pay;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 旧 {@code mac_array2xml} 与 {@code mac_xml2array}（{@code application/common.php:681-705}）在微信支付里用到的部分（33 文档第 3.4 节）。
 * 值都是字节串（见 {@link PhpPay}）。
 *
 * <p>解析结果与 {@code json_decode(json_encode(simplexml_load_string($xml, …, LIBXML_NOCDATA)), true)} 一致：根元素名丢弃；
 * 没有子元素的元素取文本（CDATA 并入文本），文本为空时是空数组；有子元素的元素是下一级数组（其中的文本丢弃）；同名元素成为列表。
 * 新版拒绝 DOCTYPE（不解析任何实体与外部资源）和带属性的元素（旧版会多出 {@code @attributes} 键，微信的报文没有属性）——
 * 这两种情况与"不是 XML"一样按解析失败处理（PHP 7.3 下即验签失败，D-B24-5）。
 */
final class WeixinXml {

    /** 解析出的值：字节串，或下一级（键 → 值，列表的键是 0、1…）。空元素是空的下一级。 */
    sealed interface Value {

        record Text(String bytes) implements Value {
        }

        record Group(Map<String, Value> children) implements Value {
        }
    }

    private WeixinXml() {
    }

    /** {@code mac_array2xml}：一层；非数字值包在 CDATA 里，最后把控制字符（\x01-\x08、\x0b、\x0c、\x0e-\x1f）的连续段换成一个空格。 */
    static String toXml(Map<String, String> data) {
        StringBuilder s = new StringBuilder("<xml>");
        data.forEach((k, v) -> {
            boolean numeric = PhpPay.isNumeric(v);
            s.append('<').append(k).append('>').append(numeric ? "" : "<![CDATA[").append(v)
                    .append(numeric ? "" : "]]>").append("</").append(k).append('>');
        });
        return s.append("</xml>").toString().replaceAll("[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f]+", " ");
    }

    /** {@code mac_xml2array}；不是可接受的 XML（含空正文）时为空。 */
    static Optional<Map<String, Value>> parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            f.setCoalescing(true);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            Document doc = b.parse(new ByteArrayInputStream(xml));
            Value root = value(doc.getDocumentElement());
            return root instanceof Value.Group g ? Optional.of(g.children()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Value value(Element e) {
        if (e.hasAttributes()) {
            throw new IllegalArgumentException("attributes");
        }
        List<Element> kids = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        NodeList nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                kids.add((Element) n);
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(n.getNodeValue());
            }
        }
        if (kids.isEmpty()) {
            String t = text.toString();
            return t.isEmpty() ? new Value.Group(Map.of()) : new Value.Text(PhpPay.bytes(t));
        }
        Map<String, List<Value>> grouped = new LinkedHashMap<>();
        for (Element k : kids) {
            grouped.computeIfAbsent(PhpPay.bytes(k.getTagName()), x -> new ArrayList<>()).add(value(k));
        }
        Map<String, Value> out = new LinkedHashMap<>();
        grouped.forEach((name, list) -> {
            if (list.size() == 1) {
                out.put(name, list.get(0));
            } else {
                Map<String, Value> items = new LinkedHashMap<>();
                for (int i = 0; i < list.size(); i++) {
                    items.put(Integer.toString(i), list.get(i));
                }
                out.put(name, new Value.Group(items));
            }
        });
        return new Value.Group(out);
    }

    /** 字节串值；不是文本（空元素、下一级）时为 null。 */
    static String text(Map<String, Value> data, String key) {
        return data.get(key) instanceof Value.Text t ? t.bytes() : null;
    }

    /**
     * 微信签名（旧 {@code Weixin::makeSign}）：{@code array_filter} 去掉假值（空串、"0"、空数组）→ 按键排序 → {@code http_build_query} 再
     * {@code urldecode}（即 {@code k=v&…}，下一级写成 {@code k[子键]=v}）→ 加 {@code &key=<appkey>} → MD5 → 大写。
     */
    static String sign(Map<String, Value> data, String key) {
        Map<String, Value> kept = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            boolean falsy = v instanceof Value.Text t && (t.bytes().isEmpty() || t.bytes().equals("0"))
                    || v instanceof Value.Group g && g.children().isEmpty();
            if (!falsy) {
                kept.put(k, v);
            }
        });
        List<String> pairs = new ArrayList<>();
        PhpPay.ksort(kept).forEach((k, v) -> query(pairs, k, v));
        return PhpPay.md5(String.join("&", pairs) + "&key=" + key).toUpperCase(Locale.ROOT);
    }

    private static void query(List<String> pairs, String prefix, Value v) {
        if (v instanceof Value.Text t) {
            pairs.add(prefix + "=" + t.bytes());
        } else if (v instanceof Value.Group g) {
            g.children().forEach((k, child) -> query(pairs, prefix + "[" + k + "]", child));
        }
    }

    /** 一层文本的数据（下单参数）；空串按空元素处理（签名时同样被去掉）。 */
    static Map<String, Value> flat(Map<String, String> data) {
        Map<String, Value> out = new LinkedHashMap<>();
        data.forEach((k, v) -> out.put(k, v.isEmpty() ? new Value.Group(Map.of()) : new Value.Text(v)));
        return out;
    }
}
