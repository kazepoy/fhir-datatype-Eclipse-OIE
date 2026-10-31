/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.List;
import java.util.Map;

/**
 * What the data type needs from HAPI FHIR. Implemented in the isolated engine (see
 * {@link FhirEngineLoader}); only JDK types cross this boundary.
 */
public interface FhirEngine {

    /** Option keys; all values are strings. */
    String FHIR_VERSION = "fhirVersion";
    /** Files and folders with profiles: StructureDefinition/ValueSet/CodeSystem files and NPM packages (.tgz), separated by ';'. */
    String PROFILES = "profiles";
    /** Canonical URL of a profile every resource must conform to, in addition to its meta.profile. */
    String REQUIRED_PROFILE = "requiredProfile";
    /** Severity for codes from code systems the validator does not know: error, warning or information. */
    String UNKNOWN_CODE_SYSTEMS = "unknownCodeSystems";
    /** "true" or "false": whether extensions without a known definition are accepted. */
    String ANY_EXTENSIONS_ALLOWED = "anyExtensionsAllowed";
    /** Base URL of a FHIR terminology server for codes the validator cannot check itself, or empty. */
    String TERMINOLOGY_SERVER = "terminologyServer";

    /** Issue keys. */
    String SEVERITY = "severity";
    String LOCATION = "location";
    String LINE = "line";
    String COLUMN = "column";
    String MESSAGE = "message";
    String MESSAGE_ID = "messageId";

    /**
     * Validates a resource (JSON or XML). Returns the issues, each a map with severity (fatal, error,
     * warning or information), location, line, column, message and messageId.
     */
    List<Map<String, String>> validate(String resource, Map<String, String> options) throws Exception;

    /** FHIR XML (with namespace) to FHIR JSON. Fails on elements that are not in the specification. */
    String toJson(String xml, String fhirVersion, boolean pretty) throws Exception;

    /** FHIR JSON to FHIR XML in the order of the specification. Fails on properties that are not in the specification. */
    String toXml(String json, String fhirVersion, boolean pretty) throws Exception;

    /** FHIR XML (with namespace) written again in the order of the specification. Fails on elements that are not in the specification. */
    String reorderXml(String xml, String fhirVersion, boolean pretty) throws Exception;

    /** FHIR JSON written again in the order of the specification. Fails on properties that are not in the specification. */
    String reorderJson(String json, String fhirVersion, boolean pretty) throws Exception;

    /**
     * The StructureDefinitions of resource profiles in the {@link #PROFILES} of these options, as a
     * JSON array of objects with url, name, title and type, sorted by url.
     */
    String listProfiles(Map<String, String> options) throws Exception;

    /**
     * A StructureDefinition (profile, resource or data type) as FHIR JSON, always with its snapshot:
     * one without is generated from its base. {@code url} is a canonical URL, or the name of a type of
     * the specification (Patient, HumanName). Looked up in the {@link #PROFILES} of these options and
     * the specification.
     */
    String structureDefinition(String url, Map<String, String> options) throws Exception;

    /**
     * The ConceptMaps in the {@link #PROFILES} of these options, as a JSON array of objects with url,
     * name, title, source and target, sorted by url.
     */
    String listConceptMaps(Map<String, String> options) throws Exception;

    /**
     * ConceptMap/$translate without a terminology server, on a ConceptMap of the {@link #PROFILES}.
     *
     * @param conceptMap canonical URL, optionally with |version
     * @param system code system of the code, or empty for any group
     * @param targetSystem code system to translate to, or empty for any group
     * @return the match as JSON with system, code and display, or null
     */
    String translate(String conceptMap, String system, String code, String targetSystem, Map<String, String> options) throws Exception;

    /** CodeSystem/$lookup without a terminology server: the display of a code, or null when the code is not known. */
    String lookup(String system, String code, Map<String, String> options) throws Exception;

    /** ValueSet/$validate-code without a terminology server: whether the code is in the value set. */
    boolean validateCode(String valueSet, String system, String code, Map<String, String> options) throws Exception;

    /** Builds the validator for these options ahead of the first message; loading the specification takes a few seconds. */
    void warmUp(Map<String, String> options);

    /**
     * Drops the validators whose options are not in {@code inUse} and that were not used for
     * {@code idleMillis}, and the specification of a FHIR version once no validator needs it.
     */
    void retainValidators(List<Map<String, String>> inUse, long idleMillis);
}
