package com.maccms.backend.collect.application;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * 旧 {@code simplexml_load_string($html)}（无选项）在采集解析里用到的那部分语义（B18，26 文档第 3.2 节；{@code Collect.php:175-196}）。
 *
 * <p>安全：<b>拒绝任何 DOCTYPE</b>（{@link DoctypeRejectedException}），不解析 DTD、不加载外部实体或外部 DTD，所以不会有 XXE 与实体膨胀。
 * 旧版在 PHP 8 上靠 libxml 默认不加载外部实体（{@code libxml_disable_entity_loader} 在 PHP 8 已无效果），内部实体照常展开；新版一律拒绝
 * （D-B18-4）。元素最多嵌套 {@value #MAX_DEPTH} 层（libxml 不带 {@code XML_PARSE_HUGE} 时的上限，黄金用例 {@code vod-xml-deep-*}）。
 *
 * <p>与 SimpleXML 一致的取值（黄金用例 {@code vod-xml-*}）：
 * <ul>
 * <li>{@code $e->name}：第一个名为 name、<b>没有前缀</b>的子元素（无命名空间或默认命名空间都算，带前缀的不算）；</li>
 * <li>{@code (string)$e}：直接子节点里的文本与 CDATA 依次相接（不含注释、处理指令与子元素的文字）；</li>
 * <li>{@code $e['a']}、{@code $e->attributes()->a}：没有前缀的属性；</li>
 * <li>{@code empty($xml)}：根元素没有无前缀的属性、没有无前缀的子元素、没有文本（空白也算文本）、注释或处理指令时为真；CDATA 不算。</li>
 * </ul>
 */
public final class ProvideXml {

    static final int MAX_DEPTH = 257;

    /** 文件含 DOCTYPE。 */
    public static final class DoctypeRejectedException extends Exception {
        DoctypeRejectedException() {
            super("doctype", null, false, false);
        }
    }

    /** 不是格式正确的 XML。 */
    public static final class MalformedXmlException extends Exception {
        MalformedXmlException(String what) {
            super(what, null, false, false);
        }
    }

    /** 节点。 */
    public sealed interface Node permits Element, Text, Other {
    }

    /** 文本（{@code cdata} 为 CDATA 段）。 */
    public record Text(String value, boolean cdata) implements Node {
    }

    /** 注释或处理指令。 */
    public record Other() implements Node {
    }

    /** 属性。 */
    public record Attr(String prefix, String name, String value) {
    }

    /** 元素。 */
    public static final class Element implements Node {
        final String prefix;
        final String name;
        final List<Attr> attrs;
        final List<Node> children = new ArrayList<>();

        Element(String prefix, String name, List<Attr> attrs) {
            this.prefix = prefix;
            this.name = name;
            this.attrs = attrs;
        }

        /** {@code $e->name}：第一个同名的无前缀子元素，没有时 null。 */
        public Element child(String n) {
            for (Node c : children) {
                if (c instanceof Element e && e.prefix.isEmpty() && e.name.equals(n)) {
                    return e;
                }
            }
            return null;
        }

        /** {@code foreach ($e->name as ...)}：全部同名的无前缀子元素。 */
        public List<Element> children(String n) {
            List<Element> out = new ArrayList<>();
            for (Node c : children) {
                if (c instanceof Element e && e.prefix.isEmpty() && e.name.equals(n)) {
                    out.add(e);
                }
            }
            return out;
        }

        /** {@code (string)$e}。 */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Node c : children) {
                if (c instanceof Text t) {
                    sb.append(t.value());
                }
            }
            return sb.toString();
        }

        /** {@code (string)$e['a']}：没有时 null。 */
        public String attr(String n) {
            for (Attr a : attrs) {
                if (a.prefix().isEmpty() && a.name().equals(n)) {
                    return a.value();
                }
            }
            return null;
        }

        /** SimpleXML 对象的 {@code empty()}（见类注释）。 */
        public boolean isEmptyObject() {
            for (Attr a : attrs) {
                if (a.prefix().isEmpty()) {
                    return false;
                }
            }
            for (Node c : children) {
                if (c instanceof Element e && e.prefix.isEmpty() || c instanceof Text t && !t.cdata() || c instanceof Other) {
                    return false;
                }
            }
            return true;
        }
    }

    private ProvideXml() {
    }

    /** 解析出根元素。 */
    public static Element parse(byte[] xml) throws DoctypeRejectedException, MalformedXmlException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXMLResolver((publicId, systemId, base, ns) -> {
            throw new XMLStreamException("external entity");
        });
        try {
            f.setProperty("http://java.sun.com/xml/stream/properties/report-cdata-event", true);
        } catch (IllegalArgumentException ignored) {
            // 不支持时 CDATA 报成普通文本：只影响 empty() 的判断
        }
        XMLStreamReader r = null;
        try {
            r = f.createXMLStreamReader(new ByteArrayInputStream(xml));
            Deque<Element> stack = new ArrayDeque<>();
            Element root = null;
            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.DTD -> throw new DoctypeRejectedException();
                    case XMLStreamConstants.ENTITY_REFERENCE -> throw new MalformedXmlException("entity");
                    case XMLStreamConstants.START_ELEMENT -> {
                        if (stack.size() >= MAX_DEPTH) {
                            throw new MalformedXmlException("depth");
                        }
                        List<Attr> attrs = new ArrayList<>();
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            attrs.add(new Attr(nz(r.getAttributePrefix(i)), r.getAttributeLocalName(i), r.getAttributeValue(i)));
                        }
                        Element e = new Element(nz(r.getPrefix()), r.getLocalName(), attrs);
                        if (stack.isEmpty()) {
                            root = e;
                        } else {
                            stack.peek().children.add(e);
                        }
                        stack.push(e);
                    }
                    case XMLStreamConstants.END_ELEMENT -> stack.pop();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty()) {
                            stack.peek().children.add(new Text(r.getText(), false));
                        }
                    }
                    case XMLStreamConstants.CDATA -> {
                        if (!stack.isEmpty()) {
                            stack.peek().children.add(new Text(r.getText(), true));
                        }
                    }
                    case XMLStreamConstants.COMMENT, XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                        if (!stack.isEmpty()) {
                            stack.peek().children.add(new Other());
                        }
                    }
                    default -> {
                    }
                }
            }
            if (root == null) {
                throw new MalformedXmlException("no root");
            }
            return root;
        } catch (XMLStreamException | RuntimeException e) {
            throw new MalformedXmlException("syntax");
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignored) {
                    // 已读完或已失败
                }
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
