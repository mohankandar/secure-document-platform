package com.securedoc.platform.infrastructure.routing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.exception.*;
import com.securedoc.platform.application.port.out.DerivedDestinationDeliveryPort;
import com.securedoc.platform.domain.integration.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Typed V1 derived-document transport for HTTP_API and secure deployment-managed FILE_SHARE. */
@Component
public class DerivedDestinationDeliveryAdapter implements DerivedDestinationDeliveryPort {
    static final String SENSITIVITY_HEADER="X-LexaVault-Sensitivity-Level";
    static final String CASE_SENSITIVITY_HEADER="X-LexaVault-Case-Sensitivity-Level";
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DerivedDestinationDeliveryAdapter.class);
    private final ObjectMapper mapper; private final HttpClient client;
    /** Hardening: secret references limited to approved names; the app's one rule and resolver. */
    private final com.securedoc.platform.infrastructure.secrets.IntegrationSecrets secrets;
    /** Task 0027: with export jobs on, one attempt per call; the export job spaces the tries. */
    private final boolean singleAttempt;
    @Autowired
    public DerivedDestinationDeliveryAdapter(ObjectMapper mapper,@org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-mode:inline}") String exportMode,
                                             com.securedoc.platform.infrastructure.secrets.IntegrationSecrets secrets){
        this(mapper,HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                com.securedoc.platform.application.pipeline.ExportMode.parse(exportMode)==com.securedoc.platform.application.pipeline.ExportMode.JOB,secrets);
    }
    public DerivedDestinationDeliveryAdapter(ObjectMapper mapper){this(mapper,HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());}
    DerivedDestinationDeliveryAdapter(ObjectMapper mapper,HttpClient client){this(mapper,client,false);}
    DerivedDestinationDeliveryAdapter(ObjectMapper mapper,HttpClient client,boolean singleAttempt){this(mapper,client,singleAttempt,com.securedoc.platform.infrastructure.secrets.IntegrationSecrets.NONE);}
    DerivedDestinationDeliveryAdapter(ObjectMapper mapper,HttpClient client,boolean singleAttempt,com.securedoc.platform.infrastructure.secrets.IntegrationSecrets secrets){this.mapper=Objects.requireNonNull(mapper);this.client=Objects.requireNonNull(client);this.singleAttempt=singleAttempt;this.secrets=Objects.requireNonNull(secrets);}
    @Override public DeliveryReceipt deliver(IntegrationConnection c,DeliveryRequest request){
        validate(c); return switch(c.providerType()){case HTTP_API->deliverHttp(c,request);case FILE_SHARE->deliverFile(c,request);default->throw invalid("Unsupported derived delivery provider",null);};
    }
    @Override public void testConnection(IntegrationConnection c){
        validate(c); if(c.providerType()==IntegrationProviderType.FILE_SHARE){Path root=safeRoot(c);if(!Files.isDirectory(root)||!Files.isWritable(root))throw invalid("Derived file-share destination is not writable",null);return;}
        HttpRequest req=authorized(c,HttpRequest.newBuilder(endpoint(c)).timeout(Duration.ofSeconds(c.timeoutSeconds())).method("HEAD",HttpRequest.BodyPublishers.noBody())).build();
        HttpResponse<Void> resp=send(req,HttpResponse.BodyHandlers.discarding());int status=resp.statusCode();if(status==401||status==403||status==404||status>=500)throw failure(status>=500, status>=500?TechnicalFailureCode.DELIVERY_IO:TechnicalFailureCode.DELIVERY_DESTINATION_INVALID,"Derived HTTP destination health check failed",null);
    }
    private DeliveryReceipt deliverHttp(IntegrationConnection c,DeliveryRequest r){return withRetry(c,()->{
        HttpRequest.Builder b=authorized(c,HttpRequest.newBuilder(endpoint(c))).timeout(Duration.ofSeconds(c.timeoutSeconds()))
                .header("Content-Type",r.mimeType())
                .header("Idempotency-Key",r.sourceDerivedDocumentId())
                .header("X-Source-Derived-Document-Id",r.sourceDerivedDocumentId())
                .header("X-Original-File-Name",safeHeader(r.fileName()));
        if(r.sha256()!=null)b.header("X-Content-SHA256",r.sha256());
        if(r.markerValue()!=null)b.header("X-Routing-Marker",safeHeader(r.markerValue()));
        addMetadataHeader(b,"X-LexaVault-Package-Id",r.metadata().get("packageId"));
        addMetadataHeader(b,"X-LexaVault-Source-Document-Id",r.metadata().get("sourceDocumentId"));
        addMetadataHeader(b,"X-LexaVault-Routing-Pages",r.metadata().get("pages"));
        addMetadataHeader(b,"X-LexaVault-Destination-Label",r.metadata().get("destinationLabel"));
        // Task 0021: the source document's level and the case level, as the source system set them.
        addMetadataHeader(b,SENSITIVITY_HEADER,r.metadata().get(DerivedDestinationDeliveryPort.SENSITIVITY_LEVEL));
        addMetadataHeader(b,CASE_SENSITIVITY_HEADER,r.metadata().get(DerivedDestinationDeliveryPort.CASE_SENSITIVITY_LEVEL));
        HttpResponse<String> resp=send(b.POST(HttpRequest.BodyPublishers.ofInputStream(r.contentSource()::open)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try{ require2xx(resp.statusCode(),"Derived HTTP delivery failed"); }
        catch(TechnicalDependencyException e){ throw com.securedoc.platform.infrastructure.delivery.RetryAfter.mark(e,resp).withDestinationCode(destinationCode(resp)); } // task 0028: a 429 keeps its Retry-After; task 0030: the destination's own code (safe pattern only)
        String ref=null;
        try{
            if(resp.body()!=null&&!resp.body().isBlank()){
                JsonNode j=mapper.readTree(resp.body());
                if(j.hasNonNull("receiptId"))ref=j.get("receiptId").asString("");
                else if(j.hasNonNull("documentId"))ref=j.get("documentId").asString("");
            }
        }catch(Exception e){
            throw failure(true,TechnicalFailureCode.DELIVERY_IO,"Derived destination returned an unreadable receipt",e).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_007);
        }
        if(ref==null||ref.isBlank())throw failure(true,TechnicalFailureCode.DELIVERY_IO,"Derived destination did not return a durable receipt",null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_007);
        return new DeliveryReceipt(ref,c.endpoint());});}
    private DeliveryReceipt deliverFile(IntegrationConnection c,DeliveryRequest r){
        Path root=safeRoot(c);String name=r.sourceDerivedDocumentId()+"__"+sanitize(r.fileName());Path target=root.resolve(name).normalize();if(!target.startsWith(root))throw invalid("Derived file path escaped configured root",null);
        Path temp=null;
        try{
            Files.createDirectories(root);
            temp=Files.createTempFile(root,"derived-",".tmp");
            try(InputStream in=r.contentSource().open();
                FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);
                OutputStream out=Channels.newOutputStream(channel)){
                in.transferTo(out);
                out.flush();
                channel.force(true);
            }
            try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
            temp=null;
            forceDirectory(c,root);
            // Task 0023 (B4, #222): the receipt names the delivery by its id only. The file name and the share
            // path stay on the share; they are not stored in the delivery row or returned by the API.
            return new DeliveryReceipt(r.sourceDerivedDocumentId(),"file-share:"+r.sourceDerivedDocumentId());
        }catch(IOException e){throw failure(true,TechnicalFailureCode.DELIVERY_IO,"Derived file-share delivery failed",e);}
        finally{if(temp!=null)deleteTempBestEffort(c,r,temp);}
    }
    /** A temp file left after a failure holds redacted output: remove it, and log the delivery id (never the path) if that fails. */
    private void deleteTempBestEffort(IntegrationConnection c,DeliveryRequest r,Path temp){
        try{Files.deleteIfExists(temp);}
        catch(IOException e){log.warn("DERIVED_FILE_SHARE_TEMP_CLEANUP_FAILED connection={} derivedDocument={} type={}",c.id(),r.sourceDerivedDocumentId(),e.getClass().getSimpleName());}
    }
    /** Task 0030: the {@code code} field of a JSON answer, or null when there is none or it can't be read. */
    private String destinationCode(HttpResponse<String> resp){
        if(resp.body()==null||resp.body().isBlank())return null;
        try{JsonNode code=mapper.readTree(resp.body()).get("code");return code==null||code.isNull()||!code.isValueNode()?null:code.asString();}
        catch(Exception unreadable){return null;}
    }
    private void addMetadataHeader(HttpRequest.Builder b,String name,String value){if(value!=null&&!value.isBlank())b.header(name,safeHeader(value));}
    private void forceDirectory(IntegrationConnection c,Path root){try(FileChannel dir=FileChannel.open(root,StandardOpenOption.READ)){dir.force(true);}catch(IOException|UnsupportedOperationException e){/* best-effort directory metadata sync after file fsync + move (Windows cannot open a directory) */log.debug("DERIVED_FILE_SHARE_DIRECTORY_SYNC_SKIPPED connection={} type={}",c.id(),e.getClass().getSimpleName());}}
    private void validate(IntegrationConnection c){if(c==null||!c.enabled()||c.purpose()!=IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY||c.direction()!=IntegrationDirection.OUTBOUND)throw invalid("Connection is not an enabled derived-document destination",null);if(c.providerType()!=IntegrationProviderType.HTTP_API&&c.providerType()!=IntegrationProviderType.FILE_SHARE)throw invalid("Unsupported derived-document provider",null);}
    private URI endpoint(IntegrationConnection c){try{URI u=URI.create(c.endpoint());if(!"http".equalsIgnoreCase(u.getScheme())&&!"https".equalsIgnoreCase(u.getScheme()))throw new IllegalArgumentException();}catch(Exception e){throw invalid("Derived HTTP endpoint is invalid",e);}
        // Hardening (task 0110): outbound addresses limited, checked again after the name is resolved.
        if(com.securedoc.platform.application.integration.OutboundAddressRule.check(c.endpoint(),c.allowedHosts()).isPresent())
            throw invalid("Derived HTTP endpoint is not allowed",null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.CFG_003);
        return URI.create(c.endpoint());}
    private Path safeRoot(IntegrationConnection c){try{return Path.of(c.fileShareRoot()).toAbsolutePath().normalize();}catch(Exception e){throw invalid("Derived file-share root is invalid",e);}}
    private HttpRequest.Builder authorized(IntegrationConnection c,HttpRequest.Builder b){if(c.authenticationType()==IntegrationAuthenticationType.NONE)return b;String secret=secret(c);return switch(c.authenticationType()){case BASIC->b.header("Authorization","Basic "+Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));case API_KEY->b.header("X-API-Key",secret);case JWT->b.header("Authorization","Bearer "+secret);case OAUTH2_CLIENT_CREDENTIALS,MTLS->throw invalid("Derived V1 authentication type requires a provider-specific connector",null);case NONE->b;};}
    private String secret(IntegrationConnection c){try{return secrets.read(c);}catch(com.securedoc.platform.infrastructure.secrets.IntegrationSecrets.NotRead e){throw invalid(e.getMessage(),null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_005);}}
    private <T> HttpResponse<T> send(HttpRequest req,HttpResponse.BodyHandler<T> h){try{return client.send(req,h);}catch(InterruptedException e){Thread.currentThread().interrupt();throw failure(true,TechnicalFailureCode.DELIVERY_IO,"Derived delivery interrupted",e);}catch(IOException e){throw failure(true,TechnicalFailureCode.DELIVERY_IO,"Derived destination unavailable",e);}}
    private <T>T withRetry(IntegrationConnection c,java.util.function.Supplier<T> op){TechnicalDependencyException last=null;int max=singleAttempt||com.securedoc.platform.application.pipeline.ExportJobAttempt.running()?1:Math.max(1,c.retryMaxAttempts()+1);for(int i=1;i<=max;i++){try{return op.get();}catch(TechnicalDependencyException e){last=e;if(!e.retryable()||i==max)throw e.withAttempts(i);try{Thread.sleep(Math.min(1000L*i,3000L));}catch(InterruptedException x){Thread.currentThread().interrupt();throw e.withAttempts(i);}}}throw last;}
    private void require2xx(int status,String message){if(status<200||status>=300){boolean retry=status==429||status>=500;throw failure(retry,retry?TechnicalFailureCode.DELIVERY_IO:TechnicalFailureCode.DELIVERY_DESTINATION_INVALID,message,null).withLvCode(status==401||status==403?com.securedoc.platform.domain.error.LvErrorCode.DLV_005:retry?com.securedoc.platform.domain.error.LvErrorCode.DLV_002:status>=400?com.securedoc.platform.domain.error.LvErrorCode.DLV_004:com.securedoc.platform.domain.error.LvErrorCode.DLV_007);}}
    private TechnicalDependencyException invalid(String msg,Throwable cause){return failure(false,TechnicalFailureCode.DELIVERY_DESTINATION_INVALID,msg,cause);}private TechnicalDependencyException failure(boolean retryable,TechnicalFailureCode code,String msg,Throwable cause){return new TechnicalDependencyException(TechnicalDependency.DELIVERY,code,retryable,msg,cause);}
    private String sanitize(String s){String x=s==null?"document.pdf":s.replaceAll("[^A-Za-z0-9._-]+","_");return x.isBlank()?"document.pdf":x;}private String safeHeader(String s){return (s==null?"":s).replace("\r","_").replace("\n","_");}
}
