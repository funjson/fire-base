package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.EvaluationApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Retrieval evaluation datasets, cases and asynchronous experiment runs.
 */
@RestController
@RequestMapping("/api/v1/evaluations")
public final class EvaluationController {
    private final EvaluationApplicationService evaluations;
    private final JwtPrincipalContextFactory principals;

    public EvaluationController(
            EvaluationApplicationService evaluations,
            JwtPrincipalContextFactory principals
    ) {
        this.evaluations = evaluations;
        this.principals = principals;
    }

    @GetMapping("/datasets")
    public List<EvaluationApi.Dataset> datasets(@AuthenticationPrincipal Jwt jwt) {
        return evaluations.datasets(principals.create(jwt));
    }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationApi.Dataset createDataset(
            @Valid @RequestBody EvaluationApi.CreateDatasetRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return evaluations.createDataset(principals.create(jwt), request);
    }

    @GetMapping("/datasets/{datasetId}/cases")
    public List<EvaluationApi.Case> cases(
            @PathVariable UUID datasetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return evaluations.cases(principals.create(jwt), datasetId);
    }

    @PostMapping("/datasets/{datasetId}/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationApi.Case createCase(
            @PathVariable UUID datasetId,
            @Valid @RequestBody EvaluationApi.CreateCaseRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return evaluations.createCase(principals.create(jwt), datasetId, request);
    }

    @GetMapping("/datasets/{datasetId}/runs")
    public List<EvaluationApi.Run> runs(
            @PathVariable UUID datasetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return evaluations.runs(principals.create(jwt), datasetId);
    }

    @PostMapping("/datasets/{datasetId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EvaluationApi.Run start(
            @PathVariable UUID datasetId,
            @Valid @RequestBody EvaluationApi.StartRunRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return evaluations.start(principals.create(jwt), datasetId, request);
    }

    @GetMapping("/runs/{runId}")
    public EvaluationApi.Run run(
            @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return evaluations.run(principals.create(jwt), runId, true);
    }
}
