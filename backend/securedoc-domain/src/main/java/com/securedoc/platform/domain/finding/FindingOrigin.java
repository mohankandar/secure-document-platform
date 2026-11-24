package com.securedoc.platform.domain.finding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task 0064: which path(s) found a finding and which AI model(s) took part. Never a value or text.
 *
 * @param paths  empty for findings saved before task 0064 and for regions a person added
 * @param models empty when no AI model took part (patterns, the signature detector)
 */
public record FindingOrigin(Set<DetectionPath> paths, List<AiModel> models) {
    public static final FindingOrigin NOT_RECORDED = new FindingOrigin(Set.of(), List.of());

    public FindingOrigin {
        EnumSet<DetectionPath> ordered = EnumSet.noneOf(DetectionPath.class);
        ordered.addAll(paths);
        paths = Collections.unmodifiableSet(ordered);
        models = List.copyOf(new LinkedHashSet<>(models));
    }

    public static FindingOrigin of(DetectionPath path, Collection<AiModel> models) {
        return new FindingOrigin(Set.of(path), List.copyOf(models));
    }

    public FindingOrigin with(FindingOrigin other) {
        Set<DetectionPath> allPaths = EnumSet.noneOf(DetectionPath.class);
        allPaths.addAll(paths);
        allPaths.addAll(other.paths);
        List<AiModel> allModels = new ArrayList<>(models);
        allModels.addAll(other.models);
        return new FindingOrigin(allPaths, allModels);
    }

    /** {@code TEXT+VISION}; null when nothing is recorded. */
    public String encodePaths() {
        return paths.isEmpty() ? null : paths.stream().map(Enum::name).collect(Collectors.joining("+"));
    }

    /** The models, {@code ;} between them; null when there is none. */
    public String encodeModels() {
        return encodeModels(models);
    }

    public static String encodeModels(Collection<AiModel> models) {
        return models.isEmpty() ? null : models.stream().map(AiModel::encode).collect(Collectors.joining(";"));
    }

    public static List<AiModel> decodeModels(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<AiModel> models = new ArrayList<>();
        for (String one : text.split(";")) models.add(AiModel.decode(one));
        return models;
    }

    public static FindingOrigin decode(String paths, String models) {
        Set<DetectionPath> read = EnumSet.noneOf(DetectionPath.class);
        if (paths != null && !paths.isBlank()) {
            for (String one : paths.split("\\+")) read.add(DetectionPath.valueOf(one));
        }
        return new FindingOrigin(read, decodeModels(models));
    }
}
