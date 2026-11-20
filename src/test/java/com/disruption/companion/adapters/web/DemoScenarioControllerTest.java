package com.disruption.companion.adapters.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class DemoScenarioControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void expiredTtlScenarioHasNoRecommendation() throws Exception {
        mockMvc.perform(get("/api/demo/scenarios/expired-ttl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended").doesNotExist())
                .andExpect(jsonPath("$.autoRebook").value(false));
    }

    @Test
    void duplicate409ScenarioReturnsAlreadyRequested() throws Exception {
        mockMvc.perform(post("/api/demo/scenarios/duplicate-409"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_REQUESTED"))
                .andExpect(jsonPath("$.requestId").value("RBK-88231"));
    }

    @Test
    void retry503Then201ScenarioReusesSameKey() throws Exception {
        mockMvc.perform(post("/api/demo/scenarios/retry-503-then-201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("REBOOKING_REQUESTED"))
                .andExpect(jsonPath("$.result.attempts").value(3))
                .andExpect(jsonPath("$.backoffsSeconds[0]").value(1))
                .andExpect(jsonPath("$.backoffsSeconds[1]").value(2));
    }

    @Test
    void timeout503BlocksO2ScenarioKeepsLockAfterEscalation() throws Exception {
        mockMvc.perform(post("/api/demo/scenarios/timeout-503-blocks-o2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstAttemptO1.status").value("ESCALATED_TO_HUMAN"))
                .andExpect(jsonPath("$.secondAttemptO2.status").value("BLOCKED_PENDING_RESOLUTION"))
                .andExpect(jsonPath("$.totalRebookingCalls").value(3));
    }

    @Test
    void missingPolicyRef422ScenarioRejectsNotification() throws Exception {
        mockMvc.perform(post("/api/demo/scenarios/missing-policy-ref-422"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("UNVALIDATED_CONTENT"));
    }
}
