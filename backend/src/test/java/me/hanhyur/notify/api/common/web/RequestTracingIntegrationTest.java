package me.hanhyur.notify.api.common.web;

import me.hanhyur.notify.api.support.PostgresTestConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import({RequestTracingIntegrationTest.ProbeController.class, PostgresTestConfiguration.class})
public class RequestTracingIntegrationTest {

    private static final String MARKER = "request-tracing-check";

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void clearMdcAfterTest() {
        MDC.clear();
    }

    @Test
    void connectsResponseIdToConsoleLog(CapturedOutput output) throws Exception {
        MvcResult result = mockMvc.perform(get("/_test/request-tracing"))
                .andExpect(status().isNoContent())
                .andReturn();

        String requestId = result.getResponse()
                .getHeader(RequestIdFilter.HEADER_NAME);

        assertNotNull(requestId);
        assertEquals(requestId, UUID.fromString(requestId).toString());
        assertEquals(
                requestId,
                result.getRequest().getAttribute(RequestIdFilter.ATTRIBUTE_NAME)
        );

        String line = output.getOut().lines()
                .filter(value -> value.contains(MARKER))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "검증용 INFO 로그를 찾을 수 없습니다."
                ));

        assertTrue(line.contains("[requestId=" + requestId + "]"));
    }

    @Test
    void addsHeaderWhenRouteDoesNotExist() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/_test/request-tracing/missing")
                )
                .andExpect(status().isNotFound())
                .andReturn();

        String requestId = result.getResponse()
                .getHeader(RequestIdFilter.HEADER_NAME);

        assertNotNull(requestId);
        assertEquals(requestId, UUID.fromString(requestId).toString());
    }

    @TestComponent
    @RestController
    public static class ProbeController {

        private static final Logger log =
                LoggerFactory.getLogger(ProbeController.class);

        @GetMapping("/_test/request-tracing")
        public ResponseEntity<Void> probe() {
            log.info(MARKER);
            return ResponseEntity.noContent().build();
        }
    }
}
