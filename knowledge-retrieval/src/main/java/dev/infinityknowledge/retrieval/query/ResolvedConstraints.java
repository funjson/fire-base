package dev.infinityknowledge.retrieval.query;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 表示一个 Query Optimization Plan 内所有 Variant 共享的已解析约束。
 *
 * <p>硬约束永远进入物理检索且不可由 Chain 或模型移除；软约束首轮生效，
 * 只有来源明确允许时才能由 RELAX 确定性移除；收窄候选在首轮不生效，
 * 只有 Agent 输入或 Q0 确定性提取得到的候选才能由 NARROW 加入。</p>
 *
 * @param appliedConstraints 当前轮实际应用的硬、软约束
 * @param narrowingCandidates 尚未应用且允许后续收窄的候选约束
 */
public record ResolvedConstraints(
        List<AppliedConstraint> appliedConstraints,
        List<NarrowingCandidate> narrowingCandidates
) {

    /** 返回没有过滤条件和待收窄候选的约束。 */
    public static ResolvedConstraints empty() {
        return new ResolvedConstraints(List.of(), List.of());
    }

    /** 校验同一字段只能有一个权威状态，避免放宽或收窄时覆盖硬约束。 */
    public ResolvedConstraints {
        appliedConstraints = List.copyOf(Objects.requireNonNull(
                appliedConstraints,
                "appliedConstraints must not be null"
        ));
        narrowingCandidates = List.copyOf(Objects.requireNonNull(
                narrowingCandidates,
                "narrowingCandidates must not be null"
        ));
        if (appliedConstraints.stream().anyMatch(Objects::isNull)
                || narrowingCandidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("resolved constraints must not contain null");
        }
        HashSet<String> keys = new HashSet<>();
        if (appliedConstraints.stream().map(AppliedConstraint::key)
                .anyMatch(key -> !keys.add(key))
                || narrowingCandidates.stream().map(NarrowingCandidate::key)
                .anyMatch(key -> !keys.add(key))) {
            throw new IllegalArgumentException(
                    "each resolved constraint field must have one authoritative state"
            );
        }
    }

    /** 将 API 的硬过滤和显式可变约束解析成首轮约束。 */
    public static ResolvedConstraints initial(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        List<AppliedConstraint> applied = new ArrayList<>();
        query.filters().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AppliedConstraint(
                        entry.getKey(),
                        entry.getValue(),
                        ConstraintSource.CALLER_HARD_FILTER,
                        ConstraintStrength.HARD
                ))
                .forEach(applied::add);
        query.constraints().relaxableFilters().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AppliedConstraint(
                        entry.getKey(),
                        entry.getValue(),
                        ConstraintSource.CALLER_RELAXABLE_FILTER,
                        ConstraintStrength.SOFT
                ))
                .forEach(applied::add);
        List<NarrowingCandidate> narrowing = query.constraints().narrowingFilters()
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new NarrowingCandidate(
                        entry.getKey(),
                        entry.getValue(),
                        ConstraintSource.CALLER_NARROWING_CANDIDATE
                ))
                .toList();
        return new ResolvedConstraints(applied, narrowing);
    }

    /** 返回当前轮应交给所有 Retriever 的统一过滤条件。 */
    public Map<String, String> appliedFilters() {
        Map<String, String> filters = new LinkedHashMap<>();
        appliedConstraints.stream()
                .sorted(Comparator.comparing(AppliedConstraint::key))
                .forEach(value -> filters.put(value.key(), value.value()));
        return Map.copyOf(filters);
    }

    /** 是否存在来源允许移除的已生效软约束。 */
    public boolean canRelax() {
        return appliedConstraints.stream().anyMatch(value ->
                value.strength() == ConstraintStrength.SOFT
                        && value.source().allowsRelaxation()
        );
    }

    /**
     * 按字段名稳定移除一个合法软约束。
     *
     * <p>该方法不接受模型返回的字段，因此调用方硬约束不可能被模型注入或删除。</p>
     */
    public Optional<ResolvedConstraints> relaxNext() {
        Optional<AppliedConstraint> selected = appliedConstraints.stream()
                .filter(value -> value.strength() == ConstraintStrength.SOFT)
                .filter(value -> value.source().allowsRelaxation())
                .min(Comparator.comparing(AppliedConstraint::key));
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedConstraints(
                appliedConstraints.stream()
                        .filter(value -> !value.equals(selected.get()))
                        .toList(),
                narrowingCandidates
        ));
    }

    /** 是否存在来自 Agent 或 Q0 已有条件的收窄候选。 */
    public boolean canNarrow() {
        return !narrowingCandidates.isEmpty();
    }

    /** 按字段名稳定应用一个已有收窄候选，不生成任何新业务条件。 */
    public Optional<ResolvedConstraints> narrowNext() {
        Optional<NarrowingCandidate> selected = narrowingCandidates.stream()
                .min(Comparator.comparing(NarrowingCandidate::key));
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        NarrowingCandidate value = selected.get();
        List<AppliedConstraint> applied = new ArrayList<>(appliedConstraints);
        applied.add(new AppliedConstraint(
                value.key(),
                value.value(),
                value.source(),
                ConstraintStrength.HARD
        ));
        return Optional.of(new ResolvedConstraints(
                applied,
                narrowingCandidates.stream()
                        .filter(candidate -> !candidate.equals(value))
                        .toList()
        ));
    }

    /** 当前轮生效的一项约束及其不可混淆的来源和强度。 */
    public record AppliedConstraint(
            String key,
            String value,
            ConstraintSource source,
            ConstraintStrength strength
    ) {
        public AppliedConstraint {
            key = required(key, "constraint key");
            value = required(value, "constraint value");
            Objects.requireNonNull(source, "constraint source must not be null");
            Objects.requireNonNull(strength, "constraint strength must not be null");
            if (strength == ConstraintStrength.SOFT && !source.allowsRelaxation()) {
                throw new IllegalArgumentException(
                        "soft constraint source must explicitly allow relaxation"
                );
            }
        }
    }

    /** 尚未生效的一项收窄候选；只有显式来源才能进入该集合。 */
    public record NarrowingCandidate(
            String key,
            String value,
            ConstraintSource source
    ) {
        public NarrowingCandidate {
            key = required(key, "narrowing constraint key");
            value = required(value, "narrowing constraint value");
            Objects.requireNonNull(source, "narrowing constraint source must not be null");
            if (!source.allowsNarrowing()) {
                throw new IllegalArgumentException(
                        "narrowing constraint source must come from caller or original query"
                );
            }
        }
    }

    /** 约束是否允许后续变化由来源决定，而不是由模型临时决定。 */
    public enum ConstraintSource {
        CALLER_HARD_FILTER(false, false),
        CALLER_RELAXABLE_FILTER(true, false),
        CALLER_NARROWING_CANDIDATE(false, true),
        QUERY_DERIVED_NARROWING(false, true),
        SPACE_SOFT_DEFAULT(true, false),
        SYSTEM_SOFT_DEFAULT(true, false);

        private final boolean allowsRelaxation;
        private final boolean allowsNarrowing;

        ConstraintSource(boolean allowsRelaxation, boolean allowsNarrowing) {
            this.allowsRelaxation = allowsRelaxation;
            this.allowsNarrowing = allowsNarrowing;
        }

        /** 该来源是否授权 RELAX 删除已生效软约束。 */
        public boolean allowsRelaxation() {
            return allowsRelaxation;
        }

        /** 该来源是否可作为 NARROW 的既有条件，而不是模型新发明的条件。 */
        public boolean allowsNarrowing() {
            return allowsNarrowing;
        }
    }

    /** 已生效约束的强度；只有 SOFT 才存在放宽语义。 */
    public enum ConstraintStrength {
        HARD,
        SOFT
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
