package me.hanhyur.notify.api.common.web;

import me.hanhyur.notify.api.support.PostgresTestConfiguration;

import jakarta.validation.Valid;
import me.hanhyur.notify.api.notification.dto.NotificationCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ApiExceptionHandlerIntegrationTest.ProbeController.class, PostgresTestConfiguration.class})
class ApiExceptionHandlerIntegrationTest {

    private static final String PATH = "/_test/problem";
    private static final String VALID_BODY = """
            {
              "customerId": "customer-1001",
              "channel": "EMAIL",
              "recipient": "sample@example.com",
              "title": "에약 안내",
              "content": "예약이 접수되었습니다."
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acceptsValidRequest() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void returnsFieldErrorsForMissingValues() throws Exception {
        ResultActions result = mockMvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        assertProblem(result, 422, "UnprocessableEntity", "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors", aMapWithSize(5)));

        for (String field : new String[]{
                "customerId", "channel", "recipient", "title", "content"
        }) {
            result.andExpect(jsonPath("$.fieldErrors." + field).isArray())
                    .andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty());
        }
    }

    @Test
    void preservesMultipleErrorsForSameField() throws Exception {
        String body = VALID_BODY.replace("customer-1001", " ".repeat(101));

        ResultActions result = mockMvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        assertProblem(result, 422, "UnprocessableEntity", "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors", aMapWithSize(1)))
                .andExpect(jsonPath("$.fieldErrors.customerId", hasSize(2)));
    }

    @Test
    void returnsProblemForMalformedJson() throws Exception {
        ResultActions result = mockMvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"));

        assertProblem(result, 400, "BadRequest", "INVALID_REQUEST")
                .andExpect(jsonPath("$.fieldErrors", aMapWithSize(0)));
    }

    @Test
    void returnsProblemForUnsupportedMediaType() throws Exception {
        ResultActions result = mockMvc.perform(post(PATH)
                .contentType(MediaType.TEXT_PLAIN)
                .content(VALID_BODY));

        assertProblem(result, 415, "UnsupportedMediaType", "UNSUPPORTED_MEDIA_TYPE")
                .andExpect(jsonPath("$.fieldErrors", aMapWithSize(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"validation", "json", "media"})
    void connectsProblemToRequestId(String scenario) throws Exception {
        MockHttpServletRequestBuilder request = switch (scenario) {
            case "validation" -> post(PATH)
                    .contentType(MediaType.APPLICATION_JSON).content("{}");
            case "json" -> post(PATH)
                    .contentType(MediaType.APPLICATION_JSON).content("{");
            case "media" -> post(PATH)
                    .contentType(MediaType.TEXT_PLAIN).content(VALID_BODY);
            default -> throw new IllegalArgumentException(scenario);
        };

        ResultActions result = mockMvc.perform(request);
        MvcResult mvcResult = result.andReturn();
        String requestId = mvcResult.getResponse()
                .getHeader(RequestIdFilter.HEADER_NAME);

        assertNotNull(requestId);
        assertEquals(requestId, UUID.fromString(requestId).toString());
        assertEquals(requestId, mvcResult.getRequest()
                .getAttribute(RequestIdFilter.ATTRIBUTE_NAME));
        result.andExpect(jsonPath("$.requestId").value(requestId));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void doesNotExposeRecipientOrContent() throws Exception {
        String body = VALID_BODY.replace("\"EMAIL\"", "\"UNKNOWN_CHANNEL\"");

        MvcResult result = assertProblem(
                mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)),
                400, "BadRequest", "INVALID_REQUEST"
        ).andReturn();

        String response = result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertFalse(response.contains("sample@example.com"));
        assertFalse(response.contains("예약이 접수되었습니다."));
        assertFalse(response.contains("UNKNOWN_CHANNEL"));
        assertFalse(response.contains("HttpMessageNotReadableException"));
    }

    private ResultActions assertProblem(
            ResultActions result,
            int expectedStatus,
            String title,
            String code
    ) throws Exception {
        return result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", aMapWithSize(8)))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @TestComponent
    @RestController
    public static class ProbeController {

        @PostMapping(value = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
        public ResponseEntity<Void> probe(
                @Valid @RequestBody NotificationCreateRequest request
        ) {
            return ResponseEntity.noContent().build();
        }
    }
}
