package com.securedoc.platform.infrastructure.redaction;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Task 0050 (R2 part 2): nothing hidden reaches the redacted output. An untouched page is copied into the new document
 * with its visible content and text only:
 * <ul>
 *   <li>the page keeps only its size, rotation, content and the resources its content uses (a shared resource list can
 *       hold pictures of other, redacted, pages);</li>
 *   <li>annotations and form fields become part of the page as they look, then are gone (links have no look and are
 *       dropped; nothing on a page stays active);</li>
 *   <li>layers are gone: visible ones are ordinary content; a page that can show a hidden one is not copied at all (it
 *       is drawn as an image, where a hidden layer is not visible);</li>
 *   <li>metadata, attachments, actions, scripts, private application data and the alternative texts of tagged content
 *       are removed at every level.</li>
 * </ul>
 * The new document has no metadata except a neutral producer and the dates, and no history of earlier saves.
 */
final class PdfOutputCleaner {
    static final String PRODUCER = "LexaVault";

    /** What a copied page keeps of its own dictionary. */
    private static final Set<String> PAGE_KEYS = Set.of("Type", "Parent", "Resources", "MediaBox", "CropBox", "Rotate",
            "UserUnit", "Contents", "Group");
    /** Removed from every object of the output, at every level. */
    static final Set<String> REMOVED_KEYS = Set.of("Metadata", "PieceInfo", "AF", "AA", "JS", "JavaScript", "OpenAction",
            "EmbeddedFiles", "EF", "OC", "OCProperties", "StructParent", "StructParents", "StructTreeRoot", "Thumb",
            "LastModified", "OPI", "Ref", "Alternates", "Measure", "PtData", "AcroForm", "Annots", "Names", "Dests",
            "Perms", "DSS", "Collection", "SpiderInfo", "IDTree", "Properties");
    /** Keys that mark something active or hidden; the final check refuses an output that holds one. */
    static final Set<String> REFUSED_KEYS = Set.of("Metadata", "PieceInfo", "AF", "AA", "JS", "JavaScript", "OpenAction",
            "EmbeddedFiles", "EF", "OC", "OCProperties", "AcroForm", "Annots", "StructTreeRoot");
    private static final COSName XOBJECT = COSName.XOBJECT;

    private PdfOutputCleaner() {
    }

    /** True when the page could show content of a layer that is not shown: it must not be copied. */
    static boolean hasHiddenLayer(PDDocument source, PDPage page) throws IOException {
        PDOptionalContentProperties layers = source.getDocumentCatalog().getOCProperties();
        List<COSDictionary> used = new ArrayList<>();
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collectLayers(page.getResources() == null ? null : page.getResources().getCOSObject(), used, seen);
        for (PDAnnotation annotation : page.getAnnotations()) collectLayers(annotation.getCOSObject(), used, seen);
        if (used.isEmpty()) return false;
        if (layers == null) return false;
        for (COSDictionary layer : used) {
            // A membership (OCMD) has its own visibility rules: only plain, switched-on groups are certain.
            if (!COSName.OCG.equals(layer.getCOSName(COSName.TYPE))) return true;
            if (!layers.isGroupEnabled(new PDOptionalContentGroup(layer))) return true;
        }
        return false;
    }

