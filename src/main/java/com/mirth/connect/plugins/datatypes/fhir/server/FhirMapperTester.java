/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;

import com.mirth.connect.plugins.datatypes.fhir.FhirEngine;
import com.mirth.connect.plugins.datatypes.fhir.FhirJson;
import com.mirth.connect.plugins.datatypes.fhir.FhirProfileMapperStep;
import com.mirth.connect.plugins.datatypes.fhir.FhirTools;
import com.mirth.connect.plugins.datatypes.fhir.FhirValidation;

/**
 * The checks of the FHIR Profile Mapper editor: the generated script with its syntax errors, and a
 * run of the step on a sample message with the validation of what it makes. The run is a sandbox
 * like a transformer: msg, tmp (no template), the maps and their $ functions, the configuration map
 * and the code templates of the server, and at most {@link #TIME_LIMIT_MILLIS} of work.
 */
public final class FhirMapperTester {

    static final long TIME_LIMIT_MILLIS = 10_000;

    private static final Logger logger = LogManager.getLogger(FhirMapperTester.class);

    /** Provides the code templates and the configuration map; the server's, or none in tests. */
    interface Environment {
        List<String> functions();

        Map<String, String> configuration();

        String serialize(String dataType, String message) throws Exception;
    }

    private FhirMapperTester() {}

    /** {"script": the generated script, "errors": [{"key", "line", "message"}]} for a step as JSON. */
    public static String script(String stepJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> errors = new ArrayList<>();
        try {
            String script = FhirProfileMapperStep.fromJson(stepJson).getScript(false);
            out.put("script", script);
            Context cx = new LimitedFactory().enterContext();
            try {
                cx.compileString("function doTransform() {\n" + script + "\n}", "step", 0, null);
            } catch (EvaluatorException e) {
                errors.add(error(script, e.lineNumber(), e.details()));
            } finally {
                Context.exit();
            }
        } catch (Exception e) {
            errors.add(error(null, 0, e.getMessage()));
        }
        out.put("errors", errors);
        return FhirJson.write(out);
    }

    /**
     * Runs a step on a message: {"step": {...}, "message": "...", "dataType": "HL7V2" | "XML" | "JSON" | "RAW"}.
     * Answers {"output": the outbound message, "issues": [{"severity", "location", "message"}], "errors": [...]}.
     */
    public static String test(String requestJson, Environment environment) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> errors = new ArrayList<>();
        List<Object> issues = new ArrayList<>();
        out.put("output", null);
        try {
            Object parsed = FhirJson.parse(requestJson);
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("The test request is not a JSON object.");
            }
            Map<?, ?> request = (Map<?, ?>) parsed;
            FhirProfileMapperStep step = FhirProfileMapperStep.fromJson(FhirJson.write(request.get("step")));
            String dataType = text(request.get("dataType")).isEmpty() ? "HL7V2" : text(request.get("dataType")).toUpperCase();
            String message = text(request.get("message"));
            String script = step.getScript(false);

