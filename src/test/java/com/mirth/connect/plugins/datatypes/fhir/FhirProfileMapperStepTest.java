package com.mirth.connect.plugins.datatypes.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.util.ScriptBuilderException;

/** The FHIR Profile Mapper step: its script run in Rhino as in a transformer, on HL7 v2 XML. */
public class FhirProfileMapperStepTest {

    /** PID of an ADT, as OIE's HL7 v2 data type gives it to the transformer. */
    private static final String HL7_XML = "<HL7Message><PID>"
            + "<PID.3><PID.3.1>123456</PID.3.1><PID.3.4><PID.3.4.1>EXAMPLE</PID.3.4.1></PID.3.4><PID.3.5>PI</PID.3.5></PID.3>"
            + "<PID.3><PID.3.1>1800101234567</PID.3.1><PID.3.5>INS-NIR</PID.3.5></PID.3>"
            + "<PID.5><PID.5.1><PID.5.1.1>DUPONT</PID.5.1.1></PID.5.1><PID.5.2>JEAN</PID.5.2><PID.5.3>PIERRE</PID.5.3></PID.5>"
            + "<PID.7><PID.7.1>19800115</PID.7.1></PID.7>"
            + "<PID.8>M</PID.8>"
            + "<PID.11><PID.11.3></PID.11.3></PID.11>"
            + "<PID.29><PID.29.1>202605031430</PID.29.1></PID.29>"
            + "</PID></HL7Message>";

    private static final String MAPPINGS = "["
            + "{\"element\":\"Patient.identifier:ipp.value\",\"type\":\"string\",\"expression\":\"msg['PID']['PID.3'][0]['PID.3.1'].toString()\",\"path\":[[\"identifier\",\"ipp#0\"],[\"value\"]]},"
            + "{\"element\":\"Patient.identifier:ins.value\",\"type\":\"string\",\"expression\":\"msg['PID']['PID.3'][1]['PID.3.1']\",\"path\":[[\"identifier\",\"ins#0\"],[\"value\"]]},"
            + "{\"element\":\"Patient.identifier:third.value\",\"type\":\"string\",\"expression\":\"msg['PID']['PID.3'][2]['PID.3.1'].toString()\",\"path\":[[\"identifier\",\"third#0\"],[\"value\"]]},"
            + "{\"element\":\"Patient.name.family\",\"type\":\"string\",\"expression\":\"msg['PID']['PID.5']['PID.5.1']['PID.5.1.1']\",\"path\":[[\"name\",\"#0\"],[\"family\"]]},"
            + "{\"element\":\"Patient.name.given\",\"type\":\"string\",\"expression\":\"msg['PID']['PID.5']['PID.5.2']\",\"path\":[[\"name\",\"#0\"],[\"given\",\"#0\"]]},"
            + "{\"element\":\"Patient.name.given[1]\",\"type\":\"string\",\"expression\":\"msg['PID']['PID.5']['PID.5.3']\",\"path\":[[\"name\",\"#0\"],[\"given\",\"#1\"]]},"
            + "{\"element\":\"Patient.gender\",\"type\":\"code\",\"expression\":\"({M: 'male', F: 'female'})[msg['PID']['PID.8'].toString()] || 'unknown'\",\"path\":[[\"gender\"]]},"
            + "{\"element\":\"Patient.birthDate\",\"type\":\"date\",\"expression\":\"msg['PID']['PID.7']['PID.7.1']\",\"path\":[[\"birthDate\"]]},"
            + "{\"element\":\"Patient.deceased[x]:deceasedDateTime\",\"type\":\"dateTime\",\"expression\":\"msg['PID']['PID.29']['PID.29.1']\",\"path\":[[\"deceasedDateTime\"]]},"
            + "{\"element\":\"Patient.address.city\",\"type\":\"string\",\"expression\":\"msg['PID']['PID.11']['PID.11.3']\",\"path\":[[\"address\",\"#0\"],[\"city\"]]},"
            + "{\"element\":\"Patient.extension:birthPlace.value[x]:valueString\",\"type\":\"string\",\"expression\":\"'Marseille'\",\"path\":[[\"extension\",\"birthPlace#0\"],[\"valueString\"]]},"
            + "{\"element\":\"Patient.identifier:ipp.system\",\"fixed\":\"urn:oid:1.2.250.1.71.4.2.7\",\"when\":1,\"path\":[[\"identifier\",\"ipp#0\"],[\"system\"]]},"
            + "{\"element\":\"Patient.identifier:ins\",\"fixed\":{\"system\":\"urn:oid:1.2.250.1.213.1.4.8\",\"type\":{\"coding\":[{\"code\":\"INS-NIR\"}]}},\"when\":1,\"path\":[[\"identifier\",\"ins#0\"]]},"
            + "{\"element\":\"Patient.identifier:third.system\",\"fixed\":\"urn:never\",\"when\":1,\"path\":[[\"identifier\",\"third#0\"],[\"system\"]]},"
            + "{\"element\":\"Patient.address.country\",\"fixed\":\"FR\",\"when\":1,\"path\":[[\"address\",\"#0\"],[\"country\"]]},"
            + "{\"element\":\"Patient.extension:birthPlace.url\",\"fixed\":\"http://hl7.org/fhir/StructureDefinition/patient-birthPlace\",\"when\":1,\"path\":[[\"extension\",\"birthPlace#0\"],[\"url\"]]}"
            + "]";

