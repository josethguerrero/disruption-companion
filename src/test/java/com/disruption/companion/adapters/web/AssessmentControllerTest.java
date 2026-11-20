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
class AssessmentControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void returnsAssessmentForBaseCase() throws Exception {
        mockMvc.perform(get("/api/bookings/ABC123/assessment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("ABC123"))
                .andExpect(jsonPath("$.delayMinutes").value(160))
                .andExpect(jsonPath("$.recommended.flightNumber").value("LA2412"))
                .andExpect(jsonPath("$.autoRebook").value(true));
    }

    @Test
    void executesRebookActionForBaseCase() throws Exception {
        mockMvc.perform(post("/api/bookings/ABC123/actions/rebook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value("ABC123"))
                .andExpect(jsonPath("$.idempotencyKey").value("ABC123:S1:E-1001"))
                .andExpect(jsonPath("$.status").value("REBOOKING_REQUESTED"))
                .andExpect(jsonPath("$.requestId").value("RBK-88231"))
                .andExpect(jsonPath("$.humanCase.caseId").value("HC-4471"));
    }
}
