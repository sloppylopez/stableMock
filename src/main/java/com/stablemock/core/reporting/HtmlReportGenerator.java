package com.stablemock.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;

/**
 * Generates a human-readable HTML report from the JSON recording report.
 */
public final class HtmlReportGenerator {

    private static final Logger logger = LoggerFactory.getLogger(HtmlReportGenerator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static int globalRequestDetailsCounter = 0;

    private HtmlReportGenerator() {
        // utility class
    }

    /**
     * Generates an HTML report from the JSON recording report.
     * 
     * @param jsonReportFile The JSON report file to read
     * @param outputHtmlFile The HTML file to write
     */
    public static void generateHtmlReport(File jsonReportFile, File outputHtmlFile) {
        if (!jsonReportFile.exists()) {
            logger.warn("JSON report file not found: {}", jsonReportFile.getAbsolutePath());
            return;
        }

        try {
            JsonNode report = objectMapper.readTree(jsonReportFile);
            generateHtmlReport(report, outputHtmlFile);
            logger.info("HTML report generated: {}", outputHtmlFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to generate HTML report: {}", e.getMessage(), e);
        }
    }

    /**
     * Generates an HTML report from a JSON report node.
     */
    public static void generateHtmlReport(JsonNode report, File outputHtmlFile) {
        // Reset global counter for each report generation
        globalRequestDetailsCounter = 0;
        
        try {
            // Create parent directories if needed
            File parentDir = outputHtmlFile.getParentFile();
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath());
            }

            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputHtmlFile.toPath()))) {
                writer.println("<!DOCTYPE html>");
                writer.println("<html lang=\"en\">");
                writer.println("<head>");
                writer.println("  <meta charset=\"UTF-8\">");
                writer.println("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
                writer.println("  <title>StableMock Recording Report</title>");
                writer.println("  <link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">");
                writer.println("  <link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>");
                writer.println("  <link href=\"https://fonts.googleapis.com/css2?family=Rye&family=Bebas+Neue&display=swap\" rel=\"stylesheet\">");
                writer.println("  <link href=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/prism-tomorrow.min.css\" rel=\"stylesheet\">");
                writer.println("  <style>");
                writer.println(getCssStyles());
                writer.println("  </style>");
                writer.println("</head>");
                writer.println("<body>");
                
                generateHeader(writer, report);
                generateSummary(writer, report);
                generateTestClasses(writer, report);

                writer.println("  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/prism.min.js\"></script>");
                writer.println("  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-json.min.js\"></script>");
                writer.println("  <script>");
                writer.println(getScript());
                writer.println("  </script>");
                
                writer.println("</body>");
                writer.println("</html>");
            }
        } catch (IOException e) {
            logger.error("Failed to write HTML report: {}", e.getMessage(), e);
        }
    }

    private static void generateHeader(PrintWriter writer, JsonNode report) {
        writer.println("  <div class=\"header\">");
        writer.println("    <!-- Animated background elements -->");
        writer.println("    <div class=\"header-bg-elements\">");
        writer.println("      <div class=\"header-glow header-glow-1\"></div>");
        writer.println("      <div class=\"header-glow header-glow-2\"></div>");
        writer.println("      <div class=\"header-glow header-glow-3\"></div>");
        writer.println("    </div>");
        writer.println("    <!-- Particle effects -->");
        writer.println("    <div class=\"header-particles\">");
        for (int i = 0; i < 20; i++) {
            double left = Math.random() * 100;
            double top = Math.random() * 100;
            double delay = Math.random() * 6;
            double duration = 4 + Math.random() * 4;
            writer.println(String.format("      <div class=\"header-particle\" style=\"left: %.1f%%; top: %.1f%%; animation-delay: %.1fs; animation-duration: %.1fs;\"></div>", 
                left, top, delay, duration));
        }
        writer.println("    </div>");
        writer.println("    <div class=\"header-content\">");
        writer.println("      <img src=\"stablemock-logo-transparent-outline.png\" alt=\"StableMock Logo\" class=\"logo\">");
        writer.println("      <div class=\"header-text\">");
        writer.println("        <h1>StableMock Recording Report</h1>");
        
        if (report.has("generatedAt")) {
            writer.println("        <p class=\"meta\">Generated: " + escapeHtml(report.get("generatedAt").asText()) + "</p>");
        }
        // Note: "triggeredBy" is not displayed as it's misleading - the report contains data from ALL test classes,
        // not just the one that triggered the report generation
        if (report.has("baseDirectory")) {
            writer.println("        <p class=\"meta\">Base directory: <code>" + escapeHtml(report.get("baseDirectory").asText()) + "</code></p>");
        }
        
        writer.println("      </div>");
        writer.println("    </div>");
        writer.println("  </div>");
    }

    private static void generateSummary(PrintWriter writer, JsonNode report) {
        writer.println("  <div class=\"summary\">");
        writer.println("    <h2>Summary</h2>");
        
        JsonNode testClasses = report.get("testClasses");
        if (testClasses != null && testClasses.isArray()) {
            int totalTestClasses = testClasses.size();
            int totalTestMethods = 0;
            int totalRequests = 0;
            int totalMutatingFields = 0;
            
            for (JsonNode testClass : testClasses) {
                JsonNode testMethods = testClass.get("testMethods");
                if (testMethods != null && testMethods.isArray()) {
                    totalTestMethods += testMethods.size();
                    
                    for (JsonNode testMethod : testMethods) {
                        int requests = countRequests(testMethod);
                        totalRequests += requests;
                        totalMutatingFields += countMutatingFields(testMethod);
                    }
                }
            }
            
            writer.println("    <div class=\"summary-grid\">");
            writer.println("      <div class=\"summary-item\">");
            writer.println("        <div class=\"summary-value\">" + totalTestClasses + "</div>");
            writer.println("        <div class=\"summary-label\">Test Classes</div>");
            writer.println("      </div>");
            writer.println("      <div class=\"summary-item\">");
            writer.println("        <div class=\"summary-value\">" + totalTestMethods + "</div>");
            writer.println("        <div class=\"summary-label\">Test Methods</div>");
            writer.println("      </div>");
            writer.println("      <div class=\"summary-item\">");
            writer.println("        <div class=\"summary-value\">" + totalRequests + "</div>");
            writer.println("        <div class=\"summary-label\">Total Requests</div>");
            writer.println("      </div>");
            writer.println("      <div class=\"summary-item\">");
            writer.println("        <div class=\"summary-value\">" + totalMutatingFields + "</div>");
            writer.println("        <div class=\"summary-label\">Mutating Fields</div>");
            writer.println("      </div>");
            writer.println("    </div>");
        }
        
        writer.println("  </div>");
    }

    private static void generateTestClasses(PrintWriter writer, JsonNode report) {
        writer.println("  <div class=\"test-classes\">");
        writer.println("    <h2>Test Classes</h2>");
        writer.println("    <div class=\"controls\">");
        writer.println("      <div class=\"control-buttons\">");
        writer.println("        <button type=\"button\" class=\"control-button\" onclick=\"toggleDetails(true)\">Expand all</button>");
        writer.println("        <button type=\"button\" class=\"control-button\" onclick=\"toggleDetails(false)\">Collapse all</button>");
        writer.println("      </div>");
        writer.println("      <input type=\"text\" id=\"filterInput\" class=\"filter-input\" placeholder=\"Filter by test, method, or URL\" oninput=\"filterReport()\">");
        writer.println("    </div>");
        
        JsonNode testClasses = report.get("testClasses");
        if (testClasses != null && testClasses.isArray()) {
            for (JsonNode testClass : testClasses) {
                generateTestClass(writer, testClass);
            }
        }
        
        writer.println("  </div>");
    }

    private static void generateTestClass(PrintWriter writer, JsonNode testClass) {
        String testClassName = testClass.has("testClass") ? testClass.get("testClass").asText() : "Unknown";
        
        int methodCount = 0;
        int requestCount = 0;
        int mutatingCount = 0;
        JsonNode testMethods = testClass.get("testMethods");
        if (testMethods != null && testMethods.isArray()) {
            methodCount = testMethods.size();
            for (JsonNode testMethod : testMethods) {
                requestCount += countRequests(testMethod);
                mutatingCount += countMutatingFields(testMethod);
            }
        }

        writer.println("    <details class=\"test-class\" data-test-class=\"" + escapeHtmlAttribute(testClassName) + "\">");
        writer.println("      <summary>");
        writer.println("        <span class=\"summary-title\">" + escapeHtml(testClassName) + "</span>");
        writer.println("        <span class=\"badge\">Methods: " + methodCount + "</span>");
        writer.println("        <span class=\"badge\">Requests: " + requestCount + "</span>");
        writer.println("        <span class=\"badge\">Mutating fields: " + mutatingCount + "</span>");
        writer.println("      </summary>");
        
        if (testMethods != null && testMethods.isArray()) {
            for (JsonNode testMethod : testMethods) {
                generateTestMethod(writer, testMethod, testClassName);
            }
        }
        
        writer.println("    </details>");
    }

    private static void generateTestMethod(PrintWriter writer, JsonNode testMethod, String testClassName) {
        String testMethodName = testMethod.has("testMethod") ? testMethod.get("testMethod").asText() : "Unknown";
        String folderPath = testMethod.has("folderPath") ? testMethod.get("folderPath").asText() : "";
        int requestCount = countRequests(testMethod);
        int mutatingCount = countMutatingFields(testMethod);
        String filterText = String.join(" ", testClassName, testMethodName, folderPath).toLowerCase();
        
        writer.println("      <details class=\"test-method\" data-method-text=\"" + escapeHtmlAttribute(filterText) + "\">");
        writer.println("        <summary>");
        writer.println("          <span class=\"summary-title\">" + escapeHtml(testMethodName) + "</span>");
        writer.println("          <span class=\"badge\">Requests: " + requestCount + "</span>");
        writer.println("          <span class=\"badge\">Mutating fields: " + mutatingCount + "</span>");
        writer.println("        </summary>");
        writer.println("        <p class=\"folder-path\"><strong>Folder:</strong> <code>" + escapeHtml(folderPath) + "</code></p>");
        
        // Handle single annotation
        if (testMethod.has("annotation")) {
            generateAnnotation(writer, testMethod.get("annotation"), null);
        }
        
        // Handle multiple annotations
        if (testMethod.has("annotations")) {
            JsonNode annotations = testMethod.get("annotations");
            if (annotations.isArray()) {
                for (JsonNode annotation : annotations) {
                    int index = annotation.has("annotationIndex") ? annotation.get("annotationIndex").asInt() : -1;
                    generateAnnotation(writer, annotation, index);
                }
            }
        }
        
        writer.println("      </details>");
    }

    private static void generateAnnotation(PrintWriter writer, JsonNode annotation, Integer annotationIndex) {
        String annotationTitle = annotationIndex != null ? "Annotation " + annotationIndex : "Annotation";
        writer.println("        <details class=\"annotation-section\">");
        writer.println("          <summary>" + escapeHtml(annotationTitle) + "</summary>");
        
        // Detected fields and ignore patterns
        if (annotation.has("detectedFields")) {
            JsonNode detectedFields = annotation.get("detectedFields");
            
            if (detectedFields.has("dynamic_fields") && detectedFields.get("dynamic_fields").isArray()) {
                writer.println("          <div class=\"mutating-fields\">");
                writer.println("            <h6>Ignore Patterns</h6>");
                writer.println("            <ul>");
                
                for (JsonNode field : detectedFields.get("dynamic_fields")) {
                    String fieldPath = field.has("field_path") ? field.get("field_path").asText() : "Unknown";
                    String confidence = field.has("confidence") ? field.get("confidence").asText() : "UNKNOWN";
                    
                    writer.println("              <li>");
                    writer.println("                <code class=\"field-path\">" + escapeHtml(fieldPath) + "</code>");
                    writer.println("                <span class=\"confidence confidence-" + confidence.toLowerCase() + "\">" + escapeHtml(confidence) + "</span>");
                    
                    if (field.has("sample_values") && field.get("sample_values").isArray()) {
                        writer.println("                <details>");
                        writer.println("                  <summary>Sample values</summary>");
                        writer.println("                  <ul class=\"sample-values\">");
                        for (JsonNode sample : field.get("sample_values")) {
                            writer.println("                    <li><code>" + escapeHtml(sample.asText()) + "</code></li>");
                        }
                        writer.println("                  </ul>");
                        writer.println("                </details>");
                    }
                    
                    writer.println("              </li>");
                }
                
                writer.println("            </ul>");
                writer.println("          </div>");
            }
            
            if (detectedFields.has("analyzed_requests_count")) {
                int count = detectedFields.get("analyzed_requests_count").asInt();
                writer.println("          <p class=\"meta-info\"><strong>Analyzed requests:</strong> " + count + "</p>");
            }
        }
        
        // Requests
        if (annotation.has("requests")) {
            JsonNode requests = annotation.get("requests");
            if (requests.isArray() && requests.size() > 0) {
                writer.println("          <div class=\"requests\">");
                writer.println("            <h6>Recorded Requests</h6>");
                writer.println("            <div class=\"requests-table-wrapper\">");
                writer.println("              <table>");
                writer.println("                <thead>");
                writer.println("                  <tr>");
                writer.println("                    <th>Method</th>");
                writer.println("                    <th>URL</th>");
                writer.println("                    <th>Count</th>");
                writer.println("                    <th>Has Body</th>");
                writer.println("                    <th>Mutating Fields</th>");
                writer.println("                    <th>Details</th>");
                writer.println("                  </tr>");
                writer.println("                </thead>");
                writer.println("                <tbody>");
                
                for (JsonNode request : requests) {
                    String method = request.has("method") ? request.get("method").asText() : "UNKNOWN";
                    String url = request.has("url") ? request.get("url").asText() : "/unknown";
                    int count = request.has("requestCount") ? request.get("requestCount").asInt() : 0;
                    boolean hasBody = request.has("hasBody") && request.get("hasBody").asBoolean();
                    String requestFilterText = (method + " " + url).toLowerCase();
                    String detailsId = "request-details-" + globalRequestDetailsCounter++;
                    boolean hasExamples = request.has("examples") && request.get("examples").isArray()
                            && request.get("examples").size() > 0;
                    
                    writer.println("                  <tr class=\"request-row\" data-request-text=\"" + escapeHtmlAttribute(requestFilterText) + "\">");
                    writer.println("                    <td><span class=\"method method-" + method.toLowerCase() + "\">" + escapeHtml(method) + "</span></td>");
                    writer.println("                    <td><code>" + escapeHtml(url) + "</code></td>");
                    writer.println("                    <td>" + count + "</td>");
                    writer.println("                    <td>" + (hasBody ? "✓" : "—") + "</td>");
                    writer.println("                    <td>");
                    
                    if (request.has("mutatingFields") && request.get("mutatingFields").isArray()) {
                        JsonNode mutatingFields = request.get("mutatingFields");
                        if (mutatingFields.size() > 0) {
                            writer.println("                      <ul class=\"inline-list\">");
                            for (JsonNode field : mutatingFields) {
                                String fieldPath = field.has("fieldPath") ? field.get("fieldPath").asText() : "Unknown";
                                writer.println("                        <li><code>" + escapeHtml(fieldPath) + "</code></li>");
                            }
                            writer.println("                      </ul>");
                        } else {
                            writer.println("                      —");
                        }
                    } else {
                        writer.println("                      —");
                    }
                    
                    writer.println("                    </td>");
                    writer.println("                    <td>");
                    if (hasExamples) {
                        writer.println("                      <button type=\"button\" class=\"details-toggle\" onclick=\"toggleRequestDetails('"
                                + escapeHtmlAttribute(detailsId) + "')\">View details</button>");
                    } else {
                        writer.println("                      —");
                    }
                    writer.println("                    </td>");
                    writer.println("                  </tr>");

                    if (hasExamples) {
                        writer.println("                  <tr class=\"details-row\" id=\"" + escapeHtmlAttribute(detailsId) + "\">");
                        writer.println("                    <td colspan=\"6\">");
                        writer.println("                      <div class=\"details-content\">");
                        renderRequestDetails(writer, request.get("examples"));
                        writer.println("                      </div>");
                        writer.println("                    </td>");
                        writer.println("                  </tr>");
                    }
                }
                
                writer.println("                </tbody>");
                writer.println("              </table>");
                writer.println("            </div>");
                writer.println("          </div>");
            }
        }
        
        writer.println("        </details>");
    }

    private static void renderRequestDetails(PrintWriter writer, JsonNode examples) {
        if (examples == null || !examples.isArray() || examples.size() == 0) {
            return;
        }

        int index = 1;
        for (JsonNode example : examples) {
            writer.println("                        <div class=\"request-response-pair\">");
            writer.println("                          <div class=\"example-header\">");
            writer.println("                            <div class=\"example-title\">Example " + index + "</div>");
            JsonNode responseNode = example.get("response");
            if (responseNode != null && responseNode.has("status")) {
                int status = responseNode.get("status").asInt();
                writer.println("                            <div class=\"example-status\">Status: <span class=\"status-badge "
                        + statusClass(status) + "\">" + status + "</span></div>");
            }
            writer.println("                          </div>");

            JsonNode requestNode = example.get("request");

            writer.println("                          <div class=\"headers-block\">");
            writer.println("                            <div class=\"section-heading\">Headers</div>");
            if (requestNode != null) {
                renderJsonBlock(writer, "Request headers", requestNode.get("headers"));
            }
            if (responseNode != null) {
                renderJsonBlock(writer, "Response headers", responseNode.get("headers"));
            }
            writer.println("                          </div>");

            writer.println("                          <div class=\"request-block\">");
            writer.println("                            <div class=\"section-heading\">Request</div>");
            if (requestNode != null) {
                JsonNode requestBodyJson = requestNode.get("bodyJson");
                String requestBody = requestNode.has("body") ? requestNode.get("body").asText() : null;
                renderBodyBlock(writer, "Body", requestBodyJson, requestBody);
            }
            writer.println("                          </div>");

            writer.println("                          <div class=\"response-block\">");
            writer.println("                            <div class=\"section-heading\">Response</div>");
            if (responseNode != null) {
                JsonNode responseBodyJson = responseNode.get("bodyJson");
                String responseBody = responseNode.has("body") ? responseNode.get("body").asText() : null;
                renderBodyBlock(writer, "Body", responseBodyJson, responseBody);
                if (responseNode.has("bodyFileName")) {
                    writer.println("                            <div class=\"meta-info\">Body file: <code>"
                            + escapeHtml(responseNode.get("bodyFileName").asText()) + "</code></div>");
                }
            }
            writer.println("                          </div>");

            writer.println("                        </div>");
            index++;
        }
    }

    private static void renderJsonBlock(PrintWriter writer, String title, JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isMissingNode() || jsonNode.isNull()) {
            return;
        }
        writer.println("                            <div class=\"request-section\">");
        writer.println("                              <div class=\"section-title\">" + escapeHtml(title) + "</div>");
        writer.println("                              <pre><code class=\"language-json\">" + escapeHtml(prettyPrintJson(jsonNode)) + "</code></pre>");
        writer.println("                            </div>");
    }

    private static void renderBodyBlock(PrintWriter writer, String title, JsonNode bodyJson, String bodyText) {
        if ((bodyJson == null || bodyJson.isMissingNode() || bodyJson.isNull())
                && (bodyText == null || bodyText.isBlank())) {
            return;
        }
        writer.println("                            <div class=\"request-section\">");
        writer.println("                              <div class=\"section-title\">" + escapeHtml(title) + "</div>");
        String body = bodyJson != null && !bodyJson.isMissingNode() && !bodyJson.isNull()
                ? prettyPrintJson(bodyJson)
                : tryPrettyPrintJson(bodyText);
        boolean isJson = bodyJson != null || (bodyText != null && isJsonString(bodyText));
        String codeClass = isJson ? "language-json" : "";
        writer.println("                              <pre><code class=\"" + codeClass + "\">" + escapeHtml(body) + "</code></pre>");
        writer.println("                            </div>");
    }
    
    private static boolean isJsonString(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) 
            || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private static int countRequests(JsonNode testMethod) {
        int count = 0;
        
        if (testMethod.has("annotation")) {
            count += countRequestsInAnnotation(testMethod.get("annotation"));
        }
        
        if (testMethod.has("annotations")) {
            JsonNode annotations = testMethod.get("annotations");
            if (annotations.isArray()) {
                for (JsonNode annotation : annotations) {
                    count += countRequestsInAnnotation(annotation);
                }
            }
        }
        
        return count;
    }

    private static int countRequestsInAnnotation(JsonNode annotation) {
        if (annotation.has("requests") && annotation.get("requests").isArray()) {
            int total = 0;
            for (JsonNode request : annotation.get("requests")) {
                if (request.has("requestCount")) {
                    total += request.get("requestCount").asInt();
                } else {
                    total += 1;
                }
            }
            return total;
        }
        return 0;
    }

    private static int countMutatingFields(JsonNode testMethod) {
        int count = 0;
        
        if (testMethod.has("annotation")) {
            count += countMutatingFieldsInAnnotation(testMethod.get("annotation"));
        }
        
        if (testMethod.has("annotations")) {
            JsonNode annotations = testMethod.get("annotations");
            if (annotations.isArray()) {
                for (JsonNode annotation : annotations) {
                    count += countMutatingFieldsInAnnotation(annotation);
                }
            }
        }
        
        return count;
    }

    private static int countMutatingFieldsInAnnotation(JsonNode annotation) {
        if (annotation.has("detectedFields")) {
            JsonNode detectedFields = annotation.get("detectedFields");
            if (detectedFields.has("dynamic_fields") && detectedFields.get("dynamic_fields").isArray()) {
                return detectedFields.get("dynamic_fields").size();
            }
        }
        return 0;
    }

    private static String prettyPrintJson(JsonNode jsonNode) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
        } catch (IOException e) {
            return jsonNode.toString();
        }
    }

    private static String tryPrettyPrintJson(String rawText) {
        if (rawText == null) {
            return "";
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(rawText);
            return prettyPrintJson(jsonNode);
        } catch (IOException e) {
            return rawText;
        }
    }

    private static String statusClass(int status) {
        if (status >= 200 && status < 300) {
            return "status-2xx";
        }
        if (status >= 300 && status < 400) {
            return "status-3xx";
        }
        if (status >= 400 && status < 500) {
            return "status-4xx";
        }
        if (status >= 500) {
            return "status-5xx";
        }
        return "status-unknown";
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static String escapeHtmlAttribute(String text) {
        return escapeHtml(text);
    }

    private static String getCssStyles() {
        return """
            * {
              margin: 0;
              padding: 0;
              box-sizing: border-box;
            }
            
            body {
              font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
              line-height: 1.6;
              color: #d1d5db;
              background: linear-gradient(to bottom, #000000, #1a0f0a);
              padding: 20px;
              min-height: 100vh;
            }
            
            .header {
              background: #000000;
              color: #d1d5db;
              padding: 30px;
              border-radius: 8px;
              margin-bottom: 30px;
              box-shadow: 0 4px 20px rgba(0, 0, 0, 0.8);
              border: 1px solid rgba(232, 167, 64, 0.3);
              position: relative;
              overflow: hidden;
            }
            
            .header-bg-elements {
              position: absolute;
              inset: 0;
              overflow: hidden;
              pointer-events: none;
            }
            
            .header-glow {
              position: absolute;
              border-radius: 50%;
              filter: blur(80px);
              opacity: 0.3;
              animation: float 6s ease-in-out infinite;
            }
            
            .header-glow-1 {
              width: 384px;
              height: 384px;
              background: rgba(232, 167, 64, 0.1);
              top: 25%;
              left: 25%;
            }
            
            .header-glow-2 {
              width: 384px;
              height: 384px;
              background: rgba(245, 201, 122, 0.1);
              bottom: 25%;
              right: 25%;
              animation-delay: 2s;
            }
            
            .header-glow-3 {
              width: 600px;
              height: 600px;
              background: rgba(232, 167, 64, 0.05);
              top: 50%;
              left: 50%;
              transform: translate(-50%, -50%);
              animation-delay: 4s;
            }
            
            .header-particles {
              position: absolute;
              inset: 0;
              pointer-events: none;
            }
            
            .header-particle {
              position: absolute;
              width: 4px;
              height: 4px;
              background: rgba(232, 167, 64, 0.3);
              border-radius: 50%;
              animation: float 8s ease-in-out infinite;
            }
            
            @keyframes float {
              0%, 100% {
                transform: translate(0, 0);
              }
              25% {
                transform: translate(10px, -10px);
              }
              50% {
                transform: translate(-5px, 10px);
              }
              75% {
                transform: translate(-10px, -5px);
              }
            }
            
            .header-content {
              display: flex;
              align-items: center;
              gap: 20px;
              position: relative;
              z-index: 1;
            }
            
            .logo {
              width: 120px;
              height: 120px;
              flex-shrink: 0;
              filter: drop-shadow(0 0 20px rgba(232, 167, 64, 0.6));
            }
            
            @media (max-width: 768px) {
              .header-content {
                flex-direction: column;
                text-align: center;
              }
              
              .logo {
                width: 80px;
                height: 80px;
              }
            }
            
            .header-text {
              flex: 1;
            }
            
            .header h1 {
              font-family: 'Rye', serif;
              font-size: 2.5em;
              margin-bottom: 10px;
              background: linear-gradient(to right, #E8A740, #F5C97A);
              -webkit-background-clip: text;
              -webkit-text-fill-color: transparent;
              background-clip: text;
            }
            
            .header .meta {
              margin: 5px 0;
              opacity: 0.9;
              font-size: 0.9em;
              color: #d1d5db;
            }
            
            .header code {
              background-color: rgba(232, 167, 64, 0.2);
              padding: 2px 6px;
              border-radius: 3px;
              font-size: 0.9em;
              color: #E8A740;
              border: 1px solid rgba(232, 167, 64, 0.3);
            }
            
            .summary {
              background: linear-gradient(to bottom right, rgba(160, 111, 62, 0.2), rgba(0, 0, 0, 0.4));
              padding: 25px;
              border-radius: 8px;
              margin-bottom: 30px;
              box-shadow: 0 2px 10px rgba(0, 0, 0, 0.5);
              border: 1px solid rgba(232, 167, 64, 0.3);
            }
            
            .summary h2 {
              margin-bottom: 20px;
              font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
              font-size: 2em;
              font-weight: 600;
              background: linear-gradient(to right, #E8A740, #F5C97A);
              -webkit-background-clip: text;
              -webkit-text-fill-color: transparent;
              background-clip: text;
            }
            
            .summary-grid {
              display: grid;
              grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
              gap: 20px;
            }
            
            .summary-item {
              text-align: center;
              padding: 20px;
              background: linear-gradient(to bottom right, rgba(160, 111, 62, 0.1), rgba(0, 0, 0, 0.3));
              border-radius: 6px;
              border: 1px solid rgba(232, 167, 64, 0.2);
            }
            
            .summary-value {
              font-family: 'Bebas Neue', sans-serif;
              font-size: 2.5em;
              font-weight: bold;
              color: #E8A740;
              margin-bottom: 5px;
              text-shadow: 0 0 10px rgba(232, 167, 64, 0.5);
            }
            
            .summary-label {
              color: #9ca3af;
              font-size: 0.9em;
              text-transform: uppercase;
              letter-spacing: 0.5px;
            }
            
            .test-classes {
              background: linear-gradient(to bottom right, rgba(160, 111, 62, 0.2), rgba(0, 0, 0, 0.4));
              padding: 25px;
              border-radius: 8px;
              box-shadow: 0 2px 10px rgba(0, 0, 0, 0.5);
              border: 1px solid rgba(232, 167, 64, 0.3);
            }
            
            .test-classes h2 {
              margin-bottom: 20px;
              font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
              font-size: 2em;
              font-weight: 600;
              background: linear-gradient(to right, #E8A740, #F5C97A);
              -webkit-background-clip: text;
              -webkit-text-fill-color: transparent;
              background-clip: text;
            }

            .controls {
              display: flex;
              flex-wrap: wrap;
              gap: 12px;
              align-items: center;
              margin-bottom: 20px;
            }

            .control-buttons {
              display: flex;
              gap: 10px;
            }

            .control-button {
              background: rgba(232, 167, 64, 0.2);
              color: #F5C97A;
              border: 1px solid rgba(232, 167, 64, 0.5);
              padding: 6px 12px;
              border-radius: 6px;
              cursor: pointer;
              font-weight: 600;
            }

            .control-button:hover {
              background: rgba(232, 167, 64, 0.35);
            }

            .filter-input {
              flex: 1;
              min-width: 220px;
              padding: 8px 12px;
              border-radius: 6px;
              border: 1px solid rgba(232, 167, 64, 0.4);
              background: rgba(0, 0, 0, 0.4);
              color: #d1d5db;
            }
            
            .test-class {
              margin-bottom: 40px;
              padding-bottom: 30px;
              border-bottom: 2px solid rgba(232, 167, 64, 0.2);
            }
            
            .test-class:last-child {
              border-bottom: none;
            }
            
            .test-class > summary,
            .test-method > summary,
            .annotation-section > summary {
              list-style: none;
              display: flex;
              flex-wrap: wrap;
              align-items: center;
              gap: 10px;
              cursor: pointer;
              color: #E8A740;
              font-weight: bold;
              padding: 6px 0;
            }

            .test-class > summary::-webkit-details-marker,
            .test-method > summary::-webkit-details-marker,
            .annotation-section > summary::-webkit-details-marker {
              display: none;
            }

            .test-class > summary::before,
            .test-method > summary::before,
            .annotation-section > summary::before {
              content: "▸";
              display: inline-block;
              margin-right: 6px;
              transition: transform 0.2s ease;
            }

            details[open] > summary::before {
              transform: rotate(90deg);
            }

            .summary-title {
              font-size: 1.2em;
              color: #F5C97A;
            }

            .badge {
              display: inline-flex;
              align-items: center;
              gap: 4px;
              padding: 2px 8px;
              border-radius: 999px;
              font-size: 0.75em;
              background: rgba(0, 0, 0, 0.5);
              border: 1px solid rgba(232, 167, 64, 0.35);
              color: #d1d5db;
            }

            .test-method {
              margin-left: 20px;
              margin-bottom: 20px;
              padding: 16px 20px;
              background: linear-gradient(to bottom right, rgba(160, 111, 62, 0.1), rgba(0, 0, 0, 0.3));
              border-radius: 6px;
              border-left: 4px solid #E8A740;
            }
            
            .folder-path {
              margin-bottom: 15px;
              font-size: 0.9em;
              color: #9ca3af;
            }
            
            .folder-path code {
              background: rgba(0, 0, 0, 0.4);
              padding: 2px 6px;
              border-radius: 3px;
              font-size: 0.85em;
              color: #E8A740;
              border: 1px solid rgba(232, 167, 64, 0.3);
            }
            
            .annotation-section {
              margin-top: 15px;
              padding: 12px 15px 15px;
              background: rgba(0, 0, 0, 0.3);
              border-radius: 4px;
              border: 1px solid rgba(232, 167, 64, 0.2);
            }
            
            .mutating-fields, .ignore-patterns, .requests {
              margin-top: 20px;
            }
            
            .mutating-fields h6, .ignore-patterns h6, .requests h6 {
              color: #F5C97A;
              margin-bottom: 10px;
              font-size: 1.1em;
              font-weight: bold;
            }
            
            .mutating-fields ul, .ignore-patterns ul {
              list-style: none;
              padding-left: 0;
            }
            
            .mutating-fields li, .ignore-patterns li {
              margin: 8px 0;
              padding: 8px;
              background: rgba(0, 0, 0, 0.4);
              border-radius: 4px;
              border-left: 3px solid #E8A740;
            }
            
            .field-path {
              font-weight: bold;
              color: #E8A740;
            }
            
            .confidence {
              display: inline-block;
              padding: 2px 8px;
              border-radius: 12px;
              font-size: 0.75em;
              font-weight: bold;
              margin-left: 10px;
            }
            
            .confidence-high {
              background-color: rgba(34, 197, 94, 0.2);
              color: #4ade80;
              border: 1px solid rgba(34, 197, 94, 0.4);
            }
            
            .confidence-medium {
              background-color: rgba(232, 167, 64, 0.2);
              color: #F5C97A;
              border: 1px solid rgba(232, 167, 64, 0.4);
            }
            
            .confidence-low {
              background-color: rgba(239, 68, 68, 0.2);
              color: #f87171;
              border: 1px solid rgba(239, 68, 68, 0.4);
            }
            
            .sample-values {
              margin-top: 8px;
              margin-left: 20px;
            }
            
            .sample-values li {
              margin: 4px 0;
              padding: 4px;
              background: rgba(0, 0, 0, 0.3);
              border-left: none;
            }
            
            .sample-values code {
              font-size: 0.85em;
              color: #9ca3af;
            }
            
            details {
              margin-top: 5px;
            }
            
            summary {
              cursor: pointer;
              color: #E8A740;
              font-size: 0.9em;
            }
            
            summary:hover {
              text-decoration: underline;
              color: #F5C97A;
            }
            
            table {
              width: 100%;
              border-collapse: collapse;
              margin-top: 10px;
            }
            
            table th {
              background: linear-gradient(to bottom, #E8A740, #A06F3E);
              color: #000000;
              padding: 6px 12px;
              text-align: left;
              font-weight: 600;
              border: 1px solid rgba(232, 167, 64, 0.5);
              position: sticky;
              top: 0;
              z-index: 1;
            }
            
            table td {
              padding: 10px;
              border-bottom: 1px solid rgba(232, 167, 64, 0.2);
              background: rgba(0, 0, 0, 0.2);
            }
            
            table tr:hover {
              background-color: rgba(232, 167, 64, 0.1);
            }
            
            .method {
              display: inline-block;
              padding: 4px 8px;
              border-radius: 4px;
              font-weight: bold;
              font-size: 0.85em;
            }
            
            .method-get {
              background-color: rgba(34, 197, 94, 0.2);
              color: #4ade80;
              border: 1px solid rgba(34, 197, 94, 0.4);
            }
            
            .method-post {
              background-color: rgba(59, 130, 246, 0.2);
              color: #60a5fa;
              border: 1px solid rgba(59, 130, 246, 0.4);
            }
            
            .method-put {
              background-color: rgba(232, 167, 64, 0.2);
              color: #F5C97A;
              border: 1px solid rgba(232, 167, 64, 0.4);
            }
            
            .method-delete {
              background-color: rgba(239, 68, 68, 0.2);
              color: #f87171;
              border: 1px solid rgba(239, 68, 68, 0.4);
            }
            
            .method-patch {
              background-color: rgba(168, 85, 247, 0.2);
              color: #a78bfa;
              border: 1px solid rgba(168, 85, 247, 0.4);
            }
            
            .inline-list {
              list-style: none;
              padding: 0;
              margin: 0;
            }
            
            .inline-list li {
              display: inline-block;
              margin-right: 8px;
              padding: 2px 6px;
              background: rgba(0, 0, 0, 0.4);
              border-radius: 3px;
              border-left: none;
              border: 1px solid rgba(232, 167, 64, 0.3);
            }
            
            .meta-info {
              margin: 10px 0;
              color: #9ca3af;
              font-size: 0.9em;
            }
            
            code {
              font-family: 'Courier New', monospace;
              font-size: 0.9em;
              color: #E8A740;
            }
            
            pre code[class*="language-"] {
              color: inherit;
            }

            .requests-table-wrapper {
              overflow-x: auto;
              border-radius: 6px;
              border: 1px solid rgba(232, 167, 64, 0.2);
            }

            .details-toggle {
              background: transparent;
              color: #E8A740;
              border: 1px solid rgba(232, 167, 64, 0.4);
              padding: 4px 10px;
              border-radius: 6px;
              cursor: pointer;
              font-weight: 600;
            }

            .details-toggle:hover {
              background: rgba(232, 167, 64, 0.2);
            }

            .details-row {
              display: none;
            }

            .details-row.is-open {
              display: table-row;
            }

            .details-content {
              padding: 12px 0;
            }

            .request-response-pair {
              margin-top: 10px;
              padding: 12px;
              background: rgba(0, 0, 0, 0.35);
              border-radius: 6px;
              display: grid;
              gap: 12px;
              grid-template-columns: repeat(3, minmax(200px, 1fr));
              border: 1px solid rgba(232, 167, 64, 0.25);
            }

            .example-header {
              grid-column: 1 / -1;
              display: flex;
              flex-wrap: wrap;
              align-items: center;
              gap: 10px;
              font-weight: bold;
              color: #F5C97A;
            }

            .section-heading {
              font-weight: bold;
              margin-bottom: 6px;
              color: #F5C97A;
            }

            .request-section {
              margin-bottom: 10px;
            }

            .section-title {
              font-size: 0.85em;
              text-transform: uppercase;
              letter-spacing: 0.04em;
              color: #9ca3af;
              margin-bottom: 4px;
            }

            .headers-block,
            .request-block,
            .response-block {
              min-width: 0;
            }

            .example-title {
              font-weight: bold;
            }

            .example-status {
              color: #9ca3af;
            }

            pre {
              background: rgba(0, 0, 0, 0.5);
              padding: 10px;
              border-radius: 6px;
              overflow-x: auto;
              border: 1px solid rgba(232, 167, 64, 0.2);
              white-space: pre-wrap;
            }
            
            pre code {
              background: transparent;
              padding: 0;
              border: none;
            }
            
            pre[class*="language-"] {
              background: rgba(0, 0, 0, 0.5);
            }

            .status-line {
              margin-bottom: 8px;
              color: #9ca3af;
            }

            .status-badge {
              padding: 2px 8px;
              border-radius: 999px;
              font-weight: bold;
              margin-left: 6px;
              font-size: 0.8em;
            }

            .status-2xx {
              background: rgba(34, 197, 94, 0.2);
              color: #4ade80;
              border: 1px solid rgba(34, 197, 94, 0.4);
            }

            .status-3xx {
              background: rgba(59, 130, 246, 0.2);
              color: #60a5fa;
              border: 1px solid rgba(59, 130, 246, 0.4);
            }

            .status-4xx {
              background: rgba(239, 68, 68, 0.2);
              color: #f87171;
              border: 1px solid rgba(239, 68, 68, 0.4);
            }

            .status-5xx {
              background: rgba(168, 85, 247, 0.2);
              color: #a78bfa;
              border: 1px solid rgba(168, 85, 247, 0.4);
            }

            .status-unknown {
              background: rgba(107, 114, 128, 0.2);
              color: #d1d5db;
              border: 1px solid rgba(107, 114, 128, 0.4);
            }

            .is-filtered-out {
              display: none;
            }
            """;
    }

    private static String getScript() {
        return """
            function toggleDetails(expand) {
              document.querySelectorAll('.test-classes details').forEach((detail) => {
                detail.open = expand;
              });
            }

            function toggleRequestDetails(id) {
              const row = document.getElementById(id);
              if (!row) {
                return;
              }
              row.classList.toggle('is-open');
            }

            function filterReport() {
              const filterValue = document.getElementById('filterInput').value.toLowerCase().trim();
              const testMethods = document.querySelectorAll('.test-method');
              testMethods.forEach((method) => {
                const methodText = method.dataset.methodText || '';
                const matchesMethod = !filterValue || methodText.includes(filterValue);

                let hasMatchingRequest = false;
                method.querySelectorAll('.request-row').forEach((row) => {
                  const rowText = row.dataset.requestText || '';
                  const matchRow = !filterValue || rowText.includes(filterValue);
                  row.classList.toggle('is-filtered-out', !matchRow);
                  const detailsRow = row.nextElementSibling;
                  if (detailsRow && detailsRow.classList.contains('details-row')) {
                    detailsRow.classList.toggle('is-filtered-out', !matchRow);
                  }
                  if (matchRow) {
                    hasMatchingRequest = true;
                  }
                });

                const shouldShow = !filterValue || matchesMethod || hasMatchingRequest;
                method.classList.toggle('is-filtered-out', !shouldShow);
              });

              document.querySelectorAll('.test-class').forEach((testClass) => {
                const visibleMethods = testClass.querySelectorAll('.test-method:not(.is-filtered-out)');
                testClass.classList.toggle('is-filtered-out', visibleMethods.length === 0 && filterValue !== '');
              });
            }
            """;
    }
}
