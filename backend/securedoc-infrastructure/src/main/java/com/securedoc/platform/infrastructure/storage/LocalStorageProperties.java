package com.securedoc.platform.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "securedoc.storage.local")
public class LocalStorageProperties {

    private String rootPath = "./securedoc-storage";

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }
}
