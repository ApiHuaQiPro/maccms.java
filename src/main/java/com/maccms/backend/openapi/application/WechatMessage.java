package com.maccms.backend.openapi.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 公众号推送的消息 XML（B27，29 文档第 4.3 节）。等价旧版 {@code simplexml_load_string($postStr, 'SimpleXMLElement', LIBXML_NOCDATA)}
 * 之后的 {@code $postObj->字段}：根元素下第一个同名子元素的直接文本（文本与 CDATA 段按顺序拼接，不含下级元素的文本）；没有该元素时为空串。
 *
 * <p>解析器禁止 DTD（{@code disallow-doctype-decl}），也就不会展开任何实体、不会读取外部资源（XXE）；另外关闭外部实体、外部 DTD 与
 * XInclude，并打开安全处理限制。带 DOCTYPE 的正文与不合法的 XML 一样按"解析失败"处理（D-B27-3）。
 */
public final class WechatMessage {

    private final Element root;

    private WechatMessage(Element root) {
        this.root = root;
    }

    /** 解析正文；不是合法 XML（含带 DOCTYPE）时为空。 */
    public static Optional<WechatMessage> parse(byte[] body) {
        DocumentBuilder b;
        try {
            b = factory().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 解析器不支持安全设置", e);
        }
        try {
            // 默认的错误处理会把解析错误写到标准错误输出
            b.setErrorHandler(new DefaultHandler());
            b.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("external entity");
            });
            Document doc = b.parse(new ByteArrayInputStream(body));
            Element root = doc.getDocumentElement();
            return root == null ? Optional.empty() : Optional.of(new WechatMessage(root));
        } catch (SAXException | IOException e) {
            return Optional.empty();
        }
    }

    private static DocumentBuilderFactory factory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 解析器不支持安全设置", e);
        }
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(false);
        return f;
    }

    /** {@code (string)$postObj->name}。 */
    public String field(String name) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                StringBuilder sb = new StringBuilder();
                for (Node t = n.getFirstChild(); t != null; t = t.getNextSibling()) {
                    if (t.getNodeType() == Node.TEXT_NODE || t.getNodeType() == Node.CDATA_SECTION_NODE) {
                        sb.append(t.getNodeValue());
                    }
                }
                return sb.toString();
            }
        }
        return "";
    }
}
