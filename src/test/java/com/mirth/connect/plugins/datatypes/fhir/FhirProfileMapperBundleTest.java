package com.mirth.connect.plugins.datatypes.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

import com.mirth.connect.util.ScriptBuilderException;
import com.sun.net.httpserver.HttpServer;

/** The FHIR Profile Mapper: conditions, Bundles of several steps, and the other step options. */
public class FhirProfileMapperBundleTest {

    /** An ORU as OIE's HL7 v2 data type gives it: the segments of the groups are siblings. */
    private static final String ORU_XML = "<HL7Message>"
            + "<MSH><MSH.10><MSH.10.1>MSG1</MSH.10.1></MSH.10></MSH>"
            + "<PID><PID.3><PID.3.1>IPP1</PID.3.1><PID.3.5>PI</PID.3.5></PID.3><PID.3><PID.3.1>NIR1</PID.3.1><PID.3.5>NH</PID.3.5></PID.3>"
            + "<PID.5><PID.5.1>DUPONT</PID.5.1></PID.5><PID.8><PID.8.1>F</PID.8.1></PID.8></PID>"
            + "<ORC><ORC.2><ORC.2.1>ORD1</ORC.2.1></ORC.2></ORC>"
            + "<OBR><OBR.4><OBR.4.1>PANEL1</OBR.4.1></OBR.4></OBR>"
            + "<OBX><OBX.3><OBX.3.1>GLU</OBX.3.1></OBX.3><OBX.5><OBX.5.1>5.40</OBX.5.1></OBX.5></OBX>"
            + "<OBX><OBX.3><OBX.3.1>NA</OBX.3.1></OBX.3><OBX.5><OBX.5.1>140</OBX.5.1></OBX.5></OBX>"
            + "<ORC><ORC.2><ORC.2.1>ORD2</ORC.2.1></ORC.2></ORC>"
            + "<OBR><OBR.4><OBR.4.1>PANEL2</OBR.4.1></OBR.4></OBR>"
            + "<OBX><OBX.3><OBX.3.1>K</OBX.3.1></OBX.3><OBX.5><OBX.5.1>4.1</OBX.5.1></OBX.5></OBX>"
            + "</HL7Message>";

    private static HttpServer server;
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final AtomicInteger calls = new AtomicInteger();

