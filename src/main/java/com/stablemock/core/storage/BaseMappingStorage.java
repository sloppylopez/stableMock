package com.stablemock.core.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Base class for mapping storage operations with shared utilities.
 */
public abstract class BaseMappingStorage {
    
    protected static final Logger logger = LoggerFactory.getLogger(BaseMappingStorage.class);
    
    protected BaseMappingStorage() {
        // abstract class
    }
    
    /**
     * Logs a body file copy failure. Body files are non-critical - missing files won't break playback,
     * but response bodies may be missing. Uses debug level to avoid cluttering logs.
     * 
     * @param fileName The name of the body file that failed to copy
     * @param exception The exception that occurred
     */
    protected static void logBodyFileCopyFailure(String fileName, Exception exception) {
        logger.debug("Failed to copy body file {}: {}", fileName, exception.getMessage());
    }
    
    /**
     * Cleans up class-level mapping directories.
     */
    public static void cleanupClassLevelDirectory(File baseMappingsDir) {
        File baseMappingsSubDir = new File(baseMappingsDir, "mappings");
        File baseFilesDir = new File(baseMappingsDir, "__files");
        
        if (baseMappingsSubDir.exists() && baseMappingsSubDir.isDirectory()) {
            File[] mappingFiles = baseMappingsSubDir.listFiles();
            if (mappingFiles != null) {
                for (File file : mappingFiles) {
                    if (file.isFile() && !file.delete()) {
                        logger.error("Failed to delete mapping file: {}", file.getAbsolutePath());
                    }
                }
            }
        }
        
        if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
            File[] bodyFiles = baseFilesDir.listFiles();
            if (bodyFiles != null) {
                for (File file : bodyFiles) {
                    if (file.isFile() && !file.delete()) {
                        logger.error("Failed to delete body file: {}", file.getAbsolutePath());
                    }
                }
            }
        }
        
        if (baseMappingsSubDir.exists() && baseMappingsSubDir.isDirectory()) {
            File[] remainingFiles = baseMappingsSubDir.listFiles();
            if (remainingFiles != null && remainingFiles.length > 0) {
                // Directory still has files, skip deletion
            } else if (!baseMappingsSubDir.delete()) {
                logger.warn("Failed to delete base mappings directory: {}", baseMappingsSubDir.getAbsolutePath());
            }
        }

