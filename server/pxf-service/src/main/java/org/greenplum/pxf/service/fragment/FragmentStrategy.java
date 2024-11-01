package org.greenplum.pxf.service.fragment;

import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;

import java.util.Collection;
import java.util.List;

public interface FragmentStrategy {

    List<Fragment> filterFragments(Collection<Fragment> fragments, RequestContext context);

    FragmentDistributionPolicy getDistributionPolicy();
}
