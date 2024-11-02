package org.greenplum.pxf.service.spring;

import io.micrometer.common.KeyValues;
import org.greenplum.pxf.service.HttpHeaderDecoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class PxfWebMvcTagsContributorTest {

    private PxfWebMvcTagsContributor contributor;
    private MockHttpServletRequest mockRequest;
    private ServerRequestObservationContext requestContext;

    @BeforeEach
    public void setup() {
        contributor = new PxfWebMvcTagsContributor(new HttpHeaderDecoder());
        mockRequest = new MockHttpServletRequest();
        requestContext = new ServerRequestObservationContext(mockRequest, new MockHttpServletResponse());
    }

    @Test
    public void testPxfWebMvcTagsContributor_pxfEndpoint_namedServer() {
        mockRequest.addHeader("X-GP-USER", "Alex");
        mockRequest.addHeader("X-GP-SEGMENT-ID", "5");
        mockRequest.addHeader("X-GP-OPTIONS-PROFILE", "test:text");
        mockRequest.addHeader("X-GP-OPTIONS-SERVER", "test_server");

        KeyValues expectedTags = KeyValues.of("user", "Alex")
                .and("segment", "5")
                .and("profile", "test:text")
                .and("server", "test_server");

        KeyValues tags = contributor.getLowCardinalityKeyValues(requestContext);

        assertThat(tags).containsAll(expectedTags);
    }

    @Test
    public void testPxfWebMvcTagsContributor_pxfEndpoint_defaultServer() {
        mockRequest.addHeader("X-GP-USER", "Alex");
        mockRequest.addHeader("X-GP-SEGMENT-ID", "5");
        mockRequest.addHeader("X-GP-OPTIONS-PROFILE", "test:text");

        KeyValues expectedTags = KeyValues.of("user", "Alex")
                .and("segment", "5")
                .and("profile", "test:text")
                .and("server", "default");

        KeyValues tags = contributor.getLowCardinalityKeyValues(requestContext);

        assertThat(tags).containsAll(expectedTags);
    }

    @Test
    public void testPxfWebMvcTagsContributor_pxfEndpoint_encoded() {
        mockRequest.addHeader("X-GP-ENCODED-HEADER-VALUES", "true");
        mockRequest.addHeader("X-GP-USER", "Alex");
        mockRequest.addHeader("X-GP-SEGMENT-ID", "5");
        mockRequest.addHeader("X-GP-OPTIONS-PROFILE", "test%3Atext");
        mockRequest.addHeader("X-GP-OPTIONS-SERVER", "test_server");

        KeyValues expectedTags = KeyValues.of("user", "Alex")
                .and("segment", "5")
                .and("profile", "test:text")
                .and("server", "test_server");

        KeyValues tags = contributor.getLowCardinalityKeyValues(requestContext);

        assertThat(tags).containsAll(expectedTags);
    }

    @Test
    public void testPxfWebMvcTagsContributor_nonPxfEndpoint() {
        KeyValues expectedTags = KeyValues.of("user", "unknown")
                .and("segment", "unknown")
                .and("profile", "unknown")
                .and("server", "unknown");

        KeyValues tags = contributor.getLowCardinalityKeyValues(requestContext);

        assertThat(tags).containsAll(expectedTags);
    }

}
