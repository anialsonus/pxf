package org.greenplum.pxf.service.rest;

import org.greenplum.pxf.service.rest.dto.ServiceMetricsDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceMetricsRestControllerTest {
    private static final String clusterName = "cluster name";
    private static final String hostName = "host name";
    private final MetricsEndpoint metricsEndpoint = mock(MetricsEndpoint.class);
    private final HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
    private final ServiceMetricsRestController controller = new ServiceMetricsRestController(metricsEndpoint, clusterName, hostName, healthEndpoint);

    @Test
    public void get() {
        MetricsEndpoint.MetricDescriptor metricDescriptor = mock(MetricsEndpoint.MetricDescriptor.class);
        when(metricsEndpoint.metric(anyString(), any())).thenReturn(metricDescriptor).thenReturn(null);
        HealthComponent healthComponent = mock(HealthComponent.class);
        Status status = mock(Status.class);
        String code = "DOWN";
        when(status.getCode()).thenReturn(code);
        when(healthComponent.getStatus()).thenReturn(status);
        when(healthEndpoint.health()).thenReturn(healthComponent);
        ServiceMetricsDto result = controller.get();
        assertEquals(code, result.getStatus());
        assertEquals(1, result.getMetrics().size());
        result.getMetrics().stream().findAny().filter(metricDescriptor::equals).orElseGet(Assertions::fail);
    }

    @Test
    public void getClusterMetrics() {
        MetricsEndpoint.MetricDescriptor metricDescriptor = mock(MetricsEndpoint.MetricDescriptor.class);
        when(metricsEndpoint.metric(anyString(), any())).thenReturn(metricDescriptor).thenReturn(null);
        ServiceMetricsRestController.ClusterMetrics result = controller.getClusterMetrics();
        assertEquals(clusterName, result.getCluster());
        assertEquals(hostName, result.getHostname());
        assertEquals(1, result.getMetrics().size());
        result.getMetrics().stream().findAny().filter(metricDescriptor::equals).orElseGet(Assertions::fail);
    }
}