package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.integration.IntegrationConnection;

import java.util.List;

/** Task 0087: the destinations' published profile files in the installation's profile folder. */
public interface DestinationProfilePort {

    /**
     * One profile file. A file with problems has no name; {@code problems} then says where in the file, with codes only.
     *
     * @param fields       the destination's field names ({@code *} at the end: required)
     * @param otherFields  whether other names may be mapped
     * @param carriesLevel whether a document's sensitivity level can be sent
     * @param carriesCaseLevel whether the case's sensitivity level can be sent
     */
    record Profile(String file, String name, String version, String description, List<String> signInTypes,
                   List<String> fields, boolean takesFields, boolean otherFields, boolean carriesLevel,
                   boolean carriesCaseLevel, List<String> notes, List<String> problems) { }

    List<Profile> list();

    /**
     * The connection as it may be stored: its profile's name and version written in, after the checks a delivery would
     * make (address, sign-in, secret reference, field mapping, settings).
     *
     * @throws com.securedoc.platform.application.exception.ValidationException LV-CFG-003, naming the field, never a value
     */
    IntegrationConnection checked(IntegrationConnection connection);
}
