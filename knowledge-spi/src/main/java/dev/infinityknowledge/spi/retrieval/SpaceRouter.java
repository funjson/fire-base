package dev.infinityknowledge.spi.retrieval;

/**
 * 使用模型对服务端已授权的知识空间进行排序。
 *
 * <p>Router 不接收租户或 ACL 操作，也不能增加、删除候选空间。执行层必须再次验证结果是
 * {@link SpaceRoutingRequest#allowedSpaces()} 的完整排列。</p>
 */
@FunctionalInterface
public interface SpaceRouter {

    /**
     * 按与独立查询的相关性排列全部候选空间。
     *
     * @param request 有界路由请求
     * @return 模型排序结果
     */
    SpaceRoutingResult rank(SpaceRoutingRequest request);

    /**
     * 返回不调用模型、保持输入顺序的稳定实现，供模型失败时显式降级。
     *
     * @return 确定性 Router
     */
    static SpaceRouter deterministic() {
        return request -> new SpaceRoutingResult(
                request.allowedSpaces().stream()
                        .map(SpaceRoutingCandidate::spaceId)
                        .toList(),
                "deterministic",
                "none"
        );
    }
}