    @BeforeClass
    public static void setUp() throws IOException {
        System.setProperty("fhir.engine.direct", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fhir/ConceptMap/$translate", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":true},{\"name\":\"match\",\"part\":["
                    + "{\"name\":\"equivalence\",\"valueCode\":\"equivalent\"},{\"name\":\"concept\",\"valueCoding\":{\"system\":\"http://loinc.org\",\"code\":\"2345-7\"}}]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterClass
    public static void tearDown() {
        server.stop(0);
    }

    private static FhirProfileMapperStep step(String type, String mappings) {
        FhirProfileMapperStep step = new FhirProfileMapperStep();
        step.setName(type);
        step.setResourceType(type);
        step.setAddMetaProfile(false);
        step.setMappings(mappings);
        return step;
    }

    private static FhirProfileMapperStep bundled(String type, String refName, String mappings) {
        FhirProfileMapperStep step = step(type, mappings);
        step.setOutput("bundle");
        step.setRefName(refName);
        return step;
    }

    /** Runs the steps one after the other in one transformer scope; returns msg as the engine would send it. */
    private static String run(FhirProfileMapperStep... steps) throws Exception {
        Context cx = new ContextFactory().enterContext();
        try {
            Scriptable scope = cx.initStandardObjects();
            scope.put("hl7", scope, ORU_XML);
            StringBuilder script = new StringBuilder("var msg = new XML(hl7);\n");
            for (FhirProfileMapperStep step : steps) {
                script.append(step.getScript(false)).append('\n');
            }
            script.append("var t = Object.prototype.toString.call(msg); typeof msg === 'xml' ? msg.toXMLString() : (t == '[object Object]' ? JSON.stringify(msg) : msg);");
            return Context.toString(cx.evaluateString(scope, script.toString(), "transformer", 1, null));
        } finally {
            Context.exit();
        }
    }

    private static Map<?, ?> parse(String json) {
        return (Map<?, ?>) FhirJson.parse(json);
    }

    private static String entry(String key, String type, String expression, String path) {
        return "{\"key\":\"" + key + "\",\"type\":\"" + type + "\",\"expression\":\"" + expression + "\",\"path\":" + path + "}";
    }

    // ------------------------------------------------------------------ conditions

    @Test
    public void aConditionFiltersTheItemsOfAnIteration() throws Exception {
        String json = run(step("Patient", "["
                + "{\"key\":\"Patient.identifier\",\"each\":\"msg['PID']['PID.3']\",\"variable\":\"id\",\"path\":[[\"identifier\",\"#0\"]]},"
                + "{\"key\":\"Patient.identifier\",\"condition\":\"id['PID.3.5'].toString() == 'PI'\",\"path\":[[\"identifier\",\"#0\"]]},"
                + entry("Patient.identifier.value", "string", "id['PID.3.1'].toString()", "[[\"identifier\",\"#0\"],[\"value\"]]") + ","
                + "{\"key\":\"Patient.identifier.system\",\"fixed\":\"urn:ipp\",\"when\":1,\"path\":[[\"identifier\",\"#0\"],[\"system\"]]}"
                + "]"));
        assertEquals("[{value=IPP1, system=urn:ipp}]", parse(json).get("identifier").toString());
    }

    @Test
    public void aConditionOnAnElementGatesWhatIsBelowIt() throws Exception {
        String mappings = "["
                + "{\"key\":\"Patient.name\",\"condition\":\"msg['PID']['PID.8']['PID.8.1'].toString() == 'X'\",\"path\":[[\"name\",\"#0\"]]},"
                + entry("Patient.name.family", "string", "msg['PID']['PID.5']['PID.5.1'].toString()", "[[\"name\",\"#0\"],[\"family\"]]") + ","
                + entry("Patient.gender", "code", "'female'", "[[\"gender\"]]") + "]";
        Map<?, ?> r = parse(run(step("Patient", mappings)));
        assertFalse(r.containsKey("name"));
        assertEquals("female", r.get("gender"));
        // a TypeError in a condition is false
        assertFalse(parse(run(step("Patient", mappings.replace("msg['PID']['PID.8']['PID.8.1'].toString() == 'X'", "msg['NK1'][3]['NK1.2'] == 'X'")))).containsKey("name"));
    }

    @Test
    public void aResourceConditionThatFailsLeavesTheMessage() throws Exception {
        String out = run(step("Patient", "[{\"key\":\"Patient\",\"condition\":\"false\",\"path\":[]}," + entry("Patient.gender", "code", "'female'", "[[\"gender\"]]") + "]"));
        assertTrue(out, out.startsWith("<HL7Message>"));
    }

    // ------------------------------------------------------------------ Bundle

    @Test
    public void stepsShareABundleAndReferToEachOther() throws Exception {
        FhirProfileMapperStep patient = bundled("Patient", "'patient'", "[" + entry("Patient.gender", "code", "'female'", "[[\"gender\"]]") + "]");
        patient.setBundleProfile("http://example.org/StructureDefinition/oru-bundle");
        patient.setIfNoneExist("'identifier=urn:ipp|' + msg['PID']['PID.3'][0]['PID.3.1']");
        // one Observation per OBX, named by the OBR they belong to
        FhirProfileMapperStep observation = bundled("Observation", "'obs-' + hl7Ordinal(hl7Preceding(obx, 'OBR', []))", "["
                + "{\"key\":\"Observation\",\"each\":\"msg['OBX']\",\"variable\":\"obx\",\"path\":[]},"
                + entry("Observation.status", "code", "'final'", "[[\"status\"]]") + ","
                + entry("Observation.code.text", "string", "obx['OBX.3']['OBX.3.1'].toString()", "[[\"code\"],[\"text\"]]") + ","
                + entry("Observation.subject.reference", "string", "fhirRef('patient')", "[[\"subject\"],[\"reference\"]]") + ","
                + entry("Observation.valueQuantity.value", "decimal", "obx['OBX.5']['OBX.5.1'].toString()", "[[\"valueQuantity\"],[\"value\"]]") + "]");
        // one DiagnosticReport per OBR, with the Observations of that OBR
        FhirProfileMapperStep report = bundled("DiagnosticReport", "", "["
                + "{\"key\":\"DiagnosticReport\",\"each\":\"msg['OBR']\",\"variable\":\"obr\",\"path\":[]},"
                + entry("DiagnosticReport.status", "code", "'final'", "[[\"status\"]]") + ","
                + entry("DiagnosticReport.code.text", "string", "obr['OBR.4']['OBR.4.1'].toString()", "[[\"code\"],[\"text\"]]") + ","
                + entry("DiagnosticReport.identifier.value", "string", "hl7Preceding(obr, 'ORC')['ORC.2']['ORC.2.1'].toString()", "[[\"identifier\",\"#0\"],[\"value\"]]") + ","
                + "{\"key\":\"DiagnosticReport.result\",\"each\":\"fhirRefs('obs-' + obrIndex)\",\"variable\":\"ref\",\"path\":[[\"result\",\"#0\"]]},"
                + entry("DiagnosticReport.result.reference", "string", "ref", "[[\"result\",\"#0\"],[\"reference\"]]") + "]");
        report.setRequestMethod("PUT");
        report.setRequestUrl("'DiagnosticReport?identifier=' + hl7Preceding(obr, 'ORC')['ORC.2']['ORC.2.1']");

        Map<?, ?> bundle = parse(run(patient, observation, report));
        assertEquals("Bundle", bundle.get("resourceType"));
        assertEquals("transaction", bundle.get("type"));
        assertEquals("{profile=[http://example.org/StructureDefinition/oru-bundle]}", bundle.get("meta").toString());
        List<?> entries = (List<?>) bundle.get("entry");
        assertEquals(1 + 3 + 2, entries.size());
        Map<?, ?> patientEntry = (Map<?, ?>) entries.get(0);
        String patientUrl = (String) patientEntry.get("fullUrl");
        assertTrue(patientUrl, patientUrl.matches("urn:uuid:[0-9a-f-]{36}"));
        assertEquals("{method=POST, url=Patient, ifNoneExist=identifier=urn:ipp|IPP1}", patientEntry.get("request").toString());

        Map<?, ?> glucose = (Map<?, ?>) ((Map<?, ?>) entries.get(1)).get("resource");
        assertEquals("{reference=" + patientUrl + "}", glucose.get("subject").toString());
        Map<?, ?> potassium = (Map<?, ?>) ((Map<?, ?>) entries.get(3)).get("resource");
        assertEquals("K", ((Map<?, ?>) potassium.get("code")).get("text"));

        Map<?, ?> panel1 = (Map<?, ?>) entries.get(4);
        Map<?, ?> panel2 = (Map<?, ?>) entries.get(5);
        assertEquals("{method=PUT, url=DiagnosticReport?identifier=ORD1}", panel1.get("request").toString());
        assertEquals("[{value=ORD2}]", ((Map<?, ?>) panel2.get("resource")).get("identifier").toString());
        String obs1 = (String) ((Map<?, ?>) entries.get(1)).get("fullUrl");
        String obs2 = (String) ((Map<?, ?>) entries.get(2)).get("fullUrl");
        String obs3 = (String) ((Map<?, ?>) entries.get(3)).get("fullUrl");
        assertEquals("[{reference=" + obs1 + "}, {reference=" + obs2 + "}]", ((Map<?, ?>) panel1.get("resource")).get("result").toString());
        assertEquals("[{reference=" + obs3 + "}]", ((Map<?, ?>) panel2.get("resource")).get("result").toString());
    }

    @Test
    public void aBundleStepFillsTheBundleItself() throws Exception {
        FhirProfileMapperStep header = bundled("Bundle", "", "["
                + entry("Bundle.identifier.value", "string", "msg['MSH']['MSH.10']['MSH.10.1'].toString()", "[[\"identifier\"],[\"value\"]]") + ","
                + entry("Bundle.type", "code", "'collection'", "[[\"type\"]]") + "]");
        header.setProfileUrl("http://example.org/StructureDefinition/oru-bundle");
        header.setAddMetaProfile(true);
        FhirProfileMapperStep patient = bundled("Patient", "", "[" + entry("Patient.gender", "code", "'female'", "[[\"gender\"]]") + "]");
        Map<?, ?> bundle = parse(run(patient, header));
        assertEquals("{value=MSG1}", bundle.get("identifier").toString());
        assertEquals("collection", bundle.get("type"));
        assertEquals("{profile=[http://example.org/StructureDefinition/oru-bundle]}", bundle.get("meta").toString());
        assertEquals(1, ((List<?>) bundle.get("entry")).size());
    }

    @Test
    public void decimalsKeepTheirText() throws Exception {
        String json = run(bundled("Observation", "", "[{\"key\":\"Observation\",\"each\":\"msg['OBX']\",\"variable\":\"obx\",\"path\":[]},"
                + entry("Observation.valueQuantity.value", "decimal", "obx['OBX.5']['OBX.5.1'].toString()", "[[\"valueQuantity\"],[\"value\"]]") + "]"));
        assertTrue(json, json.contains("\"value\":5.40}"));
        assertTrue(json, json.contains("\"value\":140}"));
        // and as FHIR XML
        String xml = FhirXml.jsonToXml(json, false);
        assertTrue(xml, xml.contains("<value value=\"5.40\"/>"));
    }

    @Test
    public void decimalsOfOneStepKeepTheirTextWhenALaterStepWritesTheBundle() throws Exception {
        FhirProfileMapperStep observation = bundled("Observation", "", "[" + entry("Observation.valueQuantity.value", "decimal", "'5.40'", "[[\"valueQuantity\"],[\"value\"]]") + "]");
        FhirProfileMapperStep patient = bundled("Patient", "", "[" + entry("Patient.gender", "code", "'female'", "[[\"gender\"]]") + "]");
        String json = run(observation, patient);
        assertTrue(json, json.contains("\"valueQuantity\":{\"value\":5.40}"));
    }

    @Test
    public void oneResourcePerItemNeedsABundle() {
        try {
            step("Observation", "[{\"key\":\"Observation\",\"each\":\"msg['OBX']\",\"variable\":\"obx\",\"path\":[]}]").getScript(false);
            fail("expected an error");
        } catch (ScriptBuilderException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("needs the output \"bundle\""));
        }
    }

    // ------------------------------------------------------------------ values

    @Test
    public void anEmptyObjectCreatesAnEntryForItsFixedValues() throws Exception {
        Map<?, ?> r = parse(run(step("Patient", "["
                + entry("Patient.identifier", "Identifier", "({})", "[[\"identifier\",\"#0\"]]") + ","
                + "{\"key\":\"Patient.identifier.system\",\"fixed\":\"urn:only-fixed\",\"when\":1,\"path\":[[\"identifier\",\"#0\"],[\"system\"]]},"
                + entry("Patient.contact", "BackboneElement", "({})", "[[\"contact\",\"#0\"]]") + "]")));
        assertEquals("[{system=urn:only-fixed}]", r.get("identifier").toString());
        assertFalse("an empty object without fixed values goes", r.containsKey("contact"));
    }

    @Test
    public void aPatternOnARepeatingElementGoesIntoACompatibleItemOrANewOne() throws Exception {
        String pattern = "{\"key\":\"Observation.category\",\"fixed\":{\"coding\":[{\"system\":\"http://terminology.hl7.org/CodeSystem/observation-category\",\"code\":\"laboratory\"}]},"
                + "\"when\":1,\"path\":[[\"category\",\"#0\"]]}";
        Map<?, ?> same = parse(run(step("Observation", "[" + entry("Observation.category.coding.code", "code", "'laboratory'", "[[\"category\",\"#0\"],[\"coding\",\"#0\"],[\"code\"]]")
                + "," + pattern + "]")));
        assertEquals("[{coding=[{code=laboratory, system=http://terminology.hl7.org/CodeSystem/observation-category}]}]", same.get("category").toString());
        Map<?, ?> other = parse(run(step("Observation", "[" + entry("Observation.category.coding.code", "code", "'local'", "[[\"category\",\"#0\"],[\"coding\",\"#0\"],[\"code\"]]")
                + "," + pattern + "]")));
        assertEquals("[{coding=[{code=local}, {system=http://terminology.hl7.org/CodeSystem/observation-category, code=laboratory}]}]", other.get("category").toString());
    }

    @Test
    public void anInstantNeedsATime() {
        try {
            run(step("Observation", "[" + entry("Observation.issued", "instant", "'20260114'", "[[\"issued\"]]") + "]"));
            fail("expected an error");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("has no time, which an instant needs"));
        }
    }

