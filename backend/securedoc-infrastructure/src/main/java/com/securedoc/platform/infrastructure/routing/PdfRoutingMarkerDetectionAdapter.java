package com.securedoc.platform.infrastructure.routing;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.RoutingMarkerDetectionPort;
import com.securedoc.platform.application.port.out.RoutingMarkerDetectionResult;
import com.securedoc.platform.domain.routing.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * Deterministic V1 marker scanner. The page may be rendered, but only the fixed
 * TOP_RIGHT/BOTTOM_LEFT crops are decoded/OCR'd. No full-page marker analysis occurs.
 */
@Component
public class PdfRoutingMarkerDetectionAdapter implements RoutingMarkerDetectionPort {
    private static final float DPI=160f;
    private final OcrPort ocr;
    public PdfRoutingMarkerDetectionAdapter(OcrPort ocr){this.ocr=Objects.requireNonNull(ocr);}

    @Override public RoutingMarkerDetectionResult detect(InputStream redactedPdf,List<RoutingRule> rules){
        if(rules==null||rules.isEmpty()) return new RoutingMarkerDetectionResult(Map.of(),Set.of());
        try{
            byte[] bytes=redactedPdf.readAllBytes();
            try(PDDocument doc= Loader.loadPDF(bytes)){
                PDFRenderer renderer=new PDFRenderer(doc); Map<UUID,LinkedHashSet<Integer>> pages=new LinkedHashMap<>(); Set<Integer> ambiguous=new LinkedHashSet<>();
                for(int i=0;i<doc.getNumberOfPages();i++){
                    int pageNumber=i+1; BufferedImage rendered=renderer.renderImageWithDPI(i,DPI, ImageType.RGB);
                    Set<RoutingRule> matched=new LinkedHashSet<>();
                    for(RoutingScanRegion region:regionsNeeded(rules)){
                        BufferedImage crop=crop(rendered,region);
                        List<RoutingRule> regionRules=rules.stream().filter(r->applies(r.scanRegion(),region)).toList();
                        Set<String> decoded=decode(crop);
                        String text=needsText(regionRules)?ocrText(pageNumber,crop):"";
                        String normalizedText=normalize(text);
                        for(RoutingRule rule:regionRules){
                            boolean hit=switch(rule.markerType()){
                                case QR_CODE -> decoded.stream().anyMatch(v->v.startsWith("QR:")&&v.substring(3).equals(rule.markerValue()));
                                case BARCODE -> decoded.stream().anyMatch(v->v.startsWith("BAR:")&&v.substring(4).equals(rule.markerValue()));
                                case TEXT_PATTERN -> !normalize(rule.markerValue()).isBlank()&&normalizedText.contains(normalize(rule.markerValue()));
                            };
                            if(hit) matched.add(rule);
                        }
                    }
                    long destinations=matched.stream().map(r->r.destinationConnectionId().value()).distinct().count();
                    if(destinations>1){ambiguous.add(pageNumber);continue;}
                    for(RoutingRule rule:matched) pages.computeIfAbsent(rule.id(),k->new LinkedHashSet<>()).add(pageNumber);
                }
                Map<UUID,List<Integer>> immutable=new LinkedHashMap<>(); pages.forEach((k,v)->immutable.put(k,List.copyOf(v)));
                return new RoutingMarkerDetectionResult(immutable,ambiguous);
            }
        }catch(IOException e){throw new UncheckedIOException("Failed to scan redacted PDF routing markers",e);}
    }
    private Set<RoutingScanRegion> regionsNeeded(List<RoutingRule> rules){
        LinkedHashSet<RoutingScanRegion> out=new LinkedHashSet<>();
        for(RoutingRule r:rules){if(r.scanRegion()==RoutingScanRegion.BOTH){out.add(RoutingScanRegion.TOP_RIGHT);out.add(RoutingScanRegion.BOTTOM_LEFT);}else out.add(r.scanRegion());}
        return out;
    }
    private boolean applies(RoutingScanRegion configured,RoutingScanRegion actual){return configured==RoutingScanRegion.BOTH||configured==actual;}
    private boolean needsText(List<RoutingRule> rules){return rules.stream().anyMatch(r->r.markerType()==RoutingMarkerType.TEXT_PATTERN);}
    private BufferedImage crop(BufferedImage image,RoutingScanRegion region){
        int w=image.getWidth(),h=image.getHeight(); int cw=Math.max(1,(int)Math.round(w*0.40)),ch=Math.max(1,(int)Math.round(h*0.25));
        return region==RoutingScanRegion.TOP_RIGHT?image.getSubimage(w-cw,0,cw,ch):image.getSubimage(0,h-ch,cw,ch);
    }
    private String ocrText(int page,BufferedImage crop){
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            ImageIO.write(crop,"png",out); return ocr.recognize(new OcrPort.OcrPageInput(page,out.toByteArray(),"image/png",crop.getWidth(),crop.getHeight())).text();
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private Set<String> decode(BufferedImage crop){
        LinkedHashSet<String> values=new LinkedHashSet<>();
        try{
            BinaryBitmap bitmap=new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(crop)));
            Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
            MultiFormatReader delegate=new MultiFormatReader();delegate.setHints(hints);
            Result[] results=new GenericMultipleBarcodeReader(delegate).decodeMultiple(bitmap,hints);
            if(results!=null) for(Result r:results){if(r==null||r.getText()==null)continue;values.add((r.getBarcodeFormat()==BarcodeFormat.QR_CODE?"QR:":"BAR:")+r.getText().trim());}
        }catch(com.google.zxing.NotFoundException ignored){/* no marker in this configured crop */}
        return values;
    }
    private String normalize(String value){return value==null?"":value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");}
}
