package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.PageRouteRepository;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vision.PageRoute;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Task 0064: document_page_routes (migration 080). Runs in the caller's transaction, if there is one. */
@Repository
public class JdbcPageRouteRepository implements PageRouteRepository {
    private static final String UPSERT = """
            INSERT INTO document_page_routes
                (tenant_id, document_id, page_number, path, reasons, ocr_confidence, signature_boxes, created_at)
            VALUES (:tenantId, :documentId, :page, :path, :reasons, :ocr, :boxes, clock_timestamp())
            ON CONFLICT (document_id, page_number) DO UPDATE
                SET path = excluded.path, reasons = excluded.reasons, ocr_confidence = excluded.ocr_confidence,
                    signature_boxes = excluded.signature_boxes, created_at = excluded.created_at
                WHERE document_page_routes.tenant_id = excluded.tenant_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPageRouteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(TenantId tenantId, DocumentId documentId, List<PageRoute> routes) {
        if (routes.isEmpty()) return;
        jdbc.batchUpdate(UPSERT, routes.stream().map(route -> new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("documentId", documentId.value())
                .addValue("page", route.page())
                .addValue("path", route.path().name())
                .addValue("reasons", route.reasons().stream().map(Enum::name).collect(Collectors.joining("+")))
                .addValue("ocr", route.ocrConfidence(), Types.DOUBLE)
                .addValue("boxes", boxes(route.signatures()))).toArray(MapSqlParameterSource[]::new));
    }

    @Override
    public List<PageRoute> findByDocument(TenantId tenantId, DocumentId documentId) {
        return jdbc.query("SELECT page_number, path, reasons, ocr_confidence, signature_boxes FROM document_page_routes"
                        + " WHERE tenant_id = :tenantId AND document_id = :documentId ORDER BY page_number",
                new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                        .addValue("documentId", documentId.value()),
                (rs, n) -> {
                    Set<PageRoute.Reason> reasons = EnumSet.noneOf(PageRoute.Reason.class);
                    String names = rs.getString("reasons");
                    if (!names.isEmpty()) {
                        for (String name : names.split("[+]")) reasons.add(PageRoute.Reason.valueOf(name));
                    }
                    double ocr = rs.getDouble("ocr_confidence");
                    Double confidence = rs.wasNull() ? null : ocr;
                    return new PageRoute(rs.getInt("page_number"), PageRoute.Path.valueOf(rs.getString("path")), reasons,
                            confidence, boxes(rs.getString("signature_boxes")));
                });
    }

    private static String boxes(List<BoundingBox> boxes) {
        return boxes.stream().map(b -> b.left() + "," + b.top() + "," + b.width() + "," + b.height())
                .collect(Collectors.joining(";"));
    }

    private static List<BoundingBox> boxes(String text) {
        List<BoundingBox> boxes = new ArrayList<>();
        if (text.isEmpty()) return boxes;
        for (String one : text.split(";")) {
            String[] n = one.split(",");
            boxes.add(BoundingBox.of(Double.parseDouble(n[0]), Double.parseDouble(n[1]), Double.parseDouble(n[2]),
                    Double.parseDouble(n[3])));
        }
        return boxes;
    }
}
