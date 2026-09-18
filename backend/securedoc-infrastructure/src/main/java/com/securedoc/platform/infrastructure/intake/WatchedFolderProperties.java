package com.securedoc.platform.infrastructure.intake;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "securedoc.intake.watched-folder")
public class WatchedFolderProperties {
    private boolean enabled = false;
    private String rootPath = "./watched-intake";
    private String triggerFileName = "manifest.json";
    private int incompleteVisibilityMinutes = 15;
    private String acceptedBehavior = "LEAVE";
    private String rejectedBehavior = "LEAVE";
    private String processingDirectory = "_processing";
    private String archiveDirectory = "_archive";
    private String problemDirectory = "_problem";
    private long staleClaimAfterMs = 300000L;

    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public String getRootPath(){return rootPath;} public void setRootPath(String v){rootPath=v;}
    public String getTriggerFileName(){return triggerFileName;} public void setTriggerFileName(String v){triggerFileName=v;}
    public int getIncompleteVisibilityMinutes(){return incompleteVisibilityMinutes;} public void setIncompleteVisibilityMinutes(int v){incompleteVisibilityMinutes=v;}
    public String getAcceptedBehavior(){return acceptedBehavior;} public void setAcceptedBehavior(String v){acceptedBehavior=v;}
    public String getRejectedBehavior(){return rejectedBehavior;} public void setRejectedBehavior(String v){rejectedBehavior=v;}
    public String getProcessingDirectory(){return processingDirectory;} public void setProcessingDirectory(String v){processingDirectory=v;}
    public String getArchiveDirectory(){return archiveDirectory;} public void setArchiveDirectory(String v){archiveDirectory=v;}
    public String getProblemDirectory(){return problemDirectory;} public void setProblemDirectory(String v){problemDirectory=v;}
    public long getStaleClaimAfterMs(){return staleClaimAfterMs;} public void setStaleClaimAfterMs(long v){staleClaimAfterMs=v;}
}