            Context cx = new LimitedFactory().enterContext();
            String result;
            try {
                Scriptable scope = cx.initStandardObjects();
                Map<String, Object> channelMap = new HashMap<>();
                scope.put("__message", scope, environment.serialize(dataType, message));
                scope.put("__dataType", scope, dataType);
                scope.put("channelMap", scope, Context.javaToJS(channelMap, scope));
                scope.put("sourceMap", scope, Context.javaToJS(new HashMap<>(), scope));
                scope.put("connectorMap", scope, Context.javaToJS(new HashMap<>(), scope));
                scope.put("responseMap", scope, Context.javaToJS(new HashMap<>(), scope));
                scope.put("globalMap", scope, Context.javaToJS(new HashMap<>(), scope));
                scope.put("globalChannelMap", scope, Context.javaToJS(new HashMap<>(), scope));
                scope.put("configurationMap", scope, Context.javaToJS(new HashMap<>(environment.configuration()), scope));
                scope.put("logger", scope, Context.javaToJS(logger, scope));
                try {
                    cx.evaluateString(scope, SANDBOX, "sandbox", 1, null);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("The sample message cannot be read as " + dataType + ": " + e.getMessage(), e);
                }
                for (String function : environment.functions()) {
                    try {
                        cx.evaluateString(scope, function, "code template", 1, null);
                    } catch (RhinoException e) {
                        // a code template that does not compile here: its functions are missing in the test
                    }
                }
                try {
                    Object value = cx.evaluateString(scope, "function doTransform() {\n" + script + "\n}\ndoTransform();\n" + RESULT, "step", 0, null);
                    result = value == null || value instanceof org.mozilla.javascript.Undefined ? null : Context.toString(value);
                } catch (RhinoException e) {
                    errors.add(error(script, e.lineNumber(), e.details()));
                    result = null;
                } catch (TimeLimit e) {
                    errors.add(error(null, 0, e.getMessage()));
                    result = null;
                }
                if (!text(step.getVariable()).isEmpty()) {
                    Object variable = channelMap.get(step.getVariable().trim());
                    result = variable == null ? null : variable.toString();
                }
            } finally {
                Context.exit();
            }
            out.put("output", result);
            if (result != null && (result.trim().startsWith("{") || result.trim().startsWith("<"))) {
                try {
                    FhirValidation validation = FhirTools.validate(result, "", step.getProfiles(), step.getFhirVersion());
                    for (Map<String, String> issue : validation.getIssues()) {
                        Map<String, Object> i = new LinkedHashMap<>();
                        i.put("severity", issue.get(FhirEngine.SEVERITY));
                        i.put("location", issue.get(FhirEngine.LOCATION));
                        i.put("message", issue.get(FhirEngine.MESSAGE));
                        issues.add(i);
                    }
                } catch (Exception e) {
                    errors.add(error(null, 0, "Validation: " + e.getMessage()));
                }
            } else if (result != null && errors.isEmpty()) {
                errors.add(error(null, 0, "The step made no FHIR resource from this message (the inbound message is unchanged)."));
            }
        } catch (Exception e) {
            errors.add(error(null, 0, e.getMessage()));
        }
        out.put("issues", issues);
        out.put("errors", errors);
        return FhirJson.write(out);
    }

    private static Map<String, Object> error(String script, int line, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        String key = script == null ? null : FhirProfileMapperStep.keyAt(script, line);
        // An error of an expression is thrown by the runtime, which names the element.
        java.util.regex.Matcher named = RUNTIME_ERROR.matcher(message == null ? "" : message);
        if (named.find()) {
            key = named.group(1);
        }
        e.put("key", key == null ? FhirJson.NULL : key);
        e.put("line", line <= 0 ? FhirJson.NULL : String.valueOf(line));
        e.put("message", message == null ? "" : message);
        return e;
    }

    private static final java.util.regex.Pattern RUNTIME_ERROR = java.util.regex.Pattern.compile("FHIR Profile Mapper, (?:condition of |for each )?([^:\\s]+):");

    private static String text(Object value) {
        return value == null || value == FhirJson.NULL ? "" : value.toString();
    }

    /**
     * msg as a transformer has it for the data type, and the functions of the transformer scope the
     * expressions most use. fhirRef gives a stand-in urn:uuid for a name an earlier step would add:
     * the test runs this step alone.
     */
    private static final String SANDBOX = String.join("\n",
            "var msg = __dataType == 'JSON' ? JSON.parse(__message) : (__dataType == 'RAW' ? String(__message) : new XML(__message));",
            "if (typeof msg === 'xml' && msg.namespace('') != undefined) { default xml namespace = msg.namespace(''); }",
            "function $c(k, v) { if (arguments.length > 1) { channelMap.put(k, v); return v; } return channelMap.get(k); }",
            "function $s(k, v) { if (arguments.length > 1) { sourceMap.put(k, v); return v; } return sourceMap.get(k); }",
            "function $co(k, v) { if (arguments.length > 1) { connectorMap.put(k, v); return v; } return connectorMap.get(k); }",
            "function $r(k, v) { if (arguments.length > 1) { responseMap.put(k, v); return v; } return responseMap.get(k); }",
            "function $g(k, v) { if (arguments.length > 1) { globalMap.put(k, v); return v; } return globalMap.get(k); }",
            "function $gc(k, v) { if (arguments.length > 1) { globalChannelMap.put(k, v); return v; } return globalChannelMap.get(k); }",
            "function $cfg(k) { return configurationMap.get(k); }",
            "function $(k) { var maps = [responseMap, connectorMap, channelMap, sourceMap, globalChannelMap, globalMap, configurationMap];",
            "  for (var i = 0; i < maps.length; i++) { if (maps[i].containsKey(k)) { return maps[i].get(k); } } return ''; }",
            "function __fhirTestRefs() { return (typeof __fhirProfileMapper !== 'undefined' && __fhirProfileMapper) ? __fhirProfileMapper.refs : {}; }",
            "function fhirRef(name, index) { var l = __fhirTestRefs()[name]; if (l && l.length) { return l[index === undefined ? 0 : index]; }",
            "  return 'urn:uuid:' + java.util.UUID.nameUUIDFromBytes(new java.lang.String('fhir-profile-mapper-test:' + name + ':' + (index || 0)).getBytes('UTF-8')); }",
            "function fhirRefs(name) { var l = __fhirTestRefs()[name]; return l && l.length ? l.slice() : [fhirRef(name)]; }");

    /** What the engine takes as the outbound message: msg (no template in the test), as text. */
    private static final String RESULT = String.join("\n",
            "var __t = Object.prototype.toString.call(msg);",
            "typeof msg === 'xml' ? msg.toXMLString() : (__t == '[object Object]' || __t == '[object Array]' ? JSON.stringify(msg) : String(msg));");

    /** Interpreted (a large step stays under the size limit of compiled methods), and at most TIME_LIMIT_MILLIS. */
    private static final class LimitedFactory extends ContextFactory {
        private final long start = System.currentTimeMillis();

        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);
            cx.setInstructionObserverThreshold(10_000);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            if (System.currentTimeMillis() - start > TIME_LIMIT_MILLIS) {
                throw new TimeLimit();
            }
        }
    }

    /** Thrown into the script when the test takes too long: an Error, so that scripts cannot catch it. */
    private static final class TimeLimit extends Error {
        private static final long serialVersionUID = 1L;

        TimeLimit() {
            super("The test took longer than " + TIME_LIMIT_MILLIS / 1000 + " seconds and was stopped.");
        }
    }
}
