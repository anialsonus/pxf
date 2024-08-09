package org.greenplum.pxf.service.spring;

import jakarta.servlet.http.HttpServletResponse;
import org.greenplum.pxf.api.error.PxfRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PxfExceptionHandlerTest {

    PxfExceptionHandler handler;
    @Mock
    HttpServletResponse mockResponse;

    @BeforeEach
    public void setup() {
        handler = new PxfExceptionHandler();
    }

    @Test
    public void testHandlePxfRuntimeException_ResponseNotCommitted() throws IOException {
        when(mockResponse.isCommitted()).thenReturn(false);
        handler.handlePxfRuntimeException(new PxfRuntimeException("foo"), mockResponse);
        verify(mockResponse).sendError(500);
        verifyNoMoreInteractions(mockResponse);
    }

    @Test
    public void testHandlePxfRuntimeException_ResponseCommitted() {
        String originalMessage = "foo";
        PxfRuntimeException originalException = new PxfRuntimeException(originalMessage);
        when(mockResponse.isCommitted()).thenReturn(true);
        PxfRuntimeException thrownException = assertThrows(PxfRuntimeException.class,
                () -> handler.handlePxfRuntimeException(originalException, mockResponse));
        assertSame(originalException, thrownException);
        assertEquals(originalMessage, thrownException.getMessage());
        verifyNoMoreInteractions(mockResponse);
    }
}
