package org.greenplum.pxf.service.spring;

import jakarta.servlet.ServletException;
import org.greenplum.pxf.service.HttpHeaderDecoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

@ExtendWith(MockitoExtension.class)
class PxfContextMdcLogEnhancerFilterTest {

    PxfContextMdcLogEnhancerFilter filter;
    MockHttpServletRequest mockRequest;
    MockHttpServletResponse mockResponse;
    MockFilterChain mockFilterChain;

    @BeforeEach
    void setup() {
        mockRequest = new MockHttpServletRequest();
        mockResponse = new MockHttpServletResponse();
        mockFilterChain = new MockFilterChain();
        filter = new PxfContextMdcLogEnhancerFilter(new HttpHeaderDecoder());
    }

    @Test
    void testNonPxfContextRequest() throws ServletException, IOException {
        try (var mdcMock = Mockito.mockStatic(MDC.class)) {
            filter.doFilter(mockRequest, mockResponse, mockFilterChain);

            // always removes
            mdcMock.verify(() -> MDC.remove("segmentId"));
            mdcMock.verify(() -> MDC.remove("sessionId"));
            mdcMock.verifyNoMoreInteractions();
        }
    }

    @Test
    void testPxfContextRequest() throws ServletException, IOException {
        try (var mdcMock = Mockito.mockStatic(MDC.class)) {
            mockRequest.addHeader("X-GP-XID", "transaction:id");
            mockRequest.addHeader("X-GP-SEGMENT-ID", "5");
            filter.doFilter(mockRequest, mockResponse, mockFilterChain);

            mdcMock.verify(() -> MDC.put("sessionId", "transaction:id:default"));
            mdcMock.verify(() -> MDC.put("segmentId", "5"));

            mdcMock.verify(() -> MDC.remove("segmentId"));
            mdcMock.verify(() -> MDC.remove("sessionId"));
            mdcMock.verifyNoMoreInteractions();
        }
    }

    @Test
    void testPxfContextRequestWithServerName() throws ServletException, IOException {
        try (var mdcMock = Mockito.mockStatic(MDC.class)) {
            mockRequest.addHeader("X-GP-XID", "transaction:id");
            mockRequest.addHeader("X-GP-OPTIONS-SERVER", "s3");
            mockRequest.addHeader("X-GP-SEGMENT-ID", "5");
            filter.doFilter(mockRequest, mockResponse, mockFilterChain);

            mdcMock.verify(() -> MDC.put("sessionId", "transaction:id:s3"));
            mdcMock.verify(() -> MDC.put("segmentId", "5"));

            mdcMock.verify(() -> MDC.remove("segmentId"));
            mdcMock.verify(() -> MDC.remove("sessionId"));
            mdcMock.verifyNoMoreInteractions();
        }
    }

    @Test
    void testPxfContextEncodedRequest() throws ServletException, IOException {
        try (var mdcMock = Mockito.mockStatic(MDC.class)) {
            mockRequest.addHeader("X-GP-ENCODED-HEADER-VALUES", "true");
            mockRequest.addHeader("X-GP-XID", "transaction%3Aid");
            mockRequest.addHeader("X-GP-SEGMENT-ID", "5");
            filter.doFilter(mockRequest, mockResponse, mockFilterChain);

            mdcMock.verify(() -> MDC.put("sessionId", "transaction:id:default"));
            mdcMock.verify(() -> MDC.put("segmentId", "5"));

            mdcMock.verify(() -> MDC.remove("segmentId"));
            mdcMock.verify(() -> MDC.remove("sessionId"));
            mdcMock.verifyNoMoreInteractions();
        }
    }
}