    private static TimeZone defaultZone;

    @BeforeClass
    public static void setUp() {
        System.setProperty("fhir.engine.direct", "true");
        defaultZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
    }

    @AfterClass
    public static void tearDown() {
        TimeZone.setDefault(defaultZone);
        if (terminology != null) {
            terminology.stop(0);
        }
    }

    // ------------------------------------------------------------------ a terminology server

    private static HttpServer terminology;
    private static final AtomicInteger translateCalls = new AtomicInteger();
    private static final String SEX_MAP = "http://example.org/ConceptMap/v2-sex";
    private static final String V2_SEX = "http://terminology.hl7.org/CodeSystem/v2-0001";

    /** ConceptMap/$translate of SEX_MAP: M and F are mapped, U is unmatched, anything else has no result. */
    private static String terminologyServer() throws IOException {
        if (terminology == null) {
            terminology = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            terminology.createContext("/fhir/ConceptMap/$translate", exchange -> {
                translateCalls.incrementAndGet();
                Map<String, String> q = new HashMap<>();
                for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                    String[] kv = pair.split("=", 2);
                    q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
                String code = q.containsKey("sourceCode") ? q.get("sourceCode") : q.get("code");
                String body;
                int status = 200;
                if (!SEX_MAP.equals(q.get("url"))) {
                    status = 404;
                    body = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"diagnostics\":\"Unknown ConceptMap\"}]}";
                } else if ("M".equals(code) || "F".equals(code)) {
                    String target = "M".equals(code) ? "male" : "female";
                    String equivalence = q.containsKey("sourceCode") ? "{\"name\":\"relationship\",\"valueCode\":\"equivalent\"}" : "{\"name\":\"equivalence\",\"valueCode\":\"equal\"}";
                    body = "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":true},{\"name\":\"match\",\"part\":["
                            + equivalence + ",{\"name\":\"concept\",\"valueCoding\":{\"system\":\"http://hl7.org/fhir/administrative-gender\",\"code\":\"" + target
                            + "\",\"display\":\"" + target + "\"}}]}]}";
                } else if ("U".equals(code)) {
                    body = "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":true},{\"name\":\"match\",\"part\":["
                            + "{\"name\":\"equivalence\",\"valueCode\":\"unmatched\"},{\"name\":\"concept\",\"valueCoding\":{\"code\":\"unknown\"}}]}]}";
                } else {
                    body = "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":false}]}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            terminology.start();
        }
        FhirTerminology.clearCache();
        translateCalls.set(0);
        return "http://127.0.0.1:" + terminology.getAddress().getPort() + "/fhir/";
    }

