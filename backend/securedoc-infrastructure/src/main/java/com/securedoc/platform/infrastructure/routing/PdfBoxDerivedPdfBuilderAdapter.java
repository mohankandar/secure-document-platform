package com.securedoc.platform.infrastructure.routing;

import com.securedoc.platform.application.port.out.DerivedPdfBuilderPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;

/** Builds a page-subset PDF exclusively from the permanently redacted master. */
@Component
public class PdfBoxDerivedPdfBuilderAdapter implements DerivedPdfBuilderPort {
    @Override public byte[] build(InputStream redactedPdf,List<Integer> pageNumbers){
        if(pageNumbers==null||pageNumbers.isEmpty()) throw new IllegalArgumentException("At least one derived page is required");
        List<Integer> ordered=pageNumbers.stream().distinct().sorted().toList();
        try{
            byte[] sourceBytes=redactedPdf.readAllBytes();
            try(PDDocument source= Loader.loadPDF(sourceBytes);PDDocument output=new PDDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                for(int page:ordered){if(page<1||page>source.getNumberOfPages())throw new IllegalArgumentException("Derived page number is outside the source PDF");output.importPage(source.getPage(page-1));}
                output.save(bytes);return bytes.toByteArray();
            }
        }catch(IOException e){throw new UncheckedIOException("Failed to build derived redacted PDF",e);}
    }
}
