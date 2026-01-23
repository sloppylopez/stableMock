package com.stablemock.core.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects dynamic fields in XML request bodies by comparing multiple requests.
 * Identifies XML elements whose values change across different executions.
 */
public final class XmlFieldDetector {

    private static final Logger logger = LoggerFactory.getLogger(XmlFieldDetector.class);

    private XmlFieldDetector() {
        // utility class
    }

    /**
     * Analyzes XML request bodies to detect dynamic fields.
     * 
     * @param xmlBodies List of XML body strings from the same endpoint
     * @param result DetectionResult to populate with findings
     */
    public static void detectDynamicFieldsInXml(List<String> xmlBodies, DetectionResult result) {
        if (xmlBodies == null || xmlBodies.size() < 2) {
            logger.debug("Not enough XML bodies to analyze (need at least 2, got {})", 
                    xmlBodies != null ? xmlBodies.size() : 0);
            return;
        }

        // Parse all XML bodies into element value maps
        List<Map<String, String>> elementValueMaps = XmlBodyParser.parseAllXmlBodies(xmlBodies);
        
        if (elementValueMaps.size() < 2) {
            logger.debug("Failed to parse enough XML bodies for analysis");
            return;
        }

        // Get all unique element paths across all documents
        Set<String> allPaths = new LinkedHashSet<>();
        for (Map<String, String> elementMap : elementValueMaps) {
            allPaths.addAll(elementMap.keySet());
        }

        // Analyze each element path
        for (String path : allPaths) {
            List<String> values = new ArrayList<>();
            
            // Collect values for this path from all documents
            for (Map<String, String> elementMap : elementValueMaps) {
                String value = elementMap.get(path);
                if (value != null) {
                    values.add(value);
                }
            }

            // Need at least 2 values to compare
            if (values.size() >= 2) {
                // Check if values are all the same
                boolean allSame = true;
                String firstValue = values.get(0);
                for (int i = 1; i < values.size(); i++) {
                    if (!firstValue.equals(values.get(i))) {
                        allSame = false;
                        break;
                    }
                }

                if (!allSame) {
                    // This element has changing values - it's dynamic!
                    List<String> sampleValues = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, values.size()); i++) {
                        sampleValues.add(values.get(i));
                    }

                    // Generate XPath pattern for WireMock XML matching
                    // Handle both elements and attributes
                    String xpathPattern;
                    if (path.contains("@")) {
                        // Attribute: root/child@attr -> //root/child/@attr
                        String[] parts = path.split("@");
                        String elementPath = parts[0];
                        String attrName = parts[1];
                        // Extract local name from attribute name (may have prefix)
                        String localAttrName = extractLocalName(attrName);
                        xpathPattern = buildElementPathXPath(elementPath)
                                + "/@*[local-name()='" + localAttrName + "']";
                    } else {
                        // Element: use full path to avoid over-broad matches
                        xpathPattern = buildElementPathXPath(path);
                    }
                    String xmlPath = "xml:" + xpathPattern;

                    result.addDynamicField(new DetectionResult.DynamicField(
                            xmlPath, sampleValues));
                    result.addIgnorePattern(xmlPath);

                    logger.info("Detected dynamic XML field: {} (samples: {})",
                            xmlPath, sampleValues.size());
                }
            }
        }
    }

    /**
     * Extracts the element name from an XPath-like path.
     * For "root/child/grandchild", returns "grandchild".
     * 
     * IMPORTANT: Strips namespace prefixes from element names before using in local-name().
     * The local-name() XPath function returns only the local part (without prefix),
     * so we must extract the local name from qualified names like "ns4:RequestElement".
     * 
     * @param path The element path (may contain namespace prefixes like "ns4:Element")
     * @return XPath expression using local-name() with prefixes stripped
     */
    private static String buildElementPathXPath(String path) {
        if (path == null || path.isEmpty()) {
            return "//*";
        }
        String[] elementParts = path.split("/");
        StringBuilder xpath = new StringBuilder();
        for (String part : elementParts) {
            if (part.isEmpty()) {
                continue;
            }
            // Extract local name (remove namespace prefix if present)
            // Example: "ns4:RequestElement" -> "RequestElement"
            // Example: "SOAP-ENV:Envelope" -> "Envelope"
            String localName = extractLocalName(part);
            if (xpath.length() == 0) {
                xpath.append("//*[local-name()='").append(localName).append("']");
            } else {
                xpath.append("/*[local-name()='").append(localName).append("']");
            }
        }
        return xpath.length() == 0 ? "//*" : xpath.toString();
    }

    /**
     * Extracts the local name from a qualified name, removing any namespace prefix.
     * 
     * Examples:
     * - "ns4:RequestElement" -> "RequestElement"
     * - "SOAP-ENV:Envelope" -> "Envelope"
     * - "Element" -> "Element" (no prefix)
     * 
     * @param qualifiedName The qualified name (may include namespace prefix)
     * @return The local name without prefix
     */
    private static String extractLocalName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return qualifiedName;
        }
        // Check if it contains a namespace prefix (format: "prefix:localName")
        int colonIndex = qualifiedName.indexOf(':');
        if (colonIndex > 0 && colonIndex < qualifiedName.length() - 1) {
            // Extract the part after the colon
            return qualifiedName.substring(colonIndex + 1);
        }
        // No prefix, return as-is
        return qualifiedName;
    }

}
