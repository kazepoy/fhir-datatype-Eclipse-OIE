/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.plugins.datatypes.fhir.FhirEngine;
import com.mirth.connect.plugins.datatypes.fhir.FhirEngineLoader;
import com.mirth.connect.plugins.datatypes.fhir.FhirProfileServletInterface;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;
import com.mirth.connect.plugins.datatypes.fhir.FhirTerminology;
import com.mirth.connect.plugins.datatypes.fhir.FhirValidatorUsage;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateProperties.CodeTemplateType;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.userutil.SerializerFactory;
import com.mirth.connect.util.ConfigurationProperty;

/**
 * The lookups of the FHIR Profile Mapper editor. The profile lookups use the validator of the same
 * options as a channel with these packages and otherwise default properties, so a deployed channel
 * and the editor share the loaded specification and packages. The terminology calls are made from
 * the OIE server, which reaches servers (Docker network names) the browser cannot.
 */
public class FhirProfileServlet extends MirthServlet implements FhirProfileServletInterface {

    public FhirProfileServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc);
    }

    @Override
    public String getProfiles(String fhirVersion, String profiles) throws ClientException {
        try {
            FhirEngine engine = FhirEngineLoader.engine();
            String json = engine.listProfiles(options(fhirVersion, profiles));
            FhirValidatorUsage.startPeriodicRelease(engine);
            return json;
        } catch (Exception e) {
            throw new ClientException(e.getMessage(), e);
        }
    }

    @Override
    public String getStructure(String url, String fhirVersion, String profiles) throws ClientException {
        if (url == null || url.trim().isEmpty()) {
            throw new ClientException("The url of the StructureDefinition is missing.");
        }
        try {
            FhirEngine engine = FhirEngineLoader.engine();
            String json = engine.structureDefinition(url, options(fhirVersion, profiles));
            FhirValidatorUsage.startPeriodicRelease(engine);
            return json;
        } catch (Exception e) {
            throw new ClientException(e.getMessage(), e);
        }
    }

    @Override
    public String getConceptMaps(String server, String profiles, String fhirVersion) throws ClientException {
        try {
            return FhirTerminology.listConceptMaps(server, profiles, fhirVersion);
        } catch (Exception e) {
            throw new ClientException(e.getMessage(), e);
        }
    }

    @Override
    public String translate(String server, String profiles, String conceptMap, String system, String code, String targetSystem, String fhirVersion) throws ClientException {
        try {
            String match = FhirTerminology.translate(server, profiles, conceptMap, system, code, targetSystem, fhirVersion);
            return match == null ? "null" : match;
        } catch (Exception e) {
            throw new ClientException(e.getMessage(), e);
        }
    }

    @Override
    public String getScript(String step) throws ClientException {
        return FhirMapperTester.script(step);
    }

    @Override
    public String test(String request) throws ClientException {
        return FhirMapperTester.test(request, SERVER);
    }

    /** The code templates (functions) and configuration map of this server, and its serializers. */
    private static final FhirMapperTester.Environment SERVER = new FhirMapperTester.Environment() {
        @Override
        public List<String> functions() {
            List<String> code = new ArrayList<>();
            try {
                for (CodeTemplate template : ControllerFactory.getFactory().createCodeTemplateController().getCodeTemplates(null)) {
                    if (template.getType() == CodeTemplateType.FUNCTION || template.getType() == CodeTemplateType.COMPILED_CODE) {
                        code.add(template.getCode());
                    }
                }
            } catch (Exception e) {
                // without code templates
            }
            return code;
        }

        @Override
        public Map<String, String> configuration() {
            Map<String, String> map = new HashMap<>();
            try {
                for (Map.Entry<String, ConfigurationProperty> e : ControllerFactory.getFactory().createConfigurationController().getConfigurationProperties().entrySet()) {
                    map.put(e.getKey(), e.getValue().getValue());
                }
            } catch (Exception e) {
                // without the configuration map
            }
            return map;
        }

        @Override
        public String serialize(String dataType, String message) throws Exception {
            switch (dataType) {
                case "JSON":
                case "RAW":
                case "XML":
                    return message;
                default:
                    // HL7 v2 (segments separated by CR, also when pasted with LF) and the other data types
                    String raw = "HL7V2".equals(dataType) ? message.replace("\r\n", "\r").replace('\n', '\r') : message;
                    return SerializerFactory.getSerializer(dataType).toXML(raw);
            }
        }
    };

    static Map<String, String> options(String fhirVersion, String profiles) {
        FhirSerializationProperties properties = new FhirSerializationProperties();
        String version = fhirVersion == null ? "" : fhirVersion.trim().toUpperCase();
        try {
            properties.setFhirVersion(version.isEmpty() ? FhirVersion.R4 : FhirVersion.valueOf(version));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("FHIR version " + fhirVersion + " is not supported. Use R4, R4B or R5.");
        }
        properties.setProfiles(profiles == null ? "" : profiles.trim());
        return properties.engineOptions();
    }
}
