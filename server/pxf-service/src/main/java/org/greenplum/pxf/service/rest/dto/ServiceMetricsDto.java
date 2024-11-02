package org.greenplum.pxf.service.rest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;

import java.util.Collection;

@Getter
@RequiredArgsConstructor
public class ServiceMetricsDto {
    private final String status;
    private final Collection<MetricsEndpoint.MetricDescriptor> metrics;
}
