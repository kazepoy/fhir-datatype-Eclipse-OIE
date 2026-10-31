package com.mirth.connect.plugins.datatypes.fhir;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

public class FhirValidatorUsageTest {

    @After
    public void reset() {
        FhirValidatorUsage.setInUse(List::of);
    }

    @Test
    public void releaseHandsTheDeployedOptionsToTheEngine() {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setTerminologyServer("http://release-test.invalid/fhir");
        FhirValidatorUsage.setInUse(() -> List.of(p.engineOptions()));

        List<Object[]> calls = new ArrayList<>();
        FhirValidatorUsage.releaseNow(new RecordingEngine(calls), 5);
        assertEquals(1, calls.size());
        assertEquals(List.of(p.engineOptions()), calls.get(0)[0]);
        assertEquals(5L, calls.get(0)[1]);
    }

    @Test
    public void withoutTheServerPluginNothingCountsAsDeployed() {
        List<Object[]> calls = new ArrayList<>();
        FhirValidatorUsage.releaseNow(new RecordingEngine(calls), FhirValidatorUsage.IDLE_MILLIS);
        assertEquals(List.of(), calls.get(0)[0]);
    }

    private static final class RecordingEngine implements FhirEngine {
        private final List<Object[]> calls;

        RecordingEngine(List<Object[]> calls) {
            this.calls = calls;
        }

        @Override
        public void retainValidators(List<Map<String, String>> inUse, long idleMillis) {
            calls.add(new Object[] { inUse, idleMillis });
        }

        // @formatter:off
        @Override public List<Map<String, String>> validate(String resource, Map<String, String> options) { throw new UnsupportedOperationException(); }
        @Override public String toJson(String xml, String fhirVersion, boolean pretty) { throw new UnsupportedOperationException(); }
        @Override public String toXml(String json, String fhirVersion, boolean pretty) { throw new UnsupportedOperationException(); }
        @Override public String reorderXml(String xml, String fhirVersion, boolean pretty) { throw new UnsupportedOperationException(); }
        @Override public String reorderJson(String json, String fhirVersion, boolean pretty) { throw new UnsupportedOperationException(); }
        @Override public String listProfiles(Map<String, String> options) { throw new UnsupportedOperationException(); }
        @Override public String structureDefinition(String url, Map<String, String> options) { throw new UnsupportedOperationException(); }
        @Override public String listConceptMaps(Map<String, String> options) { throw new UnsupportedOperationException(); }
        @Override public String translate(String conceptMap, String system, String code, String targetSystem, Map<String, String> options) { throw new UnsupportedOperationException(); }
        @Override public String lookup(String system, String code, Map<String, String> options) { throw new UnsupportedOperationException(); }
        @Override public boolean validateCode(String valueSet, String system, String code, Map<String, String> options) { throw new UnsupportedOperationException(); }
        @Override public void warmUp(Map<String, String> options) {}
        // @formatter:on
    }
}
