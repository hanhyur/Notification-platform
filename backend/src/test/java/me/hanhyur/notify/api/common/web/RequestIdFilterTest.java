package me.hanhyur.notify.api.common.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @BeforeEach
    void clearMdcBeforeTest() {
        MDC.clear();
    }

    @AfterEach
    void clearMdcAfterTest() {
        MDC.clear();
    }

    @Test
    void usesSameIdForAttributeHeaderAndMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            String requestId = requestId(response);

            assertEquals(
                    requestId,
                    req.getAttribute(RequestIdFilter.ATTRIBUTE_NAME)
            );
            assertEquals(requestId, MDC.get(RequestIdFilter.MDC_KEY));
        });
    }

    @Test
    void usesDifferentIdsForDifferentRequests() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest(), first, (req, res) -> {
                }
        );
        filter.doFilter(
                new MockHttpServletRequest(), second, (req, res) -> {
                }
        );

        assertNotEquals(requestId(first), requestId(second));
    }

    @Test
    void generatesServerIdWhenClientProvidesHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String clientValue = "req-demo-0001";

        request.addHeader(RequestIdFilter.HEADER_NAME, clientValue);
        filter.doFilter(request, response, (req, res) -> {
        });

        assertNotEquals(clientValue, requestId(response));
    }

    @Test
    void clearsRequestIdAfterSuccess() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> {}
        );

        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void clearsRequestIdWhenChainThrows() {
        assertThrows(
                ServletException.class,
                () -> filter.doFilter(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        (req, res) -> {
                            throw new ServletException("test failure");
                        }
                )
        );

        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void preservesOtherMdcEntries() throws Exception {
        MDC.put("other", "keep");

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> {

                }
        );

        assertEquals("keep", MDC.get("other"));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void setsHeaderForErrorStatus() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (req, res) -> response.setStatus(422)
        );

        assertEquals(422, response.getStatus());
        requestId(response);
    }

    private static String requestId(MockHttpServletResponse response) {
        String value = response.getHeader(RequestIdFilter.HEADER_NAME);

        assertNotNull(value);
        assertEquals(value, UUID.fromString(value).toString());

        return value;
    }
}