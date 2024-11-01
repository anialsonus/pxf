package org.greenplum.pxf.service.fragment;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Getter
public enum FragmentDistributionPolicy {
    ROUND_ROBIN("round-robin"),
    ACTIVE_SEGMENT("active-segment"),
    IMPROVED_ROUND_ROBIN("improved-round-robin"),
    RANDOM("random");

    private final String name;
    public static final Map<String, FragmentDistributionPolicy> POLICY_NAME_MAP = Arrays.stream(FragmentDistributionPolicy.values())
            .collect(Collectors.toMap(FragmentDistributionPolicy::getName, policy -> policy));

    FragmentDistributionPolicy(String name) {
        this.name = name;
    }

    public static FragmentDistributionPolicy fromName(String name) {
        return Optional.ofNullable(POLICY_NAME_MAP.get(name.toLowerCase()))
                .orElseThrow(() -> new IllegalArgumentException("Cannot find corresponding fragment distribution policy with name " + name));
    }
}
