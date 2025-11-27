package com.stablemock.core.context;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stablemock.core.server.WireMockServerManager;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;

/**
 * Manages ExtensionContext.Store operations for storing and retrieving WireMock server state.
 */
public final class ExtensionContextManager {
    
    private ExtensionContextManager() {
        // utility class
    }
    
    public static class ClassLevelStore {
        private final ExtensionContext.Store store;
        
        public ClassLevelStore(ExtensionContext context) {
            this.store = context.getStore(ExtensionContext.Namespace.create(context.getRequiredTestClass()));
        }
        
        public void putServer(WireMockServer server) {
            store.put("wireMockServer", server);
        }
        
        public WireMockServer getServer() {
            return store.get("wireMockServer", WireMockServer.class);
        }
        
        public void putPort(int port) {
            store.put("port", port);
        }
        
        public Integer getPort() {
            return store.get("port", Integer.class);
        }
        
        public void putMode(String mode) {
            store.put("mode", mode);
        }
        
        public String getMode() {
            return store.get("mode", String.class);
        }
        
        public void putTargetUrl(String targetUrl) {
            store.put("targetUrl", targetUrl);
        }
        
        public String getTargetUrl() {
            return store.get("targetUrl", String.class);
        }
        
        public void removeServer() {
            store.remove("wireMockServer");
        }
        
        public void removePort() {
            store.remove("port");
        }
        
        public void putAnnotationInfos(java.util.List<WireMockServerManager.AnnotationInfo> infos) {
            store.put("annotationInfos", infos);
        }
        
        @SuppressWarnings("unchecked")
        public java.util.List<WireMockServerManager.AnnotationInfo> getAnnotationInfos() {
            return store.get("annotationInfos", java.util.List.class);
        }
        
        public void putServers(java.util.List<WireMockServer> servers) {
            store.put("wireMockServers", servers);
        }
        
        @SuppressWarnings("unchecked")
        public java.util.List<WireMockServer> getServers() {
            return store.get("wireMockServers", java.util.List.class);
        }
        
        public void putPorts(java.util.List<Integer> ports) {
            store.put("ports", ports);
        }
        
        @SuppressWarnings("unchecked")
        public java.util.List<Integer> getPorts() {
            return store.get("ports", java.util.List.class);
        }
    }
    
    public static class MethodLevelStore {
        private final ExtensionContext.Store store;
        
        public MethodLevelStore(ExtensionContext context) {
            this.store = context.getStore(ExtensionContext.Namespace.create(context.getUniqueId()));
        }
        
        public void putServer(WireMockServer server) {
            store.put("wireMockServer", server);
        }
        
        public WireMockServer getServer() {
            return store.get("wireMockServer", WireMockServer.class);
        }
        
        public void putPort(int port) {
            store.put("port", port);
        }
        
        public Integer getPort() {
            return store.get("port", Integer.class);
        }
        
        public void putMode(String mode) {
            store.put("mode", mode);
        }
        
        public String getMode() {
            return store.get("mode", String.class);
        }
        
        public void putMappingsDir(File mappingsDir) {
            store.put("mappingsDir", mappingsDir);
        }
        
        public File getMappingsDir() {
            return store.get("mappingsDir", File.class);
        }
        
        public void putTargetUrl(String targetUrl) {
            store.put("targetUrl", targetUrl);
        }
        
        public String getTargetUrl() {
            return store.get("targetUrl", String.class);
        }
        
        public void putUseClassLevelServer(boolean use) {
            store.put("useClassLevelServer", use);
        }
        
        public Boolean getUseClassLevelServer() {
            return store.get("useClassLevelServer", Boolean.class);
        }
        
        public void putExistingRequestCount(int count) {
            store.put("existingRequestCount", count);
        }
        
        public Integer getExistingRequestCount() {
            return store.get("existingRequestCount", Integer.class);
        }
        
        public void putAnnotationInfos(java.util.List<WireMockServerManager.AnnotationInfo> infos) {
            store.put("annotationInfos", infos);
        }
        
        @SuppressWarnings("unchecked")
        public java.util.List<WireMockServerManager.AnnotationInfo> getAnnotationInfos() {
            return store.get("annotationInfos", java.util.List.class);
        }
    }
}

