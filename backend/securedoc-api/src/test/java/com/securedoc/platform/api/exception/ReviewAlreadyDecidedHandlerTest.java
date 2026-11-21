package com.securedoc.platform.api.exception;

import com.securedoc.platform.application.exception.ReviewAlreadyDecidedException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0023 (D5, #160): through Spring's handler selection (not only a direct call), "already decided" is a 409 with
 * errorCode REVIEW_ALREADY_DECIDED and the fixed message, and does not fall into the 500 ApplicationException handler.
 */
class ReviewAlreadyDecidedHandlerTest {

    @RestController
    static class Throwing {
        @PostMapping("/decide") void decide() { throw new ReviewAlreadyDecidedException(); }
    }

    @Test
    void alreadyDecidedIs409WithTheOneCode() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Throwing())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/decide"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_ALREADY_DECIDED"))
                .andExpect(jsonPath("$.message").value("This item was already decided by someone else."))
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
