package com.disruption.companion.adapters.fixture;

import com.disruption.companion.application.port.RebookingPort;
import com.disruption.companion.domain.RebookOption;
import com.disruption.companion.domain.RebookingResult;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public final class ConfigurableRebookingAdapter implements RebookingPort {

    public record RecordedRequest(RebookOption option, String idempotencyKey) {
    }

    private final Queue<RebookingResult> scriptedResponses;
    private final RebookingResult defaultResponse;
    private final List<RecordedRequest> recorded = new ArrayList<>();

    public ConfigurableRebookingAdapter() {
        this(List.of(), new RebookingResult.Created(
                "RBK-88231", "PENDING_CONFIRMATION", Instant.parse("2026-09-15T11:12:00Z")));
    }

    public ConfigurableRebookingAdapter(List<RebookingResult> scriptedResponses) {
        this(scriptedResponses, new RebookingResult.Created(
                "RBK-88231", "PENDING_CONFIRMATION", Instant.parse("2026-09-15T11:12:00Z")));
    }

    public ConfigurableRebookingAdapter(List<RebookingResult> scriptedResponses, RebookingResult defaultResponse) {
        this.scriptedResponses = new ArrayDeque<>(scriptedResponses);
        this.defaultResponse = defaultResponse;
    }

    @Override
    public synchronized RebookingResult createRebookingRequest(RebookOption option, String idempotencyKey) {
        recorded.add(new RecordedRequest(option, idempotencyKey));
        return scriptedResponses.isEmpty() ? defaultResponse : scriptedResponses.poll();
    }

    public synchronized List<RecordedRequest> recordedRequests() {
        return List.copyOf(recorded);
    }
}
