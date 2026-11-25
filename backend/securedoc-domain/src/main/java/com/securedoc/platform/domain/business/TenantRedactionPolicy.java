package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tenant-owned business policy for what to redact and when review is required.
 *
 * <p>Task 0044: {@code searchableRedactedPages} (on by default) adds an invisible OCR text layer to every redacted page
 * (option B); off gives today's image-only redacted pages. Task 0049: {@code pageBatches} splits large documents for
 * Text and Find.
 *
 * <p>Task 0062: the kinds come from the {@link RedactionCatalog}; {@code packs} are the regulation packs in force (they
 * say why kinds are on; {@code enabledEntityTypes} alone decides what is found, so a kind can be fine-tuned);
 * {@code keepLastFour} are the kinds whose last four digits stay visible. With {@code lockedItemsOn} the locked items
 * are always enabled. It is off only for an entity from before the catalog whose policy was not saved since, which
 * keeps exactly the behaviour it had.
 *
 * <p>Task 0064: {@code vision} says whether routed pages are read by the vision model, and the routing threshold.
 */
public record TenantRedactionPolicy(
        TenantId tenantId,
        Set<EntityType> enabledEntityTypes,
        double autoRedactThreshold,
        ReviewMode reviewMode,
        boolean maskSsnLastFour,
        Instant updatedAt,
        boolean searchableRedactedPages,
        PageBatchSettings pageBatches,
        Set<RegulationPack> packs,
        Set<EntityType> keepLastFour,
        boolean lockedItemsOn,
        VisionSettings vision
) {
    /** The policy as it was before task 0064, with the default vision settings. */
    public TenantRedactionPolicy(TenantId tenantId, Set<EntityType> enabledEntityTypes, double autoRedactThreshold,
                                 ReviewMode reviewMode, boolean maskSsnLastFour, Instant updatedAt,
                                 boolean searchableRedactedPages, PageBatchSettings pageBatches,
                                 Set<RegulationPack> packs, Set<EntityType> keepLastFour, boolean lockedItemsOn) {
        this(tenantId, enabledEntityTypes, autoRedactThreshold, reviewMode, maskSsnLastFour, updatedAt,
                searchableRedactedPages, pageBatches, packs, keepLastFour, lockedItemsOn, VisionSettings.DEFAULTS);
    }

    /** A policy from before the catalog (task 0062): no packs, every value removed in full, no locked items. */
    public TenantRedactionPolicy(TenantId tenantId, Set<EntityType> enabledEntityTypes, double autoRedactThreshold,
                                 ReviewMode reviewMode, boolean maskSsnLastFour, Instant updatedAt,
                                 boolean searchableRedactedPages, PageBatchSettings pageBatches) {
        this(tenantId, enabledEntityTypes, autoRedactThreshold, reviewMode, maskSsnLastFour, updatedAt,
                searchableRedactedPages, pageBatches, Set.of(), Set.of(), false);
    }

    /** The policy as it was before task 0049, with the default page batches. */
    public TenantRedactionPolicy(TenantId tenantId, Set<EntityType> enabledEntityTypes, double autoRedactThreshold,
                                 ReviewMode reviewMode, boolean maskSsnLastFour, Instant updatedAt,
                                 boolean searchableRedactedPages) {
        this(tenantId, enabledEntityTypes, autoRedactThreshold, reviewMode, maskSsnLastFour, updatedAt,
                searchableRedactedPages, PageBatchSettings.DEFAULTS);
    }

    /** The policy as it was before task 0044, with searchable redacted pages at their default (on). */
    public TenantRedactionPolicy(TenantId tenantId, Set<EntityType> enabledEntityTypes, double autoRedactThreshold,
                                 ReviewMode reviewMode, boolean maskSsnLastFour, Instant updatedAt) {
        this(tenantId, enabledEntityTypes, autoRedactThreshold, reviewMode, maskSsnLastFour, updatedAt, true);
    }

    public TenantRedactionPolicy {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(enabledEntityTypes, "enabledEntityTypes must not be null");
        Objects.requireNonNull(reviewMode, "reviewMode must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(pageBatches, "pageBatches must not be null");
        Objects.requireNonNull(vision, "vision must not be null");
        Objects.requireNonNull(packs, "packs must not be null");
        Objects.requireNonNull(keepLastFour, "keepLastFour must not be null");
        if (autoRedactThreshold < 0.0 || autoRedactThreshold > 1.0) {
            throw new IllegalArgumentException("autoRedactThreshold must be between 0 and 1");
        }
        if (!RedactionCatalog.kinds().containsAll(enabledEntityTypes)) {
            throw new IllegalArgumentException("Policy contains entity types that are not in the redaction catalog");
        }
        if (!RedactionCatalog.lastFourAllowed().containsAll(keepLastFour)) {
            throw new IllegalArgumentException("The last four digits can't be kept for one of the kinds");
        }
        EnumSet<EntityType> enabled = EnumSet.noneOf(EntityType.class);
        enabled.addAll(enabledEntityTypes);
        if (lockedItemsOn) enabled.addAll(RedactionCatalog.lockedKinds());
        enabledEntityTypes = Set.copyOf(enabled);
        packs = Set.copyOf(packs);
        keepLastFour = Set.copyOf(keepLastFour);
    }

    /** The policy of a new entity: the catalog's defaults, with the locked items. */
    public static TenantRedactionPolicy defaults(TenantId tenantId) {
        return new TenantRedactionPolicy(tenantId, RedactionCatalog.onByDefault(), 0.80, ReviewMode.UNCERTAIN_ONLY,
                true, Instant.now(), true, PageBatchSettings.DEFAULTS, Set.of(), Set.of(), true);
    }

    /** The default policy as it was before the catalog: the four kinds the AI was asked for, nothing locked. */
    public static TenantRedactionPolicy defaultsBeforeCatalog(TenantId tenantId) {
        EnumSet<EntityType> four = EnumSet.copyOf(RedactionCatalog.onByDefault());
        four.retainAll(RedactionCatalog.askedOfAi());
        return new TenantRedactionPolicy(tenantId, four, 0.80, ReviewMode.UNCERTAIN_ONLY,
                true, Instant.now());
    }

    /** How a found value of this kind is covered. A locked item is always removed in full. */
    public RedactionStyle styleOf(EntityType type) {
        return keepLastFour.contains(type) && !RedactionCatalog.isLocked(type)
                ? RedactionStyle.KEEP_LAST_FOUR : RedactionStyle.REMOVE_FULLY;
    }

    public boolean isEnabled(EntityType type) {
        return enabledEntityTypes.contains(type);
    }
}
