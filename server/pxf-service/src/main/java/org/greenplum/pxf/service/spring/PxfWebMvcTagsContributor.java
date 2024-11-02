package org.greenplum.pxf.service.spring;

import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang.StringUtils;
import org.greenplum.pxf.service.HttpHeaderDecoder;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;
import org.springframework.stereotype.Component;

/**
 * Custom {@link ServerRequestObservationConvention} that adds PXF specific tags to metrics for Spring MVC (REST endpoints)
 *
 * @return the {@link ServerRequestObservationConvention} instance
 */
@Component
public class PxfWebMvcTagsContributor extends DefaultServerRequestObservationConvention {

    private static final String UNKNOWN_VALUE = "unknown";
    private final HttpHeaderDecoder decoder;

    public PxfWebMvcTagsContributor(HttpHeaderDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        // here, we just want to have an additional KeyValue to the observation, keeping the default values
        // default server tag value to "default" if the request is from a PXF Client
        // if request is not from PXF client, apply the same tags wth the value "unknown"
        // because the Prometheus Metrics Registry requires a metric to have a consistent set of tags
        boolean encoded = decoder.areHeadersEncoded(request);
        String defaultServer = StringUtils.isNotBlank(request.getHeader("X-GP-USER")) ? "default" : UNKNOWN_VALUE;
        return super.getLowCardinalityKeyValues(context)
                .and("user", StringUtils.defaultIfBlank(decoder.getHeaderValue("X-GP-USER", request, encoded), UNKNOWN_VALUE))
                .and("segment", StringUtils.defaultIfBlank(decoder.getHeaderValue("X-GP-SEGMENT-ID", request, encoded), UNKNOWN_VALUE))
                .and("profile", StringUtils.defaultIfBlank(decoder.getHeaderValue("X-GP-OPTIONS-PROFILE", request, encoded), UNKNOWN_VALUE))
                .and("server", StringUtils.defaultIfBlank(decoder.getHeaderValue("X-GP-OPTIONS-SERVER", request, encoded), defaultServer));
    }
}
