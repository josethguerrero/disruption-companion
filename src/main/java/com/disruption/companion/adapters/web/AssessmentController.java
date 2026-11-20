package com.disruption.companion.adapters.web;

import com.disruption.companion.application.DisruptionAssessment;
import com.disruption.companion.application.EvaluateDisruption;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class AssessmentController {

    private final EvaluateDisruption evaluateDisruption;

    public AssessmentController(EvaluateDisruption evaluateDisruption) {
        this.evaluateDisruption = evaluateDisruption;
    }

    @GetMapping("/{bookingId}/assessment")
    public DisruptionAssessment assess(@PathVariable String bookingId,
                                       @RequestParam(defaultValue = "false") boolean compensationRequested) {
        return evaluateDisruption.evaluate(bookingId, compensationRequested);
    }
}
