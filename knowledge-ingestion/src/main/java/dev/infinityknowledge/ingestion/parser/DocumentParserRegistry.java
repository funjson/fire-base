package dev.infinityknowledge.ingestion.parser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结构化文档 Parser 的能力注册表和确定性选择器。
 *
 * <p>同一种媒体类型可以安装多个 Parser，例如 PDFBox 与 Docling。注册表只要求
 * {@link DocumentParser#id()} 全局唯一；具体文档必须先形成一次
 * {@link ParserSelection}，处理契约计算和真正解析随后都复用这个选择，避免配置变更或
 * 二次查找导致修订身份与实际 Parser 不一致。</p>
 */
public final class DocumentParserRegistry {

    private static final String GENERIC_BINARY_MEDIA_TYPE = "application/octet-stream";

    private final Map<String, DocumentParser> parsersById;
    private final Map<String, List<DocumentParser>> mediaTypeParsers;
    private final Map<String, List<DocumentParser>> extensionParsers;
    private final Map<String, String> defaultParserSelections;
    private final Map<String, String> parserContractsById;
    private final List<ParserCapability> capabilities;

    /**
     * 创建不可变注册表。
     *
     * <p>某个规范媒体类型只有一个实现时，它自然成为部署默认值；存在多个实现时不
     * 猜测默认值，调用方必须通过空间文档处理配置显式选择。</p>
     *
     * @param parsers 当前部署安装的 Parser
     */
    public DocumentParserRegistry(Collection<? extends DocumentParser> parsers) {
        this(parsers, Map.of());
    }

    /**
     * 创建带显式部署默认值的不可变注册表。
     *
     * @param parsers 当前部署安装的 Parser
     * @param configuredDefaults 规范媒体类型到稳定 Parser ID 的部署默认值
     */
    public DocumentParserRegistry(
            Collection<? extends DocumentParser> parsers,
            Map<String, String> configuredDefaults
    ) {
        Objects.requireNonNull(parsers, "parsers must not be null");
        Objects.requireNonNull(configuredDefaults, "configuredDefaults must not be null");
        if (parsers.isEmpty()) {
            throw new IllegalArgumentException("parsers must not be empty");
        }

        Map<String, DocumentParser> byId = new LinkedHashMap<>();
        Map<String, List<DocumentParser>> byMediaType = new LinkedHashMap<>();
        Map<String, List<DocumentParser>> byExtension = new LinkedHashMap<>();
        for (DocumentParser parser : parsers) {
            registerParser(byId, byMediaType, byExtension, parser);
        }
        parsersById = Map.copyOf(byId);
        mediaTypeParsers = immutableIndex(byMediaType);
        extensionParsers = immutableIndex(byExtension);
        defaultParserSelections = resolveDefaults(byId, configuredDefaults);
        Map<String, String> contractsById = new LinkedHashMap<>();
        byId.forEach((parserId, parser) -> contractsById.put(
                parserId,
                parserContract(parser)
        ));
        parserContractsById = Map.copyOf(contractsById);
        capabilities = byId.values().stream()
                .map(parser -> capability(
                        parser,
                        parser.id().equals(defaultParserSelections.get(
                                normalizeMediaType(parser.canonicalMediaType())
                        ))
                ))
                .sorted(Comparator.comparing(ParserCapability::parserId))
                .toList();
    }

    /** 创建内置 Markdown、纯文本、HTML、PDF 和 DOCX 解析器注册表。 */
    public static DocumentParserRegistry standard() {
        return new DocumentParserRegistry(List.of(
                new MarkdownDocumentParser(),
                new PlainTextDocumentParser(),
                new HtmlDocumentParser(),
                new PdfDocumentParser(),
                new DocxDocumentParser()
        ));
    }

    /**
     * 按空间文档处理配置选择唯一 Parser。
     *
     * <p>文档处理配置以规范媒体类型为键。创建 Space 时已经把部署默认选择物化为
     * 完整快照，因此执行期缺少当前格式必须失败，不能再读取可能已经变化的部署默认值。</p>
     *
     * @param mediaType 调用方声明或已经规范化的媒体类型
     * @param fileName 安全文件名
     * @param configSelections 规范媒体类型到 Parser ID 的空间配置
     * @return 可同时用于契约计算和解析的不可变选择
     */
    public ParserSelection select(
            String mediaType,
            String fileName,
            Map<String, String> configSelections
    ) {
        Map<String, String> selections = normalizeSelections(configSelections);
        List<DocumentParser> candidates = compatibleParsers(mediaType, fileName);
        Set<String> canonicalMediaTypes = candidates.stream()
                .map(parser -> normalizeMediaType(parser.canonicalMediaType()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<DocumentParser> selectedByConfig = new ArrayList<>();
        for (String canonicalMediaType : canonicalMediaTypes) {
            String parserId = selections.get(canonicalMediaType);
            if (parserId == null) {
                throw new DocumentParseException(
                        "immutable parser selections omit the document canonical media type"
                );
            }
            selectedByConfig.add(requireCompatibleParser(
                    parserId,
                    canonicalMediaType,
                    candidates
            ));
        }
        selectedByConfig = selectedByConfig.stream().distinct().toList();
        if (selectedByConfig.size() == 1) {
            return selection(selectedByConfig.getFirst());
        }
        if (selectedByConfig.size() > 1) {
            throw new DocumentParseException(
                    "document format resolves to multiple configured parsers"
            );
        }
        if (candidates.size() == 1) {
            return selection(candidates.getFirst());
        }
        throw new DocumentParseException(
                "multiple document parsers are available; document processing config selection is required"
        );
    }

    /** 使用稳定 Parser ID 显式选择实现，并验证它确实支持来源格式。 */
    public ParserSelection select(
            String mediaType,
            String fileName,
            String parserId
    ) {
        parserId = requiredParserId(parserId);
        List<DocumentParser> candidates = compatibleParsers(mediaType, fileName);
        DocumentParser parser = parsersById.get(parserId);
        if (parser == null || !candidates.contains(parser)) {
            throw new DocumentParseException(
                    "selected parser does not support the supplied document format"
            );
        }
        return selection(parser);
    }

    /**
     * 返回空间文档处理配置实际选择的 Parser 集合契约。
     *
     * <p>索引代际只绑定创建 Space 时明确选择的实现。这里只逐项验证并计算固化映射，
     * 不用当前部署默认值补齐，也不要求覆盖部署后来新增的格式。新 Space 是否覆盖创建时
     * 的完整目录由控制面的创建校验负责；未知格式、不可用 Parser 及格式不匹配会被拒绝。</p>
     */
    public String selectedParsersContract(Map<String, String> configSelections) {
        return selectedParserContracts(configSelections).entrySet().stream()
                .map(entry -> entry.getKey() + "\u001F" + entry.getValue())
                .collect(Collectors.joining("\u001E"));
    }

    /**
     * 返回逐规范媒体类型保存的实际 Parser 实现合同。
     *
     * <p>Space 固化时需要保留可解释材料，而不只是拼接后的总指纹；因此本方法与
     * 选择校验共用一条实现，并返回稳定排序的不可变映射。</p>
     */
    public Map<String, String> selectedParserContracts(
            Map<String, String> configSelections
    ) {
        Map<String, String> selections = normalizeSelections(configSelections);
        Set<String> canonicalMediaTypes = capabilities.stream()
                .map(ParserCapability::canonicalMediaType)
                .collect(Collectors.toSet());
        if (!canonicalMediaTypes.containsAll(selections.keySet())) {
            throw new IllegalArgumentException(
                    "parser selections contain an unknown canonical media type"
            );
        }
        Map<String, String> contracts = selections.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            DocumentParser parser = parsersById.get(entry.getValue());
                            if (parser == null) {
                                throw new IllegalArgumentException(
                                        "parser selection references an unavailable parser"
                                );
                            }
                            String canonicalMediaType = normalizeMediaType(
                                    parser.canonicalMediaType()
                            );
                            if (!entry.getKey().equals(canonicalMediaType)) {
                                throw new IllegalArgumentException(
                                        "parser selection does not match canonical media type"
                                );
                            }
                            return parserContractsById.get(parser.id());
                        },
                        (left, right) -> {
                            throw new IllegalStateException(
                                    "parser contract media type is duplicated"
                            );
                        },
                        java.util.TreeMap::new
                ));
        return java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(contracts)
        );
    }

    /**
     * 只解析规范媒体类型，不决定具体 Parser。
     *
     * <p>上传层可用它校验 MIME 与扩展名；即使同格式安装多个 Parser，也不会在
     * 文档处理配置读取之前过早选择某个实现。</p>
     */
    public String resolveCanonicalMediaType(String mediaType, String fileName) {
        Set<String> canonicalMediaTypes = compatibleParsers(mediaType, fileName).stream()
                .map(parser -> normalizeMediaType(parser.canonicalMediaType()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (canonicalMediaTypes.size() != 1) {
            throw new DocumentParseException(
                    "document format maps to multiple canonical media types"
            );
        }
        return canonicalMediaTypes.iterator().next();
    }

    /** 返回供配置页面展示的稳定 Parser 能力描述。 */
    public List<ParserCapability> capabilities() {
        return capabilities;
    }

    /** 返回当前部署的默认选择；多实现格式只有配置显式默认值时才会出现。 */
    public Map<String, String> defaultParserSelections() {
        return defaultParserSelections;
    }

    private List<DocumentParser> compatibleParsers(String mediaType, String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        List<DocumentParser> extensionCandidates = extensionParsers.getOrDefault(
                extension(fileName),
                List.of()
        );
        String normalized = mediaType == null
                ? ""
                : mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || GENERIC_BINARY_MEDIA_TYPE.equals(normalized)) {
            if (extensionCandidates.isEmpty()) {
                throw new DocumentParseException(
                        "no document parser supports the supplied media type or extension"
                );
            }
            return extensionCandidates;
        }

        List<DocumentParser> mediaCandidates = mediaTypeParsers.getOrDefault(
                normalized,
                List.of()
        );
        if (mediaCandidates.isEmpty()) {
            throw new DocumentParseException("unsupported document media type");
        }
        if (extensionCandidates.isEmpty()) {
            return mediaCandidates;
        }
        List<DocumentParser> intersection = mediaCandidates.stream()
                .filter(extensionCandidates::contains)
                .toList();
        if (intersection.isEmpty()) {
            throw new DocumentParseException(
                    "file extension does not match document media type"
            );
        }
        return intersection;
    }

    private DocumentParser requireCompatibleParser(
            String parserId,
            String canonicalMediaType,
            List<DocumentParser> candidates
    ) {
        DocumentParser selected = parsersById.get(parserId);
        if (selected == null) {
            throw new DocumentParseException("document processing config selects an unavailable parser");
        }
        if (!canonicalMediaType.equals(normalizeMediaType(selected.canonicalMediaType()))
                || !candidates.contains(selected)) {
            throw new DocumentParseException(
                    "document processing config parser does not support the supplied document format"
            );
        }
        return selected;
    }

    private static void registerParser(
            Map<String, DocumentParser> byId,
            Map<String, List<DocumentParser>> byMediaType,
            Map<String, List<DocumentParser>> byExtension,
            DocumentParser parser
    ) {
        Objects.requireNonNull(parser, "parser must not be null");
        String parserId = requiredParserId(parser.id());
        if (!parserId.equals(parser.id())) {
            throw new IllegalArgumentException("parser id must not contain surrounding spaces");
        }
        DocumentParser previous = byId.putIfAbsent(parserId, parser);
        if (previous != null) {
            throw new IllegalArgumentException("duplicate parser id: " + parserId);
        }
        String canonicalMediaType = normalizeMediaType(parser.canonicalMediaType());
        if (parser.supportedMediaTypes().stream()
                .map(DocumentParserRegistry::normalizeMediaType)
                .noneMatch(canonicalMediaType::equals)) {
            throw new IllegalArgumentException(
                    "parser canonical media type must be declared as supported"
            );
        }
        outputCapabilities(parser);
        parser.supportedMediaTypes().forEach(mediaType -> addToIndex(
                byMediaType,
                normalizeMediaType(mediaType),
                parser
        ));
        parser.supportedExtensions().forEach(extension -> addToIndex(
                byExtension,
                normalizeExtension(extension),
                parser
        ));
    }

    private Map<String, String> resolveDefaults(
            Map<String, DocumentParser> byId,
            Map<String, String> configuredDefaults
    ) {
        Map<String, String> normalizedConfigured = normalizeSelections(configuredDefaults);
        Map<String, List<DocumentParser>> byCanonicalMediaType = byId.values().stream()
                .collect(Collectors.groupingBy(
                        parser -> normalizeMediaType(parser.canonicalMediaType()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, List<DocumentParser>> entry : byCanonicalMediaType.entrySet()) {
            String configuredParserId = normalizedConfigured.get(entry.getKey());
            if (configuredParserId != null) {
                DocumentParser configuredParser = byId.get(configuredParserId);
                if (configuredParser == null || !entry.getValue().contains(configuredParser)) {
                    throw new IllegalArgumentException(
                            "configured default parser does not support " + entry.getKey()
                    );
                }
                resolved.put(entry.getKey(), configuredParserId);
            } else if (entry.getValue().size() == 1) {
                resolved.put(entry.getKey(), entry.getValue().getFirst().id());
            }
        }
        for (String configuredMediaType : normalizedConfigured.keySet()) {
            if (!byCanonicalMediaType.containsKey(configuredMediaType)) {
                throw new IllegalArgumentException(
                        "configured default uses an unsupported canonical media type"
                );
            }
        }
        return Map.copyOf(resolved);
    }

    private static Map<String, String> normalizeSelections(Map<String, String> selections) {
        Objects.requireNonNull(selections, "parser selections must not be null");
        Map<String, String> normalized = new LinkedHashMap<>();
        selections.forEach((mediaType, parserId) -> {
            String key = normalizeMediaType(mediaType);
            String value = requiredParserId(parserId);
            String previous = normalized.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalArgumentException(
                        "parser selections contain duplicate canonical media types"
                );
            }
        });
        return Map.copyOf(normalized);
    }

    private static Map<String, List<DocumentParser>> immutableIndex(
            Map<String, List<DocumentParser>> source
    ) {
        Map<String, List<DocumentParser>> immutable = new LinkedHashMap<>();
        source.forEach((key, value) -> immutable.put(
                key,
                value.stream()
                        .distinct()
                        .sorted(Comparator.comparing(DocumentParser::id))
                        .toList()
        ));
        return Map.copyOf(immutable);
    }

    private static void addToIndex(
            Map<String, List<DocumentParser>> target,
            String key,
            DocumentParser parser
    ) {
        target.computeIfAbsent(key, ignored -> new ArrayList<>()).add(parser);
    }

    private static ParserSelection selection(DocumentParser parser) {
        String canonicalMediaType = normalizeMediaType(parser.canonicalMediaType());
        return new ParserSelection(
                parser,
                new DocumentFormat(canonicalMediaType, parser.id(), parser.version())
        );
    }

    private static ParserCapability capability(
            DocumentParser parser,
            boolean defaultSelection
    ) {
        return new ParserCapability(
                parser.id(),
                parser.version(),
                normalizeMediaType(parser.canonicalMediaType()),
                parser.supportedMediaTypes().stream()
                        .map(DocumentParserRegistry::normalizeMediaType)
                        .sorted()
                        .toList(),
                parser.supportedExtensions().stream()
                        .map(DocumentParserRegistry::normalizeExtension)
                        .sorted()
                        .toList(),
                outputCapabilities(parser),
                defaultSelection
        );
    }

    /**
     * 返回一次具体选择使用的稳定 Adapter 契约。
     *
     * <p>该方法保持包内可见，让 {@link ParserSelection} 与 Registry 使用同一份能力
     * 规范化逻辑，避免修订指纹和能力目录描述不同。</p>
     */
    static String selectedParserContract(DocumentParser parser) {
        Objects.requireNonNull(parser, "parser must not be null");
        return parser.id()
                + ':' + parser.version()
                + ":outputs=" + outputCapabilityContract(parser);
    }

    private static String normalizeMediaType(String mediaType) {
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        String normalized = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        return normalized;
    }

    private static String normalizeExtension(String extension) {
        Objects.requireNonNull(extension, "extension must not be null");
        String normalized = extension.strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(".") || normalized.length() < 2) {
            throw new IllegalArgumentException("parser extensions must include a leading dot");
        }
        return normalized;
    }

    private static String requiredParserId(String parserId) {
        Objects.requireNonNull(parserId, "parserId must not be null");
        String normalized = parserId.strip();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("parserId must contain 1 to 128 characters");
        }
        return normalized;
    }

    private static String extension(String fileName) {
        int lastSlash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > lastSlash ? fileName.substring(lastDot).toLowerCase(Locale.ROOT) : "";
    }

    private static String parserContract(DocumentParser parser) {
        return String.join(
                "\u001F",
                parser.id(),
                parser.version(),
                normalizeMediaType(parser.canonicalMediaType()),
                parser.supportedMediaTypes().stream()
                        .map(DocumentParserRegistry::normalizeMediaType)
                        .sorted()
                        .collect(Collectors.joining(",")),
                parser.supportedExtensions().stream()
                        .map(DocumentParserRegistry::normalizeExtension)
                        .sorted()
                        .collect(Collectors.joining(",")),
                "outputs=" + outputCapabilityContract(parser)
        );
    }

    private static List<ParserOutputCapability> outputCapabilities(DocumentParser parser) {
        Set<ParserOutputCapability> declared = Set.copyOf(Objects.requireNonNull(
                parser.outputCapabilities(),
                "parser outputCapabilities must not be null"
        ));
        if (!declared.contains(ParserOutputCapability.STANDARD_ELEMENTS)) {
            throw new IllegalArgumentException(
                    "parser outputCapabilities must include STANDARD_ELEMENTS"
            );
        }
        return declared.stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    private static String outputCapabilityContract(DocumentParser parser) {
        return outputCapabilities(parser).stream()
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

}
