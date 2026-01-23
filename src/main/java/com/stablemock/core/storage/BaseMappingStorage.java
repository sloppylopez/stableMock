package com.stablemock.core.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

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
            if (remainingFiles == null || remainingFiles.length == 0) {
                if (!baseMappingsSubDir.delete()) {
                    logger.warn("Failed to delete base mappings directory: {}", baseMappingsSubDir.getAbsolutePath());
                }
            }
        }
        
        if (baseFilesDir.exists() && baseFilesDir.isDirectory()) {
            File[] remainingFiles = baseFilesDir.listFiles();
            if (remainingFiles == null || remainingFiles.length == 0) {
                if (!baseFilesDir.delete()) {
                    logger.warn("Failed to delete base files directory: {}", baseFilesDir.getAbsolutePath());
                }
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

