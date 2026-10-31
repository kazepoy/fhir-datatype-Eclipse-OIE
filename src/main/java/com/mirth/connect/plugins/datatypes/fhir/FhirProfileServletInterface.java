/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Permissions;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;

/**
 * REST API of the FHIR Profile Mapper step: the profiles in a set of packages, the
 * StructureDefinition (with snapshot) the web administrator builds the tree of a profile from, and
 * the ConceptMaps and $translate of a terminology server, which the browser cannot reach itself.
 * Under /api/extensions/datatype-fhir. Answers are FHIR JSON or plain JSON, never XStream.
 */
@Path("/extensions/datatype-fhir")
public interface FhirProfileServletInterface extends BaseServletInterface {

    /**
     * The resource profiles in the packages, as a JSON array of objects with url, name, title and type.
     *
     * @param fhirVersion R4, R4B or R5
     * @param profiles files and folders with profiles and packages on the server, separated by ';'
     */
    @GET
    @Path("/profiles")
    @Produces(MediaType.APPLICATION_JSON)
    @MirthOperation(name = "getFhirProfiles", display = "Get FHIR profiles", permission = Permissions.CHANNELS_MANAGE)
    String getProfiles(@QueryParam("fhirVersion") String fhirVersion, @QueryParam("profiles") String profiles) throws ClientException;

    /**
     * A StructureDefinition as FHIR JSON, with its snapshot.
     *
     * @param url canonical URL of a profile, or the name of a resource or data type (Patient, HumanName)
     */
    @GET
    @Path("/structure")
    @Produces(MediaType.APPLICATION_JSON)
    @MirthOperation(name = "getFhirStructure", display = "Get FHIR StructureDefinition", permission = Permissions.CHANNELS_MANAGE)
    String getStructure(@QueryParam("url") String url, @QueryParam("fhirVersion") String fhirVersion, @QueryParam("profiles") String profiles) throws ClientException;

    /**
     * The ConceptMaps of a terminology server, or without one (empty or "n/a") of the profile
     * packages, as a JSON array of objects with url, name, title, source and target.
     *
     * @param server base URL of the terminology server, as the OIE server reaches it, or "n/a"
     */
    @GET
    @Path("/conceptMaps")
    @Produces(MediaType.APPLICATION_JSON)
    @MirthOperation(name = "getFhirConceptMaps", display = "Get FHIR ConceptMaps", permission = Permissions.CHANNELS_MANAGE)
    String getConceptMaps(@QueryParam("server") String server, @QueryParam("profiles") String profiles, @QueryParam("fhirVersion") String fhirVersion) throws ClientException;

    /**
     * ConceptMap/$translate of one code, as the step does it: the match as JSON with system, code
     * and display, or null.
     */
    @GET
    @Path("/translate")
    @Produces(MediaType.APPLICATION_JSON)
    @MirthOperation(name = "translateFhirCode", display = "Translate a code with a FHIR ConceptMap", permission = Permissions.CHANNELS_MANAGE)
    String translate(@QueryParam("server") String server, @QueryParam("profiles") String profiles, @QueryParam("conceptMap") String conceptMap,
            @QueryParam("system") String system, @QueryParam("code") String code, @QueryParam("targetSystem") String targetSystem,
            @QueryParam("fhirVersion") String fhirVersion) throws ClientException;

    /**
     * The script a step generates, and its syntax errors: {"script", "errors": [{"key", "line",
     * "message"}]}. The body is the step as the web administrator has it (JSON).
     */
    @POST
    @Path("/script")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @MirthOperation(name = "getFhirMapperScript", display = "Get the script of a FHIR Profile Mapper step", permission = Permissions.CHANNELS_MANAGE)
    String getScript(String step) throws ClientException;

    /**
     * Runs a step on a sample message and validates what it makes: body {"step", "message",
     * "dataType"}, answer {"output", "issues", "errors"}. Runs the step's JavaScript on the server, as
     * deploying the channel would.
     */
    @POST
    @Path("/test")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @MirthOperation(name = "testFhirMapper", display = "Test a FHIR Profile Mapper step", permission = Permissions.CHANNELS_MANAGE)
    String test(String request) throws ClientException;
}
