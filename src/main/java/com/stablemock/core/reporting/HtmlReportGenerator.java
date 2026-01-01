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
                writer.println("  <style>");
                writer.println(getCssStyles());
                writer.println("  </style>");
                writer.println("</head>");
                writer.println("<body>");
                
                generateHeader(writer, report);
                generateSummary(writer, report);
                generateTestClasses(writer, report);
                
                writer.println("</body>");
                writer.println("</html>");
            }
        } catch (IOException e) {
            logger.error("Failed to write HTML report: {}", e.getMessage(), e);
        }
    }

    private static void generateHeader(PrintWriter writer, JsonNode report) {
        writer.println("  <div class=\"header\">");
        writer.println("    <div class=\"header-content\">");
        writer.println("      <img src=\"stablemock-logo-transparent-outline.png\" alt=\"StableMock Logo\" class=\"logo\">");
        writer.println("      <div class=\"header-text\">");
        writer.println("        <h1>StableMock Recording Report</h1>");
        
        if (report.has("generatedAt")) {
            writer.println("        <p class=\"meta\">Generated: " + escapeHtml(report.get("generatedAt").asText()) + "</p>");
        }
        if (report.has("triggeredBy")) {
            writer.println("        <p class=\"meta\">Triggered by: " + escapeHtml(report.get("triggeredBy").asText()) + "</p>");
        }
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
        
        writer.println("    <div class=\"test-class\">");
        writer.println("      <h3>" + escapeHtml(testClassName) + "</h3>");
        
        JsonNode testMethods = testClass.get("testMethods");
        if (testMethods != null && testMethods.isArray()) {
            for (JsonNode testMethod : testMethods) {
                generateTestMethod(writer, testMethod);
            }
        }
        
        writer.println("    </div>");
    }

    private static void generateTestMethod(PrintWriter writer, JsonNode testMethod) {
        String testMethodName = testMethod.has("testMethod") ? testMethod.get("testMethod").asText() : "Unknown";
        String folderPath = testMethod.has("folderPath") ? testMethod.get("folderPath").asText() : "";
        
        writer.println("      <div class=\"test-method\">");
        writer.println("        <h4>" + escapeHtml(testMethodName) + "</h4>");
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
        
        writer.println("      </div>");
    }

    private static void generateAnnotation(PrintWriter writer, JsonNode annotation, Integer annotationIndex) {
        if (annotationIndex != null) {
            writer.println("        <div class=\"annotation-section\">");
            writer.println("          <h5>Annotation " + annotationIndex + "</h5>");
        }
        
        // Detected fields and ignore patterns
        if (annotation.has("detectedFields")) {
            JsonNode detectedFields = annotation.get("detectedFields");
            
            if (detectedFields.has("dynamic_fields") && detectedFields.get("dynamic_fields").isArray()) {
                writer.println("          <div class=\"mutating-fields\">");
                writer.println("            <h6>Mutating Fields</h6>");
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
        
        // Ignore patterns
        if (annotation.has("ignorePatterns")) {
            JsonNode ignorePatterns = annotation.get("ignorePatterns");
            if (ignorePatterns.isArray() && ignorePatterns.size() > 0) {
                writer.println("          <div class=\"ignore-patterns\">");
                writer.println("            <h6>Generated Ignore Patterns</h6>");
                writer.println("            <ul>");
                for (JsonNode pattern : ignorePatterns) {
                    writer.println("              <li><code>" + escapeHtml(pattern.asText()) + "</code></li>");
                }
                writer.println("            </ul>");
                writer.println("          </div>");
            }
        }
        
        // Requests
        if (annotation.has("requests")) {
            JsonNode requests = annotation.get("requests");
            if (requests.isArray() && requests.size() > 0) {
                writer.println("          <div class=\"requests\">");
                writer.println("            <h6>Recorded Requests</h6>");
                writer.println("            <table>");
                writer.println("              <thead>");
                writer.println("                <tr>");
                writer.println("                  <th>Method</th>");
                writer.println("                  <th>URL</th>");
                writer.println("                  <th>Count</th>");
                writer.println("                  <th>Has Body</th>");
                writer.println("                  <th>Mutating Fields</th>");
                writer.println("                </tr>");
                writer.println("              </thead>");
                writer.println("              <tbody>");
                
                for (JsonNode request : requests) {
                    String method = request.has("method") ? request.get("method").asText() : "UNKNOWN";
                    String url = request.has("url") ? request.get("url").asText() : "/unknown";
                    int count = request.has("requestCount") ? request.get("requestCount").asInt() : 0;
                    boolean hasBody = request.has("hasBody") && request.get("hasBody").asBoolean();
                    
                    writer.println("                <tr>");
                    writer.println("                  <td><span class=\"method method-" + method.toLowerCase() + "\">" + escapeHtml(method) + "</span></td>");
                    writer.println("                  <td><code>" + escapeHtml(url) + "</code></td>");
                    writer.println("                  <td>" + count + "</td>");
                    writer.println("                  <td>" + (hasBody ? "✓" : "—") + "</td>");
                    writer.println("                  <td>");
                    
                    if (request.has("mutatingFields") && request.get("mutatingFields").isArray()) {
                        JsonNode mutatingFields = request.get("mutatingFields");
                        if (mutatingFields.size() > 0) {
                            writer.println("                    <ul class=\"inline-list\">");
                            for (JsonNode field : mutatingFields) {
                                String fieldPath = field.has("fieldPath") ? field.get("fieldPath").asText() : "Unknown";
                                writer.println("                      <li><code>" + escapeHtml(fieldPath) + "</code></li>");
                            }
                            writer.println("                    </ul>");
                        } else {
                            writer.println("                    —");
                        }
                    } else {
                        writer.println("                    —");
                    }
                    
                    writer.println("                  </td>");
                    writer.println("                </tr>");
                }
                
                writer.println("              </tbody>");
                writer.println("            </table>");
                writer.println("          </div>");
            }
        }
        
        if (annotationIndex != null) {
            writer.println("        </div>");
        }
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
              background: linear-gradient(135deg, #E8A740 0%, #F5C97A 100%);
              color: #000000;
              padding: 30px;
              border-radius: 8px;
              margin-bottom: 30px;
              box-shadow: 0 4px 20px rgba(232, 167, 64, 0.4), 0 0 40px rgba(232, 167, 64, 0.2);
              border: 2px solid rgba(232, 167, 64, 0.5);
            }
            
            .header-content {
              display: flex;
              align-items: center;
              gap: 20px;
            }
            
            .logo {
              width: 120px;
              height: 120px;
              flex-shrink: 0;
              filter: drop-shadow(0 0 20px rgba(0, 0, 0, 0.5));
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
              background: linear-gradient(to right, #000000, #5C3A1A);
              -webkit-background-clip: text;
              -webkit-text-fill-color: transparent;
              background-clip: text;
            }
            
            .header .meta {
              margin: 5px 0;
              opacity: 0.8;
              font-size: 0.9em;
              color: #1a0f0a;
            }
            
            .header code {
              background-color: rgba(0, 0, 0, 0.2);
              padding: 2px 6px;
              border-radius: 3px;
              font-size: 0.9em;
              color: #1a0f0a;
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
              font-family: 'Rye', serif;
              font-size: 2em;
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
              font-family: 'Rye', serif;
              font-size: 2em;
              background: linear-gradient(to right, #E8A740, #F5C97A);
              -webkit-background-clip: text;
              -webkit-text-fill-color: transparent;
              background-clip: text;
            }
            
            .test-class {
              margin-bottom: 40px;
              padding-bottom: 30px;
              border-bottom: 2px solid rgba(232, 167, 64, 0.2);
            }
            
            .test-class:last-child {
              border-bottom: none;
            }
            
            .test-class h3 {
              color: #E8A740;
              margin-bottom: 15px;
              font-size: 1.5em;
              font-weight: bold;
            }
            
            .test-method {
              margin-left: 20px;
              margin-bottom: 30px;
              padding: 20px;
              background: linear-gradient(to bottom right, rgba(160, 111, 62, 0.1), rgba(0, 0, 0, 0.3));
              border-radius: 6px;
              border-left: 4px solid #E8A740;
            }
            
            .test-method h4 {
              color: #F5C97A;
              margin-bottom: 10px;
              font-weight: bold;
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
              padding: 15px;
              background: rgba(0, 0, 0, 0.3);
              border-radius: 4px;
              border: 1px solid rgba(232, 167, 64, 0.2);
            }
            
            .annotation-section h5 {
              color: #E8A740;
              margin-bottom: 15px;
              font-weight: bold;
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
              padding: 12px;
              text-align: left;
              font-weight: 600;
              border: 1px solid rgba(232, 167, 64, 0.5);
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
            """;
    }
}

