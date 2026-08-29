package dev.infinityknowledge.controlplane.api.graph;

import dev.infinityknowledge.controlplane.application.graph.GraphApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin graph explorer backed by the same ACL-aware store used by Agent retrieval. */
@RestController
@RequestMapping("/api/v1/admin/graph")
public final class GraphController {
    private final GraphApplicationService graph;
    private final JwtPrincipalContextFactory principals;

    public GraphController(
            GraphApplicationService graph,
            JwtPrincipalContextFactory principals
    ) {
        this.graph = graph;
        this.principals = principals;
    }

    @PostMapping("/search")
    public GraphApi.SearchResponse search(
            @Valid @RequestBody GraphApi.SearchRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return graph.search(principals.create(jwt), request);
    }
}
