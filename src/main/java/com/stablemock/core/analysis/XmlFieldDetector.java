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
        detectDynamicFieldsInXml(xmlBodies, result, IdentityFilterMarkers.getDefault());
    }

    /**
     * Like {@link #detectDynamicFieldsInXml(List, DetectionResult)} but with custom markers
     * (e.g. {@link IdentityFilterMarkers#forAnonymizedTest()} for unit tests).
     */
    public static void detectDynamicFieldsInXml(List<String> xmlBodies, DetectionResult result,
            IdentityFilterMarkers markers) {
        if (xmlBodies == null || xmlBodies.size() < 2) {
            logger.debug("Not enough XML bodies to analyze (need at least 2, got {})",
                    xmlBodies != null ? xmlBodies.size() : 0);
            return;
        }

        List<Map<String, String>> elementValueMaps = XmlBodyParser.parseAllXmlBodies(xmlBodies);
        if (elementValueMaps.size() < 2) {
            logger.debug("Failed to parse enough XML bodies for analysis");
            return;
        }

        Set<String> allPaths = new LinkedHashSet<>();
        for (Map<String, String> elementMap : elementValueMaps) {
            allPaths.addAll(elementMap.keySet());
        }

        IdentityFilterMarkers m = markers != null ? markers : IdentityFilterMarkers.getDefault();

        for (String path : allPaths) {
            List<String> values = new ArrayList<>();
            for (Map<String, String> elementMap : elementValueMaps) {
                String value = elementMap.get(path);
                if (value != null) {
                    values.add(value);
                }
            }

            if (values.size() >= 2) {
                boolean allSame = true;
                String firstValue = values.get(0);
                for (int i = 1; i < values.size(); i++) {
                    if (!firstValue.equals(values.get(i))) {
                        allSame = false;
                        break;
                    }
                }

                if (!allSame) {
                    List<String> sampleValues = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, values.size()); i++) {
                        sampleValues.add(values.get(i));
                    }

                    String xpathPattern;
                    if (path.contains("@")) {
                        String[] parts = path.split("@");
                        String elementPath = parts[0];
                        String attrName = parts[1];
                        String localAttrName = extractLocalName(attrName);
                        xpathPattern = buildElementPathXPath(elementPath)
                                + "/@*[local-name()='" + localAttrName + "']";
                    } else {
                        xpathPattern = buildElementPathXPath(path);
                    }
                    String xmlPath = "xml:" + xpathPattern;

                    result.addDynamicField(new DetectionResult.DynamicField(xmlPath, sampleValues));
                    if (!shouldSkipIgnoreForPath(path, m)) {
                        result.addIgnorePattern(xmlPath);
                    }

                    logger.info("Detected dynamic XML field: {} (samples: {})",
                            xmlPath, sampleValues.size());
                } else if (isLikelyDateOrTimeField(path)) {
                    // Values are the same, but path name suggests it's a date/time field
                    // These fields often vary per test run even if same within a single run
                    // Mark as dynamic to handle "per-run" variation (e.g., dates set from "now")
                    List<String> sampleValues = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, values.size()); i++) {
                        sampleValues.add(values.get(i));
                    }

                    // Generate XPath pattern for WireMock XML matching
                    String xpathPattern;
                    if (path.contains("@")) {
                        // Attribute: root/child@attr -> //root/child/@attr
                        String[] parts = path.split("@");
                        String elementPath = parts[0];
                        String attrName = parts[1];
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
                    if (!shouldSkipIgnoreForPath(path, m)) {
                        result.addIgnorePattern(xmlPath);
                    }

                    logger.info("Detected likely date/time XML field (heuristic): {} (samples: {})",
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

    /**
     * Certain scoped-request fields must never be ignored (caller identity, plan selector)
     * so playback can distinguish different callers and plan types. Uses markers for
     * root/caller/plan path names (production default vs anonymized for tests).
     */
    private static boolean shouldSkipIgnoreForPath(String path, IdentityFilterMarkers markers) {
        if (path == null || path.isEmpty() || markers == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return markers.isCallerIdentityPath(lower) || markers.isPlanSelectorPath(lower);
    }

    /**
     * Checks if a path name suggests it's a date/time field that likely varies per test run.
     * This heuristic helps detect fields that are set from "now" or similar per-run values,
     * even when all requests in a single run have the same value.
     * 
     * @param path The XML path (e.g., "root/child@Start" or "root/DateRange/Start")
     * @return true if the path name suggests it's a date/time field
     */
    private static boolean isLikelyDateOrTimeField(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        
        // Extract the last part of the path (element or attribute name)
        String lastPart;
        if (path.contains("@")) {
            // Attribute: extract attribute name
            String[] parts = path.split("@");
            lastPart = parts[parts.length - 1];
        } else {
            // Element: extract last element name
            String[] parts = path.split("/");
            lastPart = parts[parts.length - 1];
        }
        
        // Remove namespace prefix if present
        lastPart = extractLocalName(lastPart);
        
        // Check for date/time-related keywords (case-insensitive)
        String lowerPart = lastPart.toLowerCase();
        return lowerPart.contains("date") ||
               lowerPart.contains("time") ||
               lowerPart.equals("start") ||
               lowerPart.equals("end") ||
               lowerPart.contains("timestamp") ||
               lowerPart.contains("echotoken") ||
               lowerPart.contains("transactionidentifier") ||
               lowerPart.contains("sessiontoken") ||
               lowerPart.contains("requestid");
    }

}
