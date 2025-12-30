package com.stablemock.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for parsing JSON request bodies.
 * Provides methods to parse JSON strings into JsonNode objects.
 */
public final class JsonBodyParser {

    private static final Logger logger = LoggerFactory.getLogger(JsonBodyParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private JsonBodyParser() {
        // utility class
    }

    /**
     * Parses a JSON string into a JsonNode.
     * 
     * @param json The JSON string to parse
     * @return The parsed JsonNode, or null if parsing fails
     */
    public static JsonNode parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            logger.debug("Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Checks if a string is likely JSON based on content.
     * 
     * @param content The content to check
     * @return true if content appears to be JSON
     */
    public static boolean isJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        String trimmed = content.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    /**
     * Checks if a Content-Type header indicates JSON.
     * 
     * @param contentType The Content-Type header value
     * @return true if Content-Type indicates JSON
     */
    public static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.contains("json") || 
               lower.contains("application/json") ||
               lower.contains("application/vnd.api+json");
    }

    /**
     * Parses multiple JSON body strings into JsonNode objects.
     * 
     * @param jsonBodies List of JSON body strings
     * @return List of JsonNode objects (null entries for failed parses)
     */
    public static List<JsonNode> parseAllJsonBodies(List<String> jsonBodies) {
        List<JsonNode> result = new ArrayList<>();
        for (String jsonBody : jsonBodies) {
            JsonNode node = parseJson(jsonBody);
            result.add(node);
        }
        return result;
    }
}

