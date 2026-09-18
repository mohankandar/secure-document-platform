package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ScannerAcquisitionRouter {
    private final ScanBridgeProperties properties;
    private final ScannerStorageHealthService storageHealth;
    private final Map<ScannerCaptureMode, ScannerAcquisitionAdapter> adapters;

    public ScannerAcquisitionRouter(ScanBridgeProperties properties, ScannerStorageHealthService storageHealth,
                                    List<ScannerAcquisitionAdapter> adapters) {
        this.properties = properties;
        this.storageHealth = storageHealth;
        Map<ScannerCaptureMode, ScannerAcquisitionAdapter> indexed = new EnumMap<>(ScannerCaptureMode.class);
        for (ScannerAcquisitionAdapter adapter : adapters) indexed.put(adapter.mode(), adapter);
        this.adapters = Map.copyOf(indexed);
    }

    public ScannerCaptureMode mode() { return properties.getMode(); }

    public ScannerAcquisitionAdapter active() {
        ScannerAcquisitionAdapter adapter = adapters.get(properties.getMode());
        if (adapter == null) throw new IllegalStateException("No acquisition adapter is registered for " + properties.getMode());
        return adapter;
    }

    public boolean available() { return properties.isEnabled() && active().available(); }

    public void assertReadyForNewSession() {
        storageHealth.assertReadyForNewScan();
        active().assertReadyForNewSession();
    }

    public void acquire(ScanSession session) {
        active().acquire(session);
    }
}
