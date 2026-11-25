package com.securedoc.benchmark.truth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Hand-made answer file for one sample document: the sensitive items per page, as a type and a rough
 * box. It never holds a value. File name: {@code <sample>.truth.json} next to the sample PDF.
 *
 * <p>Boxes are {@code [left, top, width, height]}, normalized 0..1, origin top-left of the page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Truth(int version, List<PageTruth> pages) {

    /** Item types a sample can hold. The first four are the text path's types today (V1). */
    public static final List<String> ITEM_TYPES = List.of(
            "SSN", "DATE_OF_BIRTH", "CREDIT_CARD_NUMBER", "CREDIT_SCORE",
            "BANK_ACCOUNT", "ROUTING_NUMBER", "GOVERNMENT_ID", "SIGNATURE", "FACE_PHOTO", "PERSON_NAME");

    /** The types the product's text path can find today (TenantRedactionPolicy V1 set). */
    public static final Set<String> TEXT_PATH_TYPES = Set.of(
            "SSN", "DATE_OF_BIRTH", "CREDIT_CARD_NUMBER", "CREDIT_SCORE");

    /** Answer-file types the task 0043 pattern kinds cover (routing, account, driver's license and passport). */
    public static final Set<String> R1_PATTERN_TYPES = Set.of("BANK_ACCOUNT", "ROUTING_NUMBER", "GOVERNMENT_ID");

    /** The text path's types with the task 0043 kinds switched on ({@code --text-kinds all}). */
    public static final Set<String> TEXT_PATH_R1_TYPES = Set.of(
            "SSN", "DATE_OF_BIRTH", "CREDIT_CARD_NUMBER", "CREDIT_SCORE", "BANK_ACCOUNT", "ROUTING_NUMBER", "GOVERNMENT_ID");

    /** Page kinds the report breaks results down by. */
    public static final List<String> PAGE_KINDS = List.of("TYPED", "HANDWRITTEN", "ID", "CHECK", "FAX");

    /** The routes of vision-routing-requirement.md section 2. */
    public static final List<String> ROUTES = List.of("FAST", "FAST_SIGNATURE", "VISION");

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageTruth(int page, String kind, String expectedRoute, List<Item> items) {
        public PageTruth {
            items = items == null ? List.of() : List.copyOf(items);
            if (kind == null || !PAGE_KINDS.contains(kind)) {
                throw new IllegalArgumentException("page " + page + ": unknown page kind " + kind);
            }
            if (expectedRoute != null && !ROUTES.contains(expectedRoute)) {
                throw new IllegalArgumentException("page " + page + ": unknown route " + expectedRoute);
            }
        }
    }

    /** One sensitive item: type, rough box, and whether it is handwritten. Never a value. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String type, double[] box, Boolean handwritten) {
        public Item {
            if (type == null || !ITEM_TYPES.contains(type)) {
                throw new IllegalArgumentException("unknown item type " + type);
            }
            if (box == null || box.length != 4) {
                throw new IllegalArgumentException("box must be [left, top, width, height]");
            }
        }

        public boolean isHandwritten() {
            return Boolean.TRUE.equals(handwritten);
        }
    }

    public PageTruth page(int number) {
        return pages.stream().filter(p -> p.page() == number).findFirst().orElse(null);
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static Truth read(Path file) throws IOException {
        Truth truth = JSON.readValue(file.toFile(), Truth.class);
        if (truth.pages() == null || truth.pages().isEmpty()) {
            throw new IOException("answer file has no pages: " + file.getFileName());
        }
        return truth;
    }

    public void write(Path file) throws IOException {
        JSON.writeValue(file.toFile(), this);
    }
}
