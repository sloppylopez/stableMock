package com.stablemock.core.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for parsing XML request bodies.
 * Provides methods to parse XML strings into DOM documents and extract element values.
 */
public final class XmlBodyParser {

    private static final Logger logger = LoggerFactory.getLogger(XmlBodyParser.class);
    private static final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();

    static {
        // Disable external entity expansion to prevent XXE attacks
        try {
            documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            documentBuilderFactory.setXIncludeAware(false);
            documentBuilderFactory.setExpandEntityReferences(false);
        } catch (Exception e) {
            logger.warn("Failed to configure XML parser security features: {}", e.getMessage());
        }
    }

    private XmlBodyParser() {
        // utility class
    }

    /**
     * Silent error handler that suppresses XML parsing errors to stderr.
     */
    private static final ErrorHandler SILENT_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // Suppress warnings
        }

        @Override
        public void error(SAXParseException exception) {
            // Suppress errors
        }

        @Override
        public void fatalError(SAXParseException exception) {
            // Suppress fatal errors
        }
    };

    /**
     * Parses an XML string into a Document.
     * 
     * @param xml The XML string to parse
     * @return The parsed Document, or null if parsing fails
     */
    public static Document parseXml(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return null;
        }

        try {
            DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
            builder.setErrorHandler(SILENT_ERROR_HANDLER);
            return builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        } catch (Exception e) {
            logger.debug("Failed to parse XML: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Checks if a string is likely XML based on content.
     * 
     * @param content The content to check
     * @return true if content appears to be XML
     */
    public static boolean isXml(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.startsWith("<") && trimmed.endsWith(">");
    }

    /**
     * Checks if a Content-Type header indicates XML.
     * 
     * @param contentType The Content-Type header value
     * @return true if Content-Type indicates XML
     */
    public static boolean isXmlContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.contains("xml") || lower.contains("application/xml") || 
               lower.contains("text/xml") || lower.contains("application/soap+xml");
    }

    /**
     * Extracts all element values and attributes from an XML document as a flat map.
     * Keys are XPath-like paths (e.g., "root/child/grandchild" for elements, "root/child@attr" for attributes).
     * 
     * @param doc The XML document
     * @return Map of element/attribute paths to their values
     */
    public static Map<String, String> extractElementValues(Document doc) {
        Map<String, String> values = new HashMap<>();
        if (doc == null || doc.getDocumentElement() == null) {
            return values;
        }
        extractElementValuesRecursive(doc.getDocumentElement(), "", values);
        return values;
    }

    private static void extractElementValuesRecursive(Element element, String currentPath, Map<String, String> values) {
        if (element == null) {
            return;
        }

        String elementName = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
        String newPath = currentPath.isEmpty() ? elementName : currentPath + "/" + elementName;

        // Extract attributes
        if (element.hasAttributes()) {
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attr = attributes.item(i);
                if (attr.getNodeType() == Node.ATTRIBUTE_NODE) {
                    String attrName = attr.getLocalName() != null ? attr.getLocalName() : attr.getNodeName();
                    String attrPath = newPath + "@" + attrName;
                    String attrValue = attr.getNodeValue();
                    if (attrValue != null) {
                        values.put(attrPath, attrValue);
                    }
                }
            }
        }

        // Get text content (direct text nodes only, not nested elements)
        NodeList childNodes = element.getChildNodes();
        StringBuilder textContent = new StringBuilder();
        boolean hasElementChildren = false;

        for (int i = 0; i < childNodes.getLength(); i++) {
            Node node = childNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                hasElementChildren = true;
                extractElementValuesRecursive((Element) node, newPath, values);
            } else if (node.getNodeType() == Node.TEXT_NODE) {
                String text = node.getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    textContent.append(text.trim());
                }
            }
        }

        // Only store value if element has no element children (leaf node)
        if (!hasElementChildren && textContent.length() > 0) {
            values.put(newPath, textContent.toString());
        }
    }

    /**
     * Gets all element paths from multiple XML documents.
     * 
     * @param xmlBodies List of XML body strings
     * @return List of maps, each containing element paths to values for one document
     */
    public static List<Map<String, String>> parseAllXmlBodies(List<String> xmlBodies) {
        List<Map<String, String>> result = new ArrayList<>();
        for (String xmlBody : xmlBodies) {
            Document doc = parseXml(xmlBody);
            if (doc != null) {
                result.add(extractElementValues(doc));
            }
        }
        return result;
    }
}

