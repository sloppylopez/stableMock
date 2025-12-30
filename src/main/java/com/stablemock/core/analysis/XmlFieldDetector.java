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

                    String confidence = ConfidenceCalculator.calculateConfidence(values.size());
                    // Generate XPath pattern for WireMock XML matching
                    // Handle both elements and attributes
                    String xpathPattern;
                    if (path.contains("@")) {
                        // Attribute: root/child@attr -> //root/child/@attr
                        String[] parts = path.split("@");
                        String elementPath = parts[0];
                        String attrName = parts[1];
                        xpathPattern = buildElementPathXPath(elementPath)
                                + "/@*[local-name()='" + attrName + "']";
                    } else {
                        // Element: use full path to avoid over-broad matches
                        xpathPattern = buildElementPathXPath(path);
                    }
                    String xmlPath = "xml:" + xpathPattern;

                    result.addDynamicField(new DetectionResult.DynamicField(
                            xmlPath, confidence, sampleValues));
                    result.addIgnorePattern(xmlPath);

                    logger.info("Detected dynamic XML field: {} (confidence: {}, samples: {})",
                            xmlPath, confidence, sampleValues.size());
                }
            }
        }
    }

    /**
     * Extracts the element name from an XPath-like path.
     * For "root/child/grandchild", returns "grandchild".
     * 
     * @param path The element path
     * @return The last element name in the path
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
            if (xpath.length() == 0) {
                xpath.append("//*[local-name()='").append(part).append("']");
            } else {
                xpath.append("/*[local-name()='").append(part).append("']");
            }
        }
        return xpath.length() == 0 ? "//*" : xpath.toString();
    }

}
