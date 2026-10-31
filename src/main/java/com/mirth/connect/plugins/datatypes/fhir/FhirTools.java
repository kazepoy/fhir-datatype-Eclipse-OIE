/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FailOn;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;

/**
 * FHIR functions for channel scripts, for example to validate a response or build JSON in a
 * transformer:
 *
 * <pre>
 * var FhirTools = Packages.com.mirth.connect.plugins.datatypes.fhir.FhirTools;
 * var result = FhirTools.validate(responseJson);
 * if (!result.isValid()) logger.warn(result.summary());
 * </pre>
 *
 * Runs on the server only.
 */
public final class FhirTools {

    private FhirTools() {}

    /**
     * Validates a FHIR JSON or XML resource against the base specification and its meta.profile.
     * XML may be without the FHIR namespace, e.g. msg.toString() in a transformer.
     */
    public static FhirValidation validate(String resource) throws Exception {
        return validate(resource, "", "");
    }

    /** Validates against a profile, which must be known (see the next method for your own profiles). */
    public static FhirValidation validate(String resource, String requiredProfile) throws Exception {
        return validate(resource, requiredProfile, "");
    }

    /**
     * @param requiredProfile canonical URL of a profile the resource must conform to, or empty
     * @param profiles files and folders with profiles and packages, separated by ';', or empty
     */
    public static FhirValidation validate(String resource, String requiredProfile, String profiles) throws Exception {
        return validate(resource, requiredProfile, profiles, "R4");
    }

    /**
     * @param fhirVersion R4, R4B or R5; the profiles must be for the same version
     */
    public static FhirValidation validate(String resource, String requiredProfile, String profiles, String fhirVersion) throws Exception {
        FhirSerializationProperties properties = new FhirSerializationProperties();
        properties.setFhirVersion(version(fhirVersion));
        properties.setRequiredProfile(requiredProfile == null ? "" : requiredProfile);
        properties.setProfiles(profiles == null ? "" : profiles);
        String source = resource.trim();
        if (FhirXml.detect(source) == FhirXml.Format.XML) {
            source = FhirXml.addNamespace(source);
        }
        FhirEngine engine = FhirEngineLoader.engine();
        FhirValidation validation = new FhirValidation(engine.validate(source, properties.engineOptions()), FailOn.Errors);
        FhirValidatorUsage.startPeriodicRelease(engine);
        return validation;
    }

    /** FHIR R4 XML (with or without the FHIR namespace, e.g. msg.toString()) to FHIR JSON. */
    public static String toJson(String xml) throws Exception {
        return toJson(xml, "R4");
    }

    /** As {@link #toJson(String)}, for FHIR version R4, R4B or R5. */
    public static String toJson(String xml, String fhirVersion) throws Exception {
        FhirXml.detect(xml); // refuses FHIR XML with a DOCTYPE
        return FhirEngineLoader.engine().toJson(FhirXml.addNamespace(xml.trim()), version(fhirVersion).name(), true);
    }

    /** FHIR R4 JSON to FHIR XML, with the FHIR namespace. */
    public static String toXml(String json) throws Exception {
        return toXml(json, "R4");
    }

    /** As {@link #toXml(String)}, for FHIR version R4, R4B or R5. */
    public static String toXml(String json, String fhirVersion) throws Exception {
        FhirXml.detect(json); // refuses a JavaScript object passed as msg
        return FhirEngineLoader.engine().toXml(json, version(fhirVersion).name(), true);
    }

    /**
     * Translates a code with ConceptMap/$translate (FHIR R4): on a terminology server, or locally on
     * the ConceptMaps of the profile files and packages when the server is empty or "n/a".
     *
     * <pre>
     * var c = FhirTools.translate('n/a', '/profiles', 'http://hl7.org/fhir/uv/v2mappings/ConceptMap/table-hl70001-to-administrative-gender',
     *         'http://terminology.hl7.org/CodeSystem/v2-0001', 'M');
     * if (c != null) { var coding = JSON.parse(c); }  // {"system": ..., "code": ..., "display": ...}
     * </pre>
     *
     * @param server base URL of the terminology server, or empty or "n/a"
     * @param profiles files and folders with profiles and packages, separated by ';' (without a server)
     * @param system code system of the code, or empty
     * @return the first match as JSON with system, code and display, or null without a match
     */
    public static String translate(String server, String profiles, String conceptMap, String system, String code) throws Exception {
        return FhirTerminology.translate(server, profiles, conceptMap, system, code, "", "R4");
    }

    /** As {@link #translate(String, String, String, String, String)}, to a target code system, for FHIR version R4, R4B or R5. */
    public static String translate(String server, String profiles, String conceptMap, String system, String code, String targetSystem, String fhirVersion) throws Exception {
        return FhirTerminology.translate(server, profiles, conceptMap, system, code, targetSystem, version(fhirVersion).name());
    }

    /**
     * The display of a code with CodeSystem/$lookup, or null when the code is not known: on a
     * terminology server, or locally (server empty or "n/a") on the code systems of the
     * specification and the profiles. {@code FhirTools.lookup('n/a', '', 'http://loinc.org', '718-7')}
     */
    public static String lookup(String server, String profiles, String system, String code) throws Exception {
        return FhirTerminology.lookup(server, profiles, system, code, "R4", "");
    }

    /** As {@link #lookup(String, String, String, String)}, for R4, R4B or R5 and with an Authorization header (or ''). */
    public static String lookup(String server, String profiles, String system, String code, String fhirVersion, String authorization) throws Exception {
        return FhirTerminology.lookup(server, profiles, system, code, version(fhirVersion).name(), authorization);
    }

    /**
     * Whether a code is in a value set, with ValueSet/$validate-code: on a terminology server, or
     * locally (server empty or "n/a") on the value sets of the specification and the profiles.
     *
     * @param system code system of the code, or empty
     */
    public static boolean validateCode(String server, String profiles, String valueSet, String system, String code) throws Exception {
        return FhirTerminology.validateCode(server, profiles, valueSet, system, code, "R4", "");
    }

    /** As {@link #validateCode(String, String, String, String, String)}, for R4, R4B or R5 and with an Authorization header (or ''). */
    public static boolean validateCode(String server, String profiles, String valueSet, String system, String code, String fhirVersion, String authorization)
            throws Exception {
        return FhirTerminology.validateCode(server, profiles, valueSet, system, code, version(fhirVersion).name(), authorization);
    }

    private static FhirVersion version(String fhirVersion) {
        String name = fhirVersion == null ? "" : fhirVersion.trim().toUpperCase();
        if (name.isEmpty()) {
            return FhirVersion.R4;
        }
        try {
            return FhirVersion.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("FHIR version " + fhirVersion + " is not supported. Use R4, R4B or R5.");
        }
    }
}
