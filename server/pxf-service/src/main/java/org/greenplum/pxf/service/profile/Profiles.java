package org.greenplum.pxf.service.profile;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;

import java.util.List;

/**
 * Profiles is the root element for the list of profiles
 * defined in the profiles XML file
 */
@Getter
@XmlRootElement(name = "profiles")
@XmlAccessorType(XmlAccessType.FIELD)
public class Profiles {

    /**
     * -- GETTER --
     *  Returns a list of
     *  objects
     *
     * @return a list of {@link Profile} objects
     */
    @XmlElement(name = "profile")
    private List<Profile> profiles;

}
