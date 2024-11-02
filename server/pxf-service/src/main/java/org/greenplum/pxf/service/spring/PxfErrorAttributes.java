package org.greenplum.pxf.service.spring;

import jakarta.servlet.ServletException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.greenplum.pxf.api.configuration.PxfServerProperties;
import org.greenplum.pxf.api.error.PxfRuntimeException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.NestedServletException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * Enhances the default implementation of {@link ErrorAttributes} by adding a
 * hint to the map. Provides the following attributes when possible:
 * <ul>
 * <li>timestamp - The time that the errors were extracted</li>
 * <li>status - The status code</li>
 * <li>error - The error reason</li>
 * <li>exception - The class name of the root exception (if configured)</li>
 * <li>message - The exception message</li>
 * <li>errors - Any {@link ObjectError}s from a {@link BindingResult} exception
 * <li>trace - The exception stack trace</li>
 * <li>path - The URL path when the exception was raised</li>
 * <li>hint - A hint for the user to take some action to diagnose the error</li>
 * </ul>
 *
 * @see ErrorAttributes
 */
@Component
public class PxfErrorAttributes extends DefaultErrorAttributes {

    private static final String DEFAULT_HINT = "Check the PXF logs located in the '%s/logs' directory on host '%s' or 'set client_min_messages=LOG' for additional details.";

    private final PxfServerProperties pxfProperties;
    private final ServerProperties properties;

    /**
     * Constructs a new instance of {@link PxfErrorAttributes} with the given
     * configuration properties
     *
     * @param pxfProperties the {@link PxfServerProperties}
     * @param properties    the {@link ServerProperties}
     */
    public PxfErrorAttributes(PxfServerProperties pxfProperties, ServerProperties properties) {
        this.pxfProperties = pxfProperties;
        this.properties = properties;
    }

    /**
     * Returns an enhanced {@link Map} of the error attributes with a hint.
     * The map can be used as the model of an error page {@link ModelAndView},
     * or returned as a {@link ResponseBody @ResponseBody}.
     *
     * @param webRequest the source request
     * @param options    options for error attribute contents
     * @return a map of error attributes and a hint
     */
    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> errorAttributes = super.getErrorAttributes(webRequest, options);

        Throwable throwable = getError(webRequest);
        StringBuilder hint = new StringBuilder(DEFAULT_HINT.length() * 3);
        if (throwable instanceof PxfRuntimeException) {
            PxfRuntimeException pxfRuntimeException = (PxfRuntimeException) throwable;
            if (StringUtils.isNotBlank(pxfRuntimeException.getHint())) {
                hint.append(pxfRuntimeException.getHint()).append("\n");
            }
            // If nested exception is enabled it is better to show the stack trace from the getCause()
            if (pxfRuntimeException.getCause() != null && pxfProperties.isNestedExceptionEnabled()) {
                throwable = pxfRuntimeException.getCause();
            }
        }

        String hostname = getHostname();
        hint.append(String.format(DEFAULT_HINT, pxfProperties.getBase(), hostname));

        if (throwable != null && pxfProperties.isNestedExceptionEnabled()) {
            appendNestedExceptionInformation(throwable, hint);
        }

        errorAttributes.put("hint", hint.toString());

        if (throwable != null && StringUtils.isNotBlank(throwable.getMessage())) {
            // Provide the error message from the resolved throwable instead
            errorAttributes.put("message", throwable.getMessage());
        }

        return errorAttributes;
    }

    private void appendNestedExceptionInformation(Throwable throwable, StringBuilder hint) {
        hint.append("\nCompact trace:\n");
        if (getNestedExceptionWrappedDepth() > 0) {
            appendWrappedException(throwable, hint);
        }
        if (pxfProperties.getNestedExceptionTraceDepth() > 0) {
            appendStackTraceInformation(throwable, hint);
        }
    }

    private void appendWrappedException(Throwable throwable, StringBuilder hint) {
        String[] traces = ExceptionUtils.getRootCauseStackTrace(throwable);
        int nestedExceptionWrappedDepth = getNestedExceptionWrappedDepth();
        String lastWrappedException = "";
        for (String trace : traces) {
            if (nestedExceptionWrappedDepth-- <= 0) {
                // The limit has been reached. We check the last wrapped message, and if it is not the same
                // as the throwable, we add the throwable to the output. If we don’t print the latest throwable,
                // the stack trace added below (nestedExceptionTraceDepth) will not be correct.
                lastWrappedException = getLastWrappedException(lastWrappedException);
                if (StringUtils.isNotEmpty(lastWrappedException) && !lastWrappedException.equals(throwable.toString())) {
                    hint.append(" ......... there are more than ").append(getNestedExceptionWrappedDepth())
                            .append(" messages in the stack. We will get the last one.\n");
                    hint.append(" [wrapped] ").append(throwable).append("\n");
                }
                break;
            }
            if (trace.startsWith("\t")) {
                continue;
            }
            hint.append(trace).append("\n");
            lastWrappedException = trace;
        }
    }

    private void appendStackTraceInformation(Throwable throwable, StringBuilder hint) {
        int nestedExceptionTraceDepth = pxfProperties.getNestedExceptionTraceDepth();
        // We need to add the last throwable if the wrapped stack traces are turned off
        if (getNestedExceptionWrappedDepth() <= 0) {
            hint.append(throwable).append("\n");
        }
        for (StackTraceElement ste : throwable.getStackTrace()) {
            if (nestedExceptionTraceDepth-- <= 0) {
                break;
            }
            hint.append("\tat ").append(ste).append("\n");
        }
    }

    private int getNestedExceptionWrappedDepth() {
        return pxfProperties.getNestedExceptionWrappedDepth();
    }

    private String getLastWrappedException(String exceptionStr) {
        String[] result = exceptionStr.split("\\[wrapped]");
        return exceptionStr.split("\\[wrapped]").length > 1 ? result[1].trim() : exceptionStr;
    }

    /**
     * Attempt to return a {@link PxfRuntimeException} or unwrap exceptions
     * to bubble up a desired cause (i.e. do not report
     * {@link BeanCreationException} but the original cause). Return
     * {@code null} if the error cannot be extracted.
     *
     * @param webRequest the source request
     * @return the {@link Exception} that caused the error or {@code null}
     */
    @Override
    public Throwable getError(WebRequest webRequest) {
        Throwable exception = super.getError(webRequest);
        return getReportableException(exception);
    }

    /**
     * Get the exception to report. If there is a PxfRuntimeException as the
     * cause, report it. Otherwise bubble up exceptions that are of type
     * {@link BeanCreationException} or {@link NestedServletException}
     *
     * @param exception the original exception
     * @return the exception to report
     */
    private Throwable getReportableException(Throwable exception) {
        if (exception == null) {
            return null;
        }

        Throwable exceptionToReport = exception;
        while (exception.getCause() != null) {
            if (exception.getCause() instanceof PxfRuntimeException) {
                // Report PxfRuntimeException if found as the cause
                exceptionToReport = exception.getCause();
                break;
            }

            if (exception instanceof BeanCreationException ||
                    exception instanceof ServletException) {
                // Unwrap exception
                exceptionToReport = exception.getCause();
            }

            exception = exception.getCause();
        }
        return exceptionToReport;
    }

    /**
     * Returns the hostname to report for the hint
     *
     * @return the hostname to report for the hint
     */
    private String getHostname() {
        if (properties != null && properties.getAddress() != null) {
            return properties.getAddress().getHostName();
        } else {
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                return System.getenv("HOSTNAME");
            }
        }
    }
}
