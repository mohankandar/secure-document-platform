package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Task 0050: the whole output file is looked at, not only the text of its pages: every object of the file (also one no
 * page uses), every name and text string in it, every drawing stream's text and names, and every other stream that is
 * not a picture, a font program or a colour profile. Compared in memory; nothing is kept or logged.
 */
final class PdfWholeFileCheck {

    /** What was found. */
    enum Result { CLEAN, HIDDEN_CONTENT, REDACTED_VALUE }

    private final RedactedValues values;
    private final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Streams of drawing operators that do not say so themselves (page contents, drawn characters). */
    private final Set<COSBase> drawings = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Number tables: character maps, font programs, functions, shadings. No text, and four digits prove nothing. */
    private final Set<COSBase> tables = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<String> TABLE_KEYS = Set.of("ToUnicode", "CIDToGIDMap", "CIDSet", "FontFile", "FontFile2",
            "FontFile3", "UseCMap");
    private boolean hidden;
    private boolean value;

    private PdfWholeFileCheck(RedactedValues values) {
        this.values = values;
    }

    static Result run(PDDocument document, RedactedValues values) throws IOException {
        PdfWholeFileCheck check = new PdfWholeFileCheck(values);
        COSDocument file = document.getDocument();
        for (COSObjectKey key : new ArrayList<>(file.getXrefTable().keySet())) {
            COSObject object = file.getObjectFromPool(key);
            if (object != null && object.getObject() instanceof COSDictionary dictionary) check.sort(dictionary);
        }
        check.look(file.getTrailer());
        for (COSObjectKey key : new ArrayList<>(file.getXrefTable().keySet())) {
            COSObject object = file.getObjectFromPool(key);
            if (object != null) check.look(object);
        }
        for (PDPage page : document.getPages()) {
            try (var content = page.getContents()) {
                if (content != null) check.drawing(new PDFStreamParser(content.readAllBytes()).parse());
            }
        }
        if (check.value) return Result.REDACTED_VALUE;
        return check.hidden ? Result.HIDDEN_CONTENT : Result.CLEAN;
    }

    private void sort(COSDictionary dictionary) {
        if (COSName.PAGE.equals(dictionary.getCOSName(COSName.TYPE))) {
            COSBase contents = dictionary.getDictionaryObject(COSName.CONTENTS);
            if (contents instanceof COSArray array) {
                for (int i = 0; i < array.size(); i++) drawings.add(array.getObject(i));
            } else if (contents != null) {
                drawings.add(contents);
            }
        }
        if (dictionary.getDictionaryObject(COSName.CHAR_PROCS) instanceof COSDictionary characters) {
            for (COSName key : characters.keySet()) drawings.add(characters.getDictionaryObject(key));
        }
        for (COSName key : dictionary.keySet()) {
            if (TABLE_KEYS.contains(key.getName())) tables.add(dictionary.getDictionaryObject(key));
        }
        if (dictionary.containsKey(COSName.FUNCTION_TYPE) || dictionary.containsKey(COSName.SHADING_TYPE)
                || "CMap".equals(dictionary.getNameAsString(COSName.TYPE))) tables.add(dictionary);
    }

    private void look(COSBase base) throws IOException {
        // Not recursive: a file's objects can be nested or chained very deeply.
        java.util.ArrayDeque<COSBase> waiting = new java.util.ArrayDeque<>();
        if (base != null) waiting.push(base);
        while (!waiting.isEmpty()) {
            COSBase item = PdfOutputCleaner.resolve(waiting.pop());
            if (item == null) continue;
            if (item instanceof COSName name) {
                text(name.getName());
                continue;
            }
            if (item instanceof COSString string) {
                text(string.getString());
                text(new String(string.getBytes(), StandardCharsets.ISO_8859_1));
                continue;
            }
            if (!(item instanceof COSDictionary || item instanceof COSArray) || !seen.add(item)) continue;
            if (item instanceof COSStream stream) stream(stream);
            if (item instanceof COSDictionary dictionary) {
                boolean resourceNames = dictionary.keySet().stream().anyMatch(k ->
                        PdfOutputCleaner.resolve(dictionary.getItem(k)) instanceof COSStream);
                for (COSName key : dictionary.keySet()) {
                    text(key.getName());
                    if (PdfOutputCleaner.REFUSED_KEYS.contains(key.getName())
                            && !(resourceNames && dictionary.getDictionaryObject(key) instanceof COSStream s
                                    && s.containsKey(COSName.SUBTYPE) && !"XML".equals(s.getNameAsString(COSName.SUBTYPE)))) {
                        hidden = true;
                    }
                    if (dictionary.getItem(key) != null) waiting.push(dictionary.getItem(key));
                }
            } else {
                COSArray array = (COSArray) item;
                for (int i = 0; i < array.size(); i++) if (array.get(i) != null) waiting.push(array.get(i));
            }
        }
    }

    private void stream(COSStream stream) throws IOException {
        String type = String.valueOf(stream.getNameAsString(COSName.TYPE));
        String subtype = String.valueOf(stream.getNameAsString(COSName.SUBTYPE));
        // Pixels, font programs and colour profiles hold no text. The file's own tables are read as objects.
        if ("Image".equals(subtype) || "XRef".equals(type) || "ObjStm".equals(type)
                || stream.containsKey(COSName.LENGTH1) || Set.of("Type1C", "CIDFontType0C", "OpenType").contains(subtype)
                || (stream.containsKey(COSName.N) && stream.containsKey(COSName.ALTERNATE))) {
            return;
        }
        if (tables.contains(stream)) return;
        if (PdfOutputCleaner.isContent(stream) || drawings.contains(stream)) {
            drawing(PdfOutputCleaner.parse(stream));
            return;
        }
        byte[] bytes;
        try (InputStream in = stream.createInputStream()) {
            bytes = in.readAllBytes();
        }
        // Drawing streams of pages and characters are also looked at as drawings; anything else as text.
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String part : raw.split("[()<>\\[\\]{}/]")) text(part);
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF)
                || (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE))) {
            text(new String(bytes, StandardCharsets.UTF_16));
        }
    }

    /** The strings and names of a drawing, and its strings put together as they are drawn (text split by spacing). */
    private void drawing(List<Object> tokens) {
        List<String> shown = new ArrayList<>();
        for (Object token : tokens) {
            if (token instanceof COSString string) shown.add(string(string));
            else if (token instanceof COSName name) text(name.getName());
            else if (token instanceof COSArray array) shown.add(strings(array));
            else if (token instanceof COSDictionary dictionary) shown.add(dictionaryText(dictionary));
            else if (token instanceof Operator op && (op.getName().equals("ET") || op.getName().equals("BT"))) {
                text(String.join(" ", shown));
                shown.clear();
            }
        }
        text(String.join(" ", shown));
    }

    private static String string(COSString string) {
        return new String(string.getBytes(), StandardCharsets.ISO_8859_1);
    }

    private static String strings(COSArray array) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < array.size(); i++) {
            if (array.getObject(i) instanceof COSString string) text.append(string(string));
        }
        return text.toString();
    }

    private static String dictionaryText(COSDictionary dictionary) {
        StringBuilder text = new StringBuilder();
        for (COSName key : dictionary.keySet()) {
            COSBase item = dictionary.getDictionaryObject(key);
            if (item instanceof COSString string) text.append(' ').append(string.getString());
            else if (item instanceof COSName name) text.append(' ').append(name.getName());
        }
        return text.toString();
    }

    private void text(String text) {
        if (!value && values.appearsIn(text)) value = true;
    }
}