        if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
            File[] remainingFiles = baseFilesDir.listFiles();
            if (remainingFiles != null && remainingFiles.length > 0) {
                // Directory still has files, skip deletion
            } else if (!baseFilesDir.delete()) {
                logger.warn("Failed to delete base files directory: {}", baseFilesDir.getAbsolutePath());
            }
        }
    }
    
    /**
     * UUID suffix pattern: 8hex-4hex-4hex-4hex-12hex
     */
    private static final java.util.regex.Pattern UUID_SUFFIX_PATTERN =
            java.util.regex.Pattern.compile("-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.");

    /**
     * Shortens mapping filenames by replacing the 36-char UUID suffix with an 8-char hex hash.
     *
     * <p>This prevents Git 256-character path limit issues when test class names,
     * test method names, and URL paths combine to produce very long file paths.
     *
     * <p>For each mapping JSON file:
     * <ul>
     *   <li>Detects the UUID suffix (e.g., {@code content-api-meals-rates-abc123-def456.json})</li>
     *   <li>Computes an 8-char MD5-based hash from the original filename</li>
     *   <li>Renames to {@code content-api-meals-rates-a3f2c1d0.json}</li>
     *   <li>Updates any referenced {@code bodyFileName} inside the JSON</li>
     * </ul>
     *
     * <p>Also renames corresponding body files in {@code __files/} using the same algorithm.
     *
     * @param baseDir the parent directory containing {@code mappings/} and optionally {@code __files/}
     */
    public static void shortenMappingFilenames(File baseDir) {
        if (baseDir == null || !baseDir.exists()) {
            return;
        }

        File mappingsDir = new File(baseDir, "mappings");
        File filesDir = new File(baseDir, "__files");

        // Build a map of old body filename → new body filename so we can update references in mapping JSON
        java.util.Map<String, String> bodyRenameMap = new java.util.HashMap<>();

        // Phase 1: Plan body file renames (don't rename yet)
        if (filesDir.exists() && filesDir.isDirectory()) {
            File[] bodyFiles = filesDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (bodyFiles != null) {
                for (File bodyFile : bodyFiles) {
                    String newName = computeShortName(bodyFile.getName());
                    if (!newName.equals(bodyFile.getName())) {
                        bodyRenameMap.put(bodyFile.getName(), newName);
                    }
                }
                if (!bodyRenameMap.isEmpty()) {
                    // Actually perform the renames
                    for (java.util.Map.Entry<String, String> entry : bodyRenameMap.entrySet()) {
                        File src = new File(filesDir, entry.getKey());
                        File dst = new File(filesDir, entry.getValue());
                        if (src.renameTo(dst)) {
                            logger.debug("Renamed body file: {} -> {}", entry.getKey(), entry.getValue());
                        } else {
                            logger.warn("Failed to rename body file: {} -> {}", entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        }

        // Phase 2: Rename mapping files and update bodyFileName references
        if (mappingsDir.exists() && mappingsDir.isDirectory()) {
            File[] mappingFiles = mappingsDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (mappingFiles != null) {
                for (File mappingFile : mappingFiles) {
                    String originalName = mappingFile.getName();
                    String shortName = computeShortName(originalName);

                    // Update bodyFileName reference inside the JSON if needed
                    boolean hasBodyRef = false;
                    try {
                        String jsonContent = Files.readString(mappingFile.toPath());
                        String updatedJson = updateBodyFileName(jsonContent, bodyRenameMap);
                        if (!updatedJson.equals(jsonContent)) {
                            Files.writeString(mappingFile.toPath(), updatedJson);
                            hasBodyRef = true;
                        }
                    } catch (IOException e) {
                        logger.debug("Could not read/update mapping JSON {}: {}", originalName, e.getMessage());
                    }

                    // Rename the mapping file itself
                    if (!shortName.equals(originalName)) {
                        File destFile = new File(mappingsDir, shortName);
                        if (mappingFile.renameTo(destFile)) {
                            logger.debug("Renamed mapping file: {} -> {}{}",
                                    originalName, shortName, hasBodyRef ? " (body ref updated)" : "");
                        } else {
                            logger.warn("Failed to rename mapping file: {} -> {}", originalName, shortName);
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes a shortened filename by replacing the trailing UUID with an 8-char hex hash.
     *
     * @param fileName the original filename (e.g., "users_1-abc123-def456-789abc.json")
     * @return shortened filename (e.g., "users_1-a3f2c1d0.json") or unchanged if no UUID detected
     */
    private static String computeShortName(String fileName) {
        if (fileName == null || !fileName.endsWith(".json")) {
            return fileName;
        }

        java.util.regex.Matcher matcher = UUID_SUFFIX_PATTERN.matcher(fileName);
        if (!matcher.find()) {
            return fileName;
        }

        int uuidStartIndex = matcher.start();
        String prefix = fileName.substring(0, uuidStartIndex); // already without the leading '-'
        String extension = ".json";

        // Compute 8-char hex hash from the full original filename
        String shortHash = md5Hex8(fileName);

        return prefix + "-" + shortHash + extension;
    }

    /**
     * Computes an 8-character lowercase hex string from the first 4 bytes of MD5 digest of input.
     *
     * @param input the string to hash
     * @return 8-char lowercase hex string
     */
    private static String md5Hex8(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                String hex = Integer.toHexString(0xFF & digest[i]);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed to be available
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    /**
     * Updates bodyFileName references inside mapping JSON content.
     *
     * @param jsonContent the raw JSON string
     * @param bodyRenameMap map of old body filename → new body filename
     * @return updated JSON string, or original if no replacements made
     */
    private static String updateBodyFileName(String jsonContent, java.util.Map<String, String> bodyRenameMap) {
        if (bodyRenameMap.isEmpty()) {
            return jsonContent;
        }
        String result = jsonContent;
        for (java.util.Map.Entry<String, String> entry : bodyRenameMap.entrySet()) {
            // Match "bodyFileName": "value" pattern
            String oldPattern = '"' + entry.getKey() + '"';
            String newPattern = '"' + entry.getValue() + '"';
            result = result.replace(oldPattern, newPattern);
        }
        return result;
    }

    /**
     * Copies all .json files from source directory to destination directory.
     *
     * @param sourceDir source directory (may be null or non-existent)
     * @param destDir destination directory (must exist)
     */
    public static void copyJsonFiles(File sourceDir, File destDir) {
        if (sourceDir == null || !sourceDir.exists() || !sourceDir.isDirectory()) {
            return;
        }
        if (!destDir.exists()) {
            try {
                Files.createDirectories(destDir.toPath());
            } catch (IOException e) {
                logger.debug("Failed to create destination directory: {}", destDir.getAbsolutePath());
                return;
            }
        }

        File[] files = sourceDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            File destFile = new File(destDir, file.getName());
            try {
                Files.copy(file.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                logger.debug("Copied: {} -> {}", file.getName(), destFile.getName());
            } catch (IOException e) {
                logger.debug("Failed to copy {}: {}", file.getName(), e.getMessage());
            }
        }
    }

    /**
     * Checks if a request URL matches any of the annotation URLs.
     * 
     * <p>Matching semantics:
     * <ul>
     *   <li>Extracts the path component from each annotation URL</li>
     *   <li>Matches if the request URL starts with the annotation path</li>
     *   <li>Matches if the annotation path is empty (root path)</li>
     *   <li>If URL parsing fails (MalformedURLException), returns true as a fallback
     *       to ensure the request is not incorrectly excluded</li>
     * </ul>
     * 
     * @param requestUrl the request URL to match against
     * @param annotationUrls array of annotation URLs to check
     * @return true if the request URL matches any annotation URL, false otherwise
     */
    protected static boolean matchesAnnotationUrl(String requestUrl, String[] annotationUrls) {
        for (String annotationUrl : annotationUrls) {
            try {
                java.net.URL parsedAnnotationUrl = new java.net.URL(annotationUrl);
                String annotationPath = parsedAnnotationUrl.getPath();
                if (annotationPath.isEmpty() || requestUrl.startsWith(annotationPath)) {
                    return true;
                }
            } catch (java.net.MalformedURLException e) {
                // If URL parsing fails, return true as fallback to avoid incorrectly excluding requests
                return true;
            }
        }
        return false;
    }
}

