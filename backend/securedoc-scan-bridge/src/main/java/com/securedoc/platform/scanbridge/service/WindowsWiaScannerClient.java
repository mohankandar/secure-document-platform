package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows Image Acquisition (WIA) process boundary.
 *
 * <p>The Scanner Bridge deliberately keeps COM/WIA outside the JVM. The PowerShell helper is
 * shipped inside the signed bridge artifact, materialized beneath the bridge-owned work root,
 * and receives only bridge-controlled paths and non-sensitive scanner configuration.</p>
 */
@Component
public class WindowsWiaScannerClient {
    private static final String SCRIPT_RESOURCE = "physical/windows-wia-acquire.ps1";
    private static final String SCRIPT_NAME = "windows-wia-acquire.ps1";

    private final ScanBridgeProperties properties;
    private final FileScanSessionStore store;
    private volatile long nextAvailabilityProbeNanos;
    private volatile boolean lastAvailability;

    public WindowsWiaScannerClient(ScanBridgeProperties properties, FileScanSessionStore store) {
        this.properties = properties;
        this.store = store;
    }

    public boolean available() {
        if (!isConfiguredForWia() || !isWindows()) return false;
        long now = System.nanoTime();
        if (now < nextAvailabilityProbeNanos) return lastAvailability;
        synchronized (this) {
            now = System.nanoTime();
            if (now < nextAvailabilityProbeNanos) return lastAvailability;
            lastAvailability = probe();
            long cacheMs = Math.max(1000L, properties.getPhysical().getAvailabilityCacheMs());
            nextAvailabilityProbeNanos = now + TimeUnit.MILLISECONDS.toNanos(cacheMs);
            return lastAvailability;
        }
    }

