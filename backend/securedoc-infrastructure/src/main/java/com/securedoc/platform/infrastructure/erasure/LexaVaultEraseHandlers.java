package com.securedoc.platform.infrastructure.erasure;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlerException;
import com.documentplatform.eraselog.EraseRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Task 0060: one erase handler per kind ({@link EraseRecord}), each with its check, its erasing and its verify check. */
@Configuration
public class LexaVaultEraseHandlers {

    @Bean
    public EraseHandler caseFilesEraseHandler(StoredFileErasure files) {
        return new EraseHandler() {
            @Override
            public String kind() {
                return EraseRecord.CASE_FILES;
            }

            @Override
            public void check(EraseRequest request) {
                files.requireReachable(request, true);
            }

            @Override
            public Applied apply(EraseEntry entry, Context context) {
                return files.eraseCase(entry, context);
            }

            @Override
            public Verified verify(EraseEntry entry) {
                return files.verifyCase(entry);
            }
        };
    }

    @Bean
    public EraseHandler documentEraseHandler(StoredFileErasure files) {
        return new FileHandler(EraseRecord.DOCUMENT, files);
    }

    @Bean
    public EraseHandler redactedCopyEraseHandler(StoredFileErasure files) {
        return new FileHandler(EraseRecord.REDACTED_COPY, files);
    }

    @Bean
    public EraseHandler exportCopyEraseHandler(StoredFileErasure files) {
        return new FileHandler(EraseRecord.EXPORT_COPY, files);
    }

    @Bean
    public EraseHandler workingFileEraseHandler(StoredFileErasure files) {
        return new FileHandler(EraseRecord.WORKING_FILE, files);
    }

    @Bean
    public EraseHandler unrecordedFileEraseHandler(StoredFileErasure files) {
        return new FileHandler(EraseRecord.UNRECORDED_FILE, files) {
            /** Only while no record points to it (a record made meanwhile keeps the file). */
            @Override
            public void check(EraseRequest request) {
                if (files.recorded(request)) {
                    throw new EraseHandlerException("LV_ERASE_FILE_RECORDED", "a record points to the file");
                }
            }
        };
    }

    /** Task 0097. */
    @Bean
    public EraseHandler deliveryLeftoverEraseHandler(DeliveryLeftoverErasure leftovers) {
        return new EraseHandler() {
            @Override
            public String kind() {
                return EraseRecord.DELIVERY_LEFTOVER;
            }

            @Override
            public void check(EraseRequest request) {
                leftovers.check(request);
            }

            @Override
            public Applied apply(EraseEntry entry, Context context) {
                return leftovers.erase(entry, context);
            }

            @Override
            public Verified verify(EraseEntry entry) {
                return leftovers.verify(entry);
            }
        };
    }

    private static class FileHandler implements EraseHandler {
        private final String kind;
        private final StoredFileErasure files;

        FileHandler(String kind, StoredFileErasure files) {
            this.kind = kind;
            this.files = files;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public void check(EraseRequest request) {
            files.requireReachable(request, false);
        }

        @Override
        public Applied apply(EraseEntry entry, Context context) {
            return files.eraseFile(entry, context);
        }

        @Override
        public Verified verify(EraseEntry entry) {
            return files.verifyFile(entry);
        }
    }
}
