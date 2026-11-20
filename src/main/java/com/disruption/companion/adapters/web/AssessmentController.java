package com.disruption.companion.adapters.web;

import com.disruption.companion.application.ActionExecutionResult;
import com.disruption.companion.application.DisruptionAssessment;
import com.disruption.companion.application.EvaluateDisruption;
import com.disruption.companion.application.ExecuteDisruptionAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class AssessmentController {

    private final EvaluateDisruption evaluateDisruption;
    private final ExecuteDisruptionAction executeDisruptionAction;

    public AssessmentController(EvaluateDisruption evaluateDisruption,
                                ExecuteDisruptionAction executeDisruptionAction) {
        this.evaluateDisruption = evaluateDisruption;
        this.executeDisruptionAction = executeDisruptionAction;
    }

    @GetMapping("/{bookingId}/assessment")
    public DisruptionAssessment assess(@PathVariable String bookingId,
                                       @RequestParam(defaultValue = "false") boolean compensationRequested) {
        return evaluateDisruption.evaluate(bookingId, compensationRequested);
    }

    @PostMapping("/{bookingId}/actions/rebook")
    public ActionExecutionResult rebook(@PathVariable String bookingId,
                                        @RequestParam(defaultValue = "false") boolean compensationRequested,
                                        @RequestParam(required = false) String optionId) {
        return executeDisruptionAction.execute(bookingId, compensationRequested, optionId);
    }
}