    @Test
    public void metaLastUpdatedAndUuids() throws Exception {
        FhirProfileMapperStep step = step("Patient", "[" + entry("Patient.id", "id", "fhirUuid()", "[[\"id\"]]") + "]");
        step.setSetLastUpdated(true);
        Map<?, ?> r = parse(run(step));
        assertTrue(String.valueOf(r.get("id")), String.valueOf(r.get("id")).matches("[0-9a-f-]{36}"));
        assertTrue(r.get("meta").toString(), r.get("meta").toString().matches("\\{lastUpdated=\\d{4}-\\d{2}-\\d{2}T.*\\}"));
    }

    // ------------------------------------------------------------------ terminology

    @Test
    public void theSourceCodingCanBeKeptWithAnAuthorizationHeader() throws Exception {
        FhirTerminology.clearCache();
        calls.set(0);
        FhirProfileMapperStep step = step("Observation", "[{\"key\":\"Observation.code\",\"type\":\"CodeableConcept\",\"expression\":\"'GLU'\",\"path\":[[\"code\"]],"
                + "\"translate\":{\"conceptMap\":\"http://example.org/cm\",\"source\":\"urn:local\",\"unmapped\":\"none\",\"keepSource\":true}}]");
        step.setTerminologyServer("http://127.0.0.1:" + server.getAddress().getPort() + "/fhir");
        step.setTerminologyAuthorization("'Bearer ' + 'secret'");
        step.setTerminologyCacheSeconds(0);
        assertEquals("{coding=[{system=http://loinc.org, code=2345-7}, {system=urn:local, code=GLU}]}", parse(run(step)).get("code").toString());
        assertEquals("Bearer secret", authorization.get());
        run(step);
        assertEquals("cache 0: asked again", 2, calls.get());
    }