    public void assertAvailable() {
        if (!isConfiguredForWia()) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_PROVIDER_UNSUPPORTED",
                    "The configured physical scanner provider is not supported by this Scanner Bridge.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!isWindows()) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_WINDOWS_REQUIRED",
                    "The WIA physical scanner adapter requires Windows.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!available()) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_NOT_AVAILABLE",
                    "No configured WIA scanner is currently available on this workstation.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** Acquire one physical scan job as page images in a bridge-owned directory. */
    public List<Path> acquire(Path outputDirectory) {
        assertAvailable();
        try {
            Files.createDirectories(outputDirectory);
            try (var files = Files.list(outputDirectory)) {
                if (files.findAny().isPresent()) {
                    throw new ScanBridgeException("PHYSICAL_CAPTURE_DIRECTORY_NOT_EMPTY",
                            "The physical scanner capture directory is not empty.", HttpStatus.CONFLICT);
                }
            }

            List<String> command = baseCommand(false);
            command.add("-OutputDirectory"); command.add(outputDirectory.toAbsolutePath().normalize().toString());
            command.add("-Source"); command.add(normalizedSource());
            command.add("-Duplex"); command.add(Boolean.toString(properties.getPhysical().isDuplex()));
            command.add("-Dpi"); command.add(Integer.toString(validatedDpi()));
            command.add("-MaxPages"); command.add(Integer.toString(validatedMaxPages()));

            ProcessResult result = run(command, Duration.ofSeconds(validatedTimeoutSeconds()));
            if (result.timedOut()) {
                throw new ScanBridgeException("PHYSICAL_SCANNER_TIMEOUT",
                        "The physical scanner did not finish within the configured acquisition timeout.",
                        HttpStatus.GATEWAY_TIMEOUT);
            }
            if (result.exitCode() != 0) throw mapAcquisitionFailure(result.exitCode(), result.output());

            List<Path> images;
            try (var stream = Files.list(outputDirectory)) {
                images = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bmp"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            if (images.isEmpty()) {
                throw new ScanBridgeException("PHYSICAL_SCANNER_NO_PAGES",
                        "The physical scanner returned no pages.", HttpStatus.CONFLICT);
            }
            return List.copyOf(images);
        } catch (ScanBridgeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_IO_FAILED",
                    "The physical scanner output could not be prepared safely.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean probe() {
        try {
            ProcessResult result = run(baseCommand(true), Duration.ofSeconds(8));
            return !result.timedOut() && result.exitCode() == 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private List<String> baseCommand(boolean probe) {
        List<String> command = new ArrayList<>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        if (probe) command.add("-NonInteractive");
        command.add("-ExecutionPolicy"); command.add("Bypass");
        command.add("-File"); command.add(materializeScript().toString());
        if (probe) command.add("-Probe");
        if (notBlank(properties.getPhysical().getDeviceId())) {
            command.add("-DeviceId"); command.add(properties.getPhysical().getDeviceId().trim());
        }
        if (notBlank(properties.getPhysical().getDeviceName())) {
            command.add("-DeviceName"); command.add(properties.getPhysical().getDeviceName().trim());
        }
        return command;
    }

    private Path materializeScript() {
        Path helperDir = store.workRoot().resolve(".physical-adapter").normalize();
        if (!helperDir.startsWith(store.workRoot())) throw new IllegalStateException("Invalid physical adapter helper path");
        Path target = helperDir.resolve(SCRIPT_NAME);
        Path temp = helperDir.resolve(SCRIPT_NAME + ".tmp");
        try {
            Files.createDirectories(helperDir);
            byte[] bytes;
            try (InputStream input = new ClassPathResource(SCRIPT_RESOURCE).getInputStream()) {
                bytes = input.readAllBytes();
            }
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.deleteIfExists(temp);
                throw new IOException("Atomic helper deployment is required", ex);
            }
            return target.toAbsolutePath().normalize();
        } catch (IOException ex) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_HELPER_UNAVAILABLE",
                    "The WIA scanner helper could not be prepared.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ProcessResult run(List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(Math.max(1, timeout.toSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new ProcessResult(-1, "", true);
            }
            // The helper emits only safe technical status/error codes, never document content.
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (output.length() > 4096) output = output.substring(0, 4096);
            return new ProcessResult(process.exitValue(), output, false);
        } catch (IOException ex) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_POWERSHELL_UNAVAILABLE",
                    "Windows PowerShell is required for the WIA scanner adapter.", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new ScanBridgeException("PHYSICAL_SCANNER_INTERRUPTED",
                    "The physical scanner operation was interrupted.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private ScanBridgeException mapAcquisitionFailure(int exitCode, String output) {
        if (exitCode == 20 || contains(output, "LEXAVAULT_WIA_NO_SCANNER")) {
            return new ScanBridgeException("PHYSICAL_SCANNER_NOT_AVAILABLE",
                    "No WIA scanner is currently available.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (exitCode == 21 || contains(output, "LEXAVAULT_WIA_DEVICE_NOT_FOUND")) {
            return new ScanBridgeException("PHYSICAL_SCANNER_DEVICE_NOT_FOUND",
                    "The configured physical scanner was not found.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (exitCode == 23 || contains(output, "LEXAVAULT_WIA_CANCELLED")) {
            return new ScanBridgeException("PHYSICAL_SCAN_CANCELLED",
                    "The physical scan was cancelled before a document was acquired.", HttpStatus.CONFLICT);
        }
        if (exitCode == 25 || contains(output, "LEXAVAULT_WIA_MAX_PAGES_REACHED")) {
            return new ScanBridgeException("PHYSICAL_SCAN_MAX_PAGES_REACHED",
                    "The scanner reached the configured maximum page count before the feeder was empty.",
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return new ScanBridgeException("PHYSICAL_SCAN_ACQUISITION_FAILED",
                "The WIA scanner could not complete the scan job.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private boolean isConfiguredForWia() {
        return "WIA".equalsIgnoreCase(properties.getPhysical().getProvider());
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String normalizedSource() {
        String source = properties.getPhysical().getSource();
        source = source == null ? "AUTO" : source.trim().toUpperCase(Locale.ROOT);
        if (!List.of("AUTO", "FEEDER", "FLATBED").contains(source)) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_SOURCE_INVALID",
                    "Physical scanner source must be AUTO, FEEDER, or FLATBED.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return source;
    }

    private int validatedDpi() {
        int dpi = properties.getPhysical().getDpi();
        if (dpi < 75 || dpi > 1200) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_DPI_INVALID",
                    "Physical scanner DPI must be between 75 and 1200.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return dpi;
    }

    private int validatedMaxPages() {
        int maxPages = properties.getPhysical().getMaxPages();
        if (maxPages < 1 || maxPages > 1000) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_MAX_PAGES_INVALID",
                    "Physical scanner max-pages must be between 1 and 1000.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return maxPages;
    }

    private int validatedTimeoutSeconds() {
        int seconds = properties.getPhysical().getAcquisitionTimeoutSeconds();
        if (seconds < 30 || seconds > 3600) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_TIMEOUT_INVALID",
                    "Physical scanner acquisition timeout must be between 30 and 3600 seconds.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return seconds;
    }

    private boolean contains(String value, String token) {
        return value != null && value.contains(token);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record ProcessResult(int exitCode, String output, boolean timedOut) { }
}