    private static FhirProfileMapperStep patient(String mappings) {
        FhirProfileMapperStep step = new FhirProfileMapperStep();
        step.setName("Patient");
        step.setResourceType("Patient");
        step.setAddMetaProfile(false);
        step.setMappings(mappings);
        return step;
    }

    private static Map<?, ?> resource(FhirProfileMapperStep step) throws Exception {
        return (Map<?, ?>) FhirJson.parse((String) run(step, null, new HashMap<>()));
    }

    // ------------------------------------------------------------------ iterations

    @Test
    public void anIterationMakesAnEntryPerRepetition() throws Exception {
        Map<?, ?> r = resource(patient("["
                + "{\"key\":\"Patient.identifier\",\"each\":\"msg['PID']['PID.3']\",\"variable\":\"id\",\"path\":[[\"identifier\",\"#0\"]]},"
                + "{\"key\":\"Patient.identifier.value\",\"type\":\"string\",\"expression\":\"id['PID.3.1']\",\"path\":[[\"identifier\",\"#0\"],[\"value\"]]},"
                + "{\"key\":\"Patient.identifier.type.text\",\"type\":\"string\",\"expression\":\"id['PID.3.5'] + '/' + idIndex\",\"path\":[[\"identifier\",\"#0\"],[\"type\"],[\"text\"]]},"
                + "{\"key\":\"Patient.identifier.use\",\"fixed\":\"usual\",\"when\":1,\"path\":[[\"identifier\",\"#0\"],[\"use\"]]},"
                + "{\"key\":\"Patient.identifier[1].value\",\"type\":\"string\",\"expression\":\"'manual'\",\"path\":[[\"identifier\",\"#1\"],[\"value\"]]}"
                + "]"));
        assertEquals("[{value=123456, type={text=PI/0}, use=usual}, {value=1800101234567, type={text=INS-NIR/1}, use=usual}, {value=manual}]",
                r.get("identifier").toString());
    }

    @Test
    public void iterationsNest() throws Exception {
        Map<?, ?> r = resource(patient("["
                + "{\"key\":\"Patient.name\",\"each\":\"[['Jean', 'Pierre'], ['Paul']]\",\"variable\":\"n\",\"path\":[[\"name\",\"#0\"]]},"
                + "{\"key\":\"Patient.name.given\",\"each\":\"n\",\"variable\":\"g\",\"path\":[[\"name\",\"#0\"],[\"given\",\"#0\"]]},"
                + "{\"key\":\"Patient.name.given\",\"type\":\"string\",\"expression\":\"g + gIndex + nIndex\",\"path\":[[\"name\",\"#0\"],[\"given\",\"#0\"]]},"
                + "{\"key\":\"Patient.name.family\",\"type\":\"string\",\"expression\":\"msg['PID']['PID.5']['PID.5.1']['PID.5.1.1']\",\"path\":[[\"name\",\"#0\"],[\"family\"]]}"
                + "]"));
        assertEquals("[{given=[Jean00, Pierre10], family=DUPONT}, {given=[Paul01], family=DUPONT}]", r.get("name").toString());
    }

    @Test
    public void anIterationOverNothingAddsNothing() throws Exception {
        Map<?, ?> r = resource(patient("["
                + "{\"key\":\"Patient.telecom\",\"each\":\"msg['PID']['PID.13']\",\"variable\":\"t\",\"path\":[[\"telecom\",\"#0\"]]},"
                + "{\"key\":\"Patient.telecom.value\",\"type\":\"string\",\"expression\":\"t['PID.13.1']\",\"path\":[[\"telecom\",\"#0\"],[\"value\"]]},"
                + "{\"key\":\"Patient.telecom.system\",\"fixed\":\"phone\",\"when\":1,\"path\":[[\"telecom\",\"#0\"],[\"system\"]]},"
                + "{\"key\":\"Patient.contact\",\"each\":\"msg['NK1'][5]['NK1.2']\",\"variable\":\"c\",\"path\":[[\"contact\",\"#0\"]]},"
                + "{\"key\":\"Patient.contact.name.text\",\"type\":\"string\",\"expression\":\"c.toString()\",\"path\":[[\"contact\",\"#0\"],[\"name\"],[\"text\"]]},"
                + "{\"key\":\"Patient.gender\",\"type\":\"code\",\"expression\":\"'male'\",\"path\":[[\"gender\"]]}"
                + "]"));
        assertEquals("{resourceType=Patient, gender=male}", r.toString());
    }