    @Test
    public void lookupAndValidateCodeWithoutAServer() throws Exception {
        assertEquals("Female", FhirTools.lookup("n/a", "", "http://hl7.org/fhir/administrative-gender", "female"));
        assertNull(FhirTools.lookup("n/a", "", "http://hl7.org/fhir/administrative-gender", "nope"));
        assertTrue(FhirTools.validateCode("n/a", "", "http://hl7.org/fhir/ValueSet/administrative-gender", "http://hl7.org/fhir/administrative-gender", "male"));
        assertFalse(FhirTools.validateCode("n/a", "", "http://hl7.org/fhir/ValueSet/administrative-gender", "http://hl7.org/fhir/administrative-gender", "nope"));
    }

    @Test
    public void aTerminologyServerMustBeHttp() {
        try {
            FhirTerminology.translate("file:///etc/passwd", "", "http://example.org/cm", "", "x", "", "R4");
            fail("expected an error");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("must be an http or https URL"));
        }
    }

    // ------------------------------------------------------------------ HL7 v2 groups

    @Test
    public void hl7GroupFunctions() throws Exception {
        String out = run(step("Basic", "[" + entry("Basic.code.text", "string",
                "hl7Following(msg['OBR'][0], 'OBX', ['OBR', 'ORC']).length + '/' + hl7Following(msg['OBR'][1], 'OBX', ['OBR', 'ORC']).length"
                        + " + '/' + hl7Preceding(msg['OBR'][1], 'ORC')['ORC.2']['ORC.2.1'] + '/' + hl7Ordinal(msg['OBX'][2]) + '/' + (hl7Preceding(msg['PID'], 'OBR') === undefined)",
                "[[\"code\"],[\"text\"]]") + "]"));
        assertEquals("2/1/ORD2/2/true", ((Map<?, ?>) parse(out).get("code")).get("text"));
        String found = run(step("Basic", "[" + entry("Basic.code.text", "string",
                "fhirFind(msg['PID']['PID.3'], function (cx) { return cx['PID.3.5'] == 'NH'; })['PID.3.1'] + '/' + fhirFilter(msg['OBX'], function (o, i) { return i > 0; }).length",
                "[[\"code\"],[\"text\"]]") + "]"));
        assertEquals("NIR1/2", ((Map<?, ?>) parse(found).get("code")).get("text"));
    }
}