    private static void collectLayers(COSBase base, List<COSDictionary> used, Set<COSBase> seen) {
        COSBase value = resolve(base);
        if (value == null || !seen.add(value)) return;
        if (value instanceof COSDictionary dictionary) {
            COSName type = dictionary.getCOSName(COSName.TYPE);
            if (COSName.OCG.equals(type) || COSName.getPDFName("OCMD").equals(type)) {
                used.add(dictionary);
                return;
            }
            for (COSName key : dictionary.keySet()) {
                if (!COSName.PARENT.equals(key) && !COSName.P.equals(key)) collectLayers(dictionary.getItem(key), used, seen);
            }
        } else if (value instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) collectLayers(array.get(i), used, seen);
        }
    }

    /** Copies an untouched page: its visible content and text, with its annotations as they look. */
    static void copyPage(PDDocument source, PDDocument output, PDPage page) throws IOException {
        List<PDAnnotation> annotations = page.getAnnotations();
        PDPage copy = output.importPage(page);
        COSDictionary resources = page.getResources() == null ? new COSDictionary()
                : new COSDictionary(page.getResources().getCOSObject());
        for (COSName category : new ArrayList<>(resources.keySet())) {
            // Each copied page has its own list, so one page's clean-up never changes another's.
            if (resources.getDictionaryObject(category) instanceof COSDictionary shared) {
                resources.setItem(category, new COSDictionary(shared));
            }
        }
        copy.getCOSObject().setItem(COSName.RESOURCES, resources);
        copy.setMediaBox(page.getMediaBox());
        copy.setCropBox(page.getCropBox());
        copy.setRotation(page.getRotation());
        COSDictionary dictionary = copy.getCOSObject();
        for (COSName key : new ArrayList<>(dictionary.keySet())) {
            if (!PAGE_KEYS.contains(key.getName())) dictionary.removeItem(key);
        }
        keepUsed(copy, resources);
        for (PDAnnotation annotation : annotations) flatten(source, output, copy, annotation);
    }

    /** Pictures and drawings the page's content never draws are not copied. */
    private static void keepUsed(PDPage page, COSDictionary resources) throws IOException {
        if (!(resources.getDictionaryObject(XOBJECT) instanceof COSDictionary objects)) return;
        Set<String> used = new HashSet<>();
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        try (var content = page.getContents()) {
            if (content != null) drawn(new PDFStreamParser(content.readAllBytes()).parse(), used);
        }
        // Forms, patterns and drawn characters without a resource list of their own use the page's.
        collectDrawn(resources, used, seen);
        for (COSName name : new ArrayList<>(objects.keySet())) {
            if (!used.contains(name.getName())) objects.removeItem(name);
        }
    }

    private static void collectDrawn(COSBase base, Set<String> used, Set<COSBase> seen) throws IOException {
        COSBase value = resolve(base);
        if (value == null || !seen.add(value)) return;
        if (value instanceof COSStream stream && isContent(stream) && !stream.containsKey(COSName.RESOURCES)) {
            drawn(parse(stream), used);
        }
        if (value instanceof COSDictionary dictionary) {
            for (COSName key : dictionary.keySet()) collectDrawn(dictionary.getItem(key), used, seen);
        } else if (value instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) collectDrawn(array.get(i), used, seen);
        }
    }

    private static void drawn(List<Object> tokens, Set<String> used) {
        for (int i = 1; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof Operator op && op.getName().equals("Do")
                    && tokens.get(i - 1) instanceof COSName name) {
                used.add(name.getName());
            }
        }
    }

    /** Draws an annotation's normal appearance onto the page, in its place (PDF 32000-1, 12.5.5). */
    private static void flatten(PDDocument source, PDDocument output, PDPage page, PDAnnotation annotation)
            throws IOException {
        if (annotation instanceof PDAnnotationPopup || annotation instanceof PDAnnotationLink
                || annotation.isHidden() || annotation.isNoView()) return;
        PDAppearanceStream appearance = annotation.getNormalAppearanceStream();
        if (appearance == null) {
            try {
                annotation.constructAppearances(source);
            } catch (RuntimeException noLook) {
                return;
            }
            appearance = annotation.getNormalAppearanceStream();
        }
        PDRectangle place = annotation.getRectangle();
        // Task 0053: nobody sees what is outside the page, and extraction does not read it: it is not drawn.
        PDRectangle shown = page.getCropBox();
        if (place != null && (place.getUpperRightX() <= shown.getLowerLeftX()
                || place.getLowerLeftX() >= shown.getUpperRightX() || place.getUpperRightY() <= shown.getLowerLeftY()
                || place.getLowerLeftY() >= shown.getUpperRightY())) return;
        if (appearance == null || place == null || appearance.getBBox() == null) return;
        PDRectangle box = appearance.getBBox();
        if (box.getWidth() <= 0 || box.getHeight() <= 0 || place.getWidth() <= 0 || place.getHeight() <= 0) return;
        Rectangle2D bounds = appearance.getMatrix().createAffineTransform()
                .createTransformedShape(box.toGeneralPath()).getBounds2D();
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) return;
        Matrix toPlace = Matrix.getTranslateInstance(place.getLowerLeftX(), place.getLowerLeftY());
        toPlace.concatenate(Matrix.getScaleInstance((float) (place.getWidth() / bounds.getWidth()),
                (float) (place.getHeight() / bounds.getHeight())));
        toPlace.concatenate(Matrix.getTranslateInstance((float) -bounds.getX(), (float) -bounds.getY()));
        try (PDPageContentStream content = new PDPageContentStream(output, page, PDPageContentStream.AppendMode.APPEND,
                true, true)) {
            content.saveGraphicsState();
            content.transform(toPlace);
            content.drawForm(new PDFormXObject(appearance.getCOSObject()));
            content.restoreGraphicsState();
        }
    }

    /** Last step before saving: no object of the output keeps anything hidden, and the document says nothing else. */
    static void clean(PDDocument output) throws IOException {
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : output.getPages()) {
            COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
            rewritePage(output, page, contents);
            scrub(page.getCOSObject(), seen);
        }
        PDDocumentInformation information = new PDDocumentInformation();
        information.setProducer(PRODUCER);
        Calendar now = new GregorianCalendar();
        information.setCreationDate(now);
        information.setModificationDate(now);
        output.setDocumentInformation(information);
        COSDictionary catalog = output.getDocumentCatalog().getCOSObject();
        for (COSName key : new ArrayList<>(catalog.keySet())) {
            if (!Set.of("Type", "Pages", "Version").contains(key.getName())) catalog.removeItem(key);
        }
    }

    /** A page's content can be several streams that only together are well formed. */
    private static void rewritePage(PDDocument output, PDPage page, COSBase contents) throws IOException {
        if (contents == null) return;
        List<Object> tokens;
        try (var content = page.getContents()) {
            tokens = new PDFStreamParser(content.readAllBytes()).parse();
        }
        if (!withoutMarks(tokens)) return;
        var stream = new org.apache.pdfbox.pdmodel.common.PDStream(output);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(tokens);
        }
        page.setContents(stream);
    }

    private static void scrub(COSBase base, Set<COSBase> seen) throws IOException {
        COSBase value = resolve(base);
        if (value == null || !seen.add(value)) return;
        if (value instanceof COSStream stream && isContent(stream)) {
            List<Object> tokens = parse(stream);
            if (withoutMarks(tokens)) {
                try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                    new ContentStreamWriter(out).writeTokens(tokens);
                }
            }
        }
        if (value instanceof COSDictionary dictionary) {
            boolean page = COSName.PAGE.equals(dictionary.getCOSName(COSName.TYPE));
            for (COSName key : new ArrayList<>(dictionary.keySet())) {
                if (removed(dictionary, key)) dictionary.removeItem(key);
                else if (!(page && COSName.PARENT.equals(key))) scrub(dictionary.getItem(key), seen);
            }
        } else if (value instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) scrub(array.get(i), seen);
        }
    }

    /** "Properties" and the like are names of fonts or drawings inside a resource category: kept there. */
    private static boolean removed(COSDictionary dictionary, COSName key) {
        if (!REMOVED_KEYS.contains(key.getName())) return false;
        // A resource category maps free names to objects; its own names are not keys with a meaning.
        COSBase value = dictionary.getDictionaryObject(key);
        boolean named = value instanceof COSStream stream && (stream.containsKey(COSName.SUBTYPE)
                && Set.of("Form", "Image").contains(String.valueOf(stream.getNameAsString(COSName.SUBTYPE))));
        boolean font = value instanceof COSDictionary d && COSName.FONT.equals(d.getCOSName(COSName.TYPE));
        return !(named || font);
    }

    /**
     * Marked content loses its properties: a layer's marks, and the alternative and actual texts of tagged content
     * (text nobody sees). The content between the marks stays. True when something changed.
     */
    private static boolean withoutMarks(List<Object> tokens) {
        boolean changed = false;
        List<Object> kept = new ArrayList<>(tokens.size());
        for (Object token : tokens) {
            if (token instanceof Operator op) {
                if (op.getName().equals("BDC") && kept.size() >= 2) {
                    kept.remove(kept.size() - 1);
                    kept.remove(kept.size() - 1);
                    kept.add(COSName.getPDFName("Span"));
                    kept.add(Operator.getOperator("BMC"));
                    changed = true;
                    continue;
                }
                if (op.getName().equals("DP") && kept.size() >= 2) {
                    kept.remove(kept.size() - 1);
                    kept.remove(kept.size() - 1);
                    changed = true;
                    continue;
                }
                if (op.getName().equals("MP") && !kept.isEmpty()) {
                    kept.remove(kept.size() - 1);
                    changed = true;
                    continue;
                }
                if (op.getName().equals("BMC") && !kept.isEmpty()
                        && !COSName.getPDFName("Span").equals(kept.get(kept.size() - 1))) {
                    kept.set(kept.size() - 1, COSName.getPDFName("Span"));
                    changed = true;
                }
            }
            kept.add(token);
        }
        if (changed) {
            tokens.clear();
            tokens.addAll(kept);
        }
        return changed;
    }

    /** A form, a tiling pattern or a drawn character: a stream of drawing operators. */
    static boolean isContent(COSStream stream) {
        return "Form".equals(stream.getNameAsString(COSName.SUBTYPE))
                || stream.getInt(COSName.getPDFName("PatternType"), 0) == 1;
    }

    static List<Object> parse(COSStream stream) throws IOException {
        try (var in = stream.createInputStream()) {
            return new PDFStreamParser(in.readAllBytes()).parse();
        }
    }

    static COSBase resolve(COSBase base) {
        return base instanceof COSObject object ? object.getObject() : base;
    }
}
