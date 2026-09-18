package com.securedoc.platform.scanbridge.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Converts bridge-owned WIA page images into the one consolidated PDF required by Scanner Intake. */
@Component
public class PhysicalScanPdfAssembler {

    public int assemble(List<Path> pageImages, Path targetPdf, int dpi) {
        if (pageImages == null || pageImages.isEmpty()) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_NO_PAGES",
                    "The physical scanner returned no pages.", HttpStatus.CONFLICT);
        }
        Path normalizedTarget = targetPdf.toAbsolutePath().normalize();
        Path part = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + ".part");
        try {
            Files.createDirectories(normalizedTarget.getParent());
            Files.deleteIfExists(part);
            try (PDDocument document = new PDDocument()) {
                for (Path imagePath : pageImages) {
                    BufferedImage image = ImageIO.read(imagePath.toFile());
                    if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                        throw new IOException("Unreadable scanner page image");
                    }
                    float widthPoints = Math.max(1f, image.getWidth() * 72f / dpi);
                    float heightPoints = Math.max(1f, image.getHeight() * 72f / dpi);
                    PDPage page = new PDPage(new PDRectangle(widthPoints, heightPoints));
                    document.addPage(page);
                    PDImageXObject pdfImage = JPEGFactory.createFromImage(document, image, 0.82f, dpi);
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.drawImage(pdfImage, 0, 0, widthPoints, heightPoints);
                    }
                }
                document.save(part.toFile());
            }
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(part, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.deleteIfExists(part);
                throw new IOException("Atomic move is required for Scanner Bridge crash consistency", ex);
            }
            return pageImages.size();
        } catch (ScanBridgeException ex) {
            throw ex;
        } catch (IOException ex) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) { }
            throw new ScanBridgeException("PHYSICAL_SCAN_PDF_ASSEMBLY_FAILED",
                    "The scanned pages could not be consolidated into a durable PDF.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