    @Test
    public void theVariableOfAnIterationMustBeAName() {
        for (String var : new String[] { "msg", "1x", "var", "__r", "" }) {
            try {
                patient("[{\"key\":\"Patient.name\",\"each\":\"msg['PID']['PID.5']\",\"variable\":\"" + var + "\",\"path\":[[\"name\",\"#0\"]]}]").getScript(false);
                fail("expected the variable to be refused: " + var);
            } catch (ScriptBuilderException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("must be a JavaScript name"));
            }
        }
    }

    // ------------------------------------------------------------------ ConceptMap/$translate

    private static String gender(String type, String expression, String unmapped) {
        return gender(type, expression, unmapped, "", SEX_MAP, V2_SEX);
    }

    /** One translated element: gender (code), maritalStatus.coding (Coding) or maritalStatus (CodeableConcept). */
    private static String gender(String type, String expression, String unmapped, String value, String conceptMap, String source) {
        String path = "Coding".equals(type) ? "[[\"maritalStatus\"],[\"coding\",\"#0\"]]" : "CodeableConcept".equals(type) ? "[[\"maritalStatus\"]]" : "[[\"gender\"]]";
        return "[{\"key\":\"Patient.x\",\"type\":\"" + type + "\",\"expression\":\"" + expression + "\",\"path\":" + path
                + ",\"translate\":{\"conceptMap\":\"" + conceptMap + "\",\"source\":\"" + source + "\",\"unmapped\":\"" + unmapped
                + "\",\"value\":\"" + value + "\"}}]";
    }

    @Test
    public void aCodeIsTranslatedWithTheConceptMap() throws Exception {
        String server = terminologyServer();
        FhirProfileMapperStep step = patient(gender("code", "msg['PID']['PID.8']", "none"));
        step.setTerminologyServer(server);
        assertEquals("male", resource(step).get("gender"));
        assertEquals("male", resource(step).get("gender"));
        assertEquals("the second message uses the cache", 1, translateCalls.get());
    }

    @Test
    public void aCodingOrCodeableConceptGetsTheTargetCoding() throws Exception {
        String server = terminologyServer();
        FhirProfileMapperStep step = patient(gender("Coding", "'F'", "none"));
        step.setTerminologyServer(server);
        assertEquals("{coding=[{system=http://hl7.org/fhir/administrative-gender, code=female, display=female}]}", resource(step).get("maritalStatus").toString());
        step.setMappings(gender("CodeableConcept", "'M'", "none"));
        assertEquals("{coding=[{system=http://hl7.org/fhir/administrative-gender, code=male, display=male}]}", resource(step).get("maritalStatus").toString());
    }

    @Test
    public void anUntranslatedCodeIsLeftOutKeptOrAnError() throws Exception {
        String server = terminologyServer();
        FhirProfileMapperStep step = patient(gender("code", "'U'", "none"));
        step.setTerminologyServer(server);
        assertFalse("unmatched is no translation", resource(step).containsKey("gender"));
        step.setMappings(gender("Coding", "'X'", "source"));
        assertEquals("{coding=[{system=" + V2_SEX + ", code=X}]}", resource(step).get("maritalStatus").toString());
        step.setMappings(gender("code", "'X'", "error"));
        try {
            resource(step);
            fail("expected an error");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Patient.x: code \"X\" of " + V2_SEX + " is not in ConceptMap " + SEX_MAP));
        }
    }

    @Test
    public void aServerErrorStopsTheMessage() throws Exception {
        String server = terminologyServer();
        FhirProfileMapperStep step = patient(gender("code", "'M'", "none").replace(SEX_MAP, "http://example.org/ConceptMap/other"));
        step.setTerminologyServer(server);
        try {
            resource(step);
            fail("expected an error");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("answered HTTP 404"));
            assertTrue(e.getMessage(), e.getMessage().contains("Unknown ConceptMap"));
        }
    }

    @Test
    public void r5UsesItsParameterNames() throws Exception {
        String server = terminologyServer();
        assertEquals("female", ((Map<?, ?>) FhirJson.parse(FhirTerminology.translate(server, "", SEX_MAP, V2_SEX, "F", "", "R5"))).get("code"));
    }

    // ------------------------------------------------------------------ local $translate (n/a)

    private static final String LOCAL_MAPS = "src/test/resources/conceptmaps";

    private static FhirProfileMapperStep local(String mappings, String server) {
        FhirProfileMapperStep step = patient(mappings);
        step.setTerminologyServer(server);
        step.setProfiles(LOCAL_MAPS);
        return step;
    }

    @Test
    public void withoutAServerTheConceptMapsOfTheProfilesTranslate() throws Exception {
        for (String server : new String[] { "", "n/a", "N/A" }) {
            assertEquals("female", resource(local(gender("code", "'F'", "none"), server)).get("gender"));
        }
        // wider is a match; unmatched is none
        assertEquals("{coding=[{system=http://hl7.org/fhir/administrative-gender, code=other, display=Other}]}",
                resource(local(gender("CodeableConcept", "'A'", "none"), "n/a")).get("maritalStatus").toString());
        assertFalse(resource(local(gender("code", "'U'", "none"), "n/a")).containsKey("gender"));
        // the ConceptMap by url|version, any group without a source system
        assertEquals("male", resource(local(gender("code", "'M'", "none", "", SEX_MAP + "|1.0.0", ""), "n/a")).get("gender"));
        // a source system no group has
        assertFalse(resource(local(gender("code", "'M'", "none", "", SEX_MAP, "http://other"), "n/a")).containsKey("gender"));
    }

    @Test
    public void localUnmappedRulesAreApplied() throws Exception {
        String map = "http://example.org/ConceptMap/local-sex";
        String system = "http://example.org/CodeSystem/local-sex";
        // XML ConceptMap; disjoint is no match, so other-map: the R5 fallback map (relationship)
        assertEquals("male", resource(local(gender("code", "'1'", "none", "", map, system), "n/a")).get("gender"));
        assertEquals("female", resource(local(gender("code", "'2'", "none", "", map, system), "n/a")).get("gender"));
        // not-related-to is no match; then the fixed unmapped code of the fallback map
        assertEquals("unknown", resource(local(gender("code", "'9'", "none", "", map, system), "n/a")).get("gender"));
    }

    @Test
    public void noMatchCanGiveAFixedValueOrAnExpression() throws Exception {
        String server = terminologyServer();
        FhirProfileMapperStep step = patient(gender("Coding", "'X'", "fixed", "http://hl7.org/fhir/administrative-gender|unknown", SEX_MAP, V2_SEX));
        step.setTerminologyServer(server);
        assertEquals("{coding=[{system=http://hl7.org/fhir/administrative-gender, code=unknown}]}", resource(step).get("maritalStatus").toString());
        step.setMappings(gender("code", "'X'", "fixed", "unknown", SEX_MAP, V2_SEX));
        assertEquals("unknown", resource(step).get("gender"));
        // an expression of code and system, e.g. a code template function
        step.setMappings(gender("code", "'X'", "function", "code === 'X' && system === '" + V2_SEX + "' ? 'other' : null", SEX_MAP, V2_SEX));
        assertEquals("other", resource(step).get("gender"));
        step.setMappings(gender("code", "'Y'", "function", "code === 'X' ? 'other' : null", SEX_MAP, V2_SEX));
        assertFalse("null from the expression: no value", resource(step).containsKey("gender"));
        step.setMappings(gender("Coding", "'Y'", "function", "({system: 'urn:local', code: code.toLowerCase()})", SEX_MAP, V2_SEX));
        assertEquals("{coding=[{system=urn:local, code=y}]}", resource(step).get("maritalStatus").toString());
    }

    @Test
    public void aFixedValueOrExpressionMustBeGiven() {
        for (String unmapped : new String[] { "fixed", "function" }) {
            try {
                patient(gender("code", "'M'", unmapped)).getScript(false);
                fail("expected an error: " + unmapped);
            } catch (ScriptBuilderException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("for codes without a match"));
            }
        }
    }

    @Test
    public void aLocalConceptMapThatIsNotThereStopsTheMessage() throws Exception {
        try {
            resource(local(gender("code", "'M'", "none", "", "http://example.org/ConceptMap/none", V2_SEX), "n/a"));
            fail("expected an error");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ConceptMap http://example.org/ConceptMap/none is not in the profile packages"));
        }
    }

    @Test
    public void theLocalConceptMapsAreListed() throws Exception {
        String list = FhirTerminology.listConceptMaps("n/a", LOCAL_MAPS, "R4");
        assertTrue(list, list.contains("\"url\":\"http://example.org/ConceptMap/local-sex\""));
        assertTrue(list, list.contains("\"title\":\"HL7 v2 table 0001 to administrative-gender (R4)\""));
        assertTrue(list, list.contains("\"source\":\"http://terminology.hl7.org/ValueSet/v2-0001\""));
    }

    private static FhirProfileMapperStep step() {
        FhirProfileMapperStep step = new FhirProfileMapperStep();
        step.setName("Patient");
        step.setResourceType("Patient");
        step.setProfileUrl("http://example.org/StructureDefinition/example-patient");
        step.setMappings(MAPPINGS);
        return step;
    }

    /**
     * Runs the step as a transformer would; template is the outbound template (tmp) or null. Returns
     * what the engine takes as the outbound message: tmp with a template, msg without.
     */
    private static Object run(FhirProfileMapperStep step, String templateXml, Map<String, Object> channelMap) throws Exception {
        Context cx = new ContextFactory().enterContext();
        try {
            Scriptable scope = cx.initStandardObjects();
            scope.put("hl7", scope, HL7_XML);
            scope.put("templateXml", scope, templateXml);
            scope.put("channelMap", scope, Context.javaToJS(channelMap, scope));
            String script = "var msg = new XML(hl7);\n"
                    + (templateXml != null ? "var tmp = new XML(templateXml);\n" : "")
                    + step.getScript(false) + "\n"
                    + "var out = " + (templateXml != null ? "tmp" : "msg") + ";\n"
                    + "if ('xml' === typeof out) { out = out.toXMLString(); } else if ('undefined' !== typeof out && out !== null) {\n"
                    + "  var t = Object.prototype.toString.call(out); if (t == '[object Object]' || t == '[object Array]') { out = JSON.stringify(out); } }\n"
                    + "out;";
            Object result = cx.evaluateString(scope, script, "transformer", 1, null);
            return result == null ? null : Context.toString(result);
        } finally {
            Context.exit();
        }
    }

    @Test
    public void buildsTheResourceFromTheMessage() throws Exception {
        String json = (String) run(step(), null, new HashMap<>());
        Map<?, ?> r = (Map<?, ?>) FhirJson.parse(json);
        assertEquals("Patient", r.get("resourceType"));
        assertEquals("{profile=[http://example.org/StructureDefinition/example-patient]}", r.get("meta").toString());
        assertEquals("[{value=123456, system=urn:oid:1.2.250.1.71.4.2.7}, "
                + "{value=1800101234567, system=urn:oid:1.2.250.1.213.1.4.8, type={coding=[{code=INS-NIR}]}}]", r.get("identifier").toString());
        assertEquals("[{family=DUPONT, given=[JEAN, PIERRE]}]", r.get("name").toString());
        assertEquals("male", r.get("gender"));
        assertEquals("1980-01-15", r.get("birthDate"));
        // HL7 v2 TS without a zone: the server's offset (Europe/Paris, summer time).
        assertEquals("2026-05-03T14:30:00+02:00", r.get("deceasedDateTime"));
        assertEquals("[{valueString=Marseille, url=http://hl7.org/fhir/StructureDefinition/patient-birthPlace}]", r.get("extension").toString());
        // Empty source fields: no element, and no fixed value of their entry either.
        assertFalse(json, r.containsKey("address"));
        assertFalse(json, json.contains("urn:never"));
    }

    @Test
    public void theResultIsValidFhir() throws Exception {
        String json = (String) run(step(), null, new HashMap<>());
        String out = FhirTools.toXml(json);
        assertTrue(out, out.contains("<family value=\"DUPONT\"/>"));
    }

    @Test
    public void anXmlTemplateGetsFhirXml() throws Exception {
        String xml = (String) run(step(), "<Patient/>", new HashMap<>());
        assertTrue(xml, xml.startsWith("<Patient>"));
        assertTrue(xml, xml.contains("<birthDate value=\"1980-01-15\"/>"));
        String withNamespace = (String) run(step(), "<Patient xmlns=\"http://hl7.org/fhir\"/>", new HashMap<>());
        assertTrue(withNamespace, withNamespace.startsWith("<Patient xmlns=\"http://hl7.org/fhir\">"));
    }

    @Test
    public void aVariableGetsTheJson() throws Exception {
        FhirProfileMapperStep step = step();
        step.setVariable("patient");
        Map<String, Object> channelMap = new HashMap<>();
        // msg stays the inbound message
        assertTrue(String.valueOf(run(step, null, channelMap)).startsWith("<HL7Message>"));
        assertTrue(String.valueOf(channelMap.get("patient")), String.valueOf(channelMap.get("patient")).contains("\"family\":\"DUPONT\""));
    }

    @Test
    public void aWrongValueNamesTheElement() throws Exception {
        FhirProfileMapperStep step = step();
        step.setMappings("[{\"element\":\"Patient.multipleBirth[x]:multipleBirthInteger\",\"type\":\"integer\",\"expression\":\"'twee'\",\"path\":[[\"multipleBirthInteger\"]]}]");
        try {
            run(step, null, new HashMap<>());
            fail("expected an error");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Patient.multipleBirth[x]:multipleBirthInteger: \"twee\" is not an integer."));
        }
    }

    @Test
    public void aStepWithoutAProfileIsRefused() {
        FhirProfileMapperStep step = new FhirProfileMapperStep();
        step.setName("empty");
        try {
            step.getScript(false);
            fail("expected an error");
        } catch (ScriptBuilderException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no profile has been loaded"));
        }
    }

    @Test
    public void cloneKeepsTheProperties() {
        FhirProfileMapperStep step = step();
        step.setVariable("v");
        FhirProfileMapperStep copy = (FhirProfileMapperStep) step.clone();
        assertEquals(step.getMappings(), copy.getMappings());
        assertEquals("v", copy.getVariable());
        assertEquals("Patient", copy.getName());
    }

    // ------------------------------------------------------------------ profile lookups (engine)

    @Test
    public void theEngineGivesTheSnapshotOfATypeOrProfile() throws Exception {
        FhirEngine engine = FhirEngineLoader.engine();
        FhirSerializationProperties p = new FhirSerializationProperties();
        String patient = engine.structureDefinition("Patient", p.engineOptions());
        assertTrue(patient.contains("\"id\":\"Patient.name\""));

        p.setProfiles("src/test/resources/profiles");
        String list = engine.listProfiles(p.engineOptions());
        assertTrue(list, list.contains("\"type\":\"Patient\""));
        String url = list.replaceAll("(?s).*?\"url\":\"([^\"]+)\".*", "$1");
        String profile = engine.structureDefinition(url, p.engineOptions());
        assertTrue(profile, profile.contains("\"snapshot\""));
        assertTrue(profile, profile.contains("\"id\":\"Patient.birthDate\""));
        // base.max as in the specification: the mapper decides from it which elements are arrays
        assertTrue(profile, profile.matches("(?s).*\"id\":\"Patient.birthDate\".*?\"base\":\\{\"path\":\"Patient.birthDate\",\"min\":0,\"max\":\"1\"\\}.*"));
        assertTrue(profile, profile.matches("(?s).*\"id\":\"Patient.name\".*?\"base\":\\{\"path\":\"Patient.name\",\"min\":0,\"max\":\"\\*\"\\}.*"));
    }
}
