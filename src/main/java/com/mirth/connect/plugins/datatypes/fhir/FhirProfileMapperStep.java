/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.mirth.connect.model.Step;
import com.mirth.connect.util.ScriptBuilderException;

/**
 * Transformer step that builds FHIR resources conforming to a profile: the web administrator shows
 * the element tree of the profile, and each element gets a JavaScript expression, usually a path in
 * the inbound message (msg['PID']['PID.5']['PID.5.1']). Fixed and pattern values of the profile
 * (slice discriminators, extension URLs) are added to every entry that got a mapped value.
 *
 * A repeating element can iterate: one entry per item of a list (msg['PID']['PID.3']), with the item
 * and its index as variables for the expressions below it. Any element can have a condition: what is
 * below it is only mapped when it holds (per item of an iteration). The resource itself can iterate
 * (one resource per OBX) and have a condition. A value can be translated with ConceptMap/$translate
 * on the {@link #getTerminologyServer() terminology server}, or without one (empty or "n/a") locally
 * on the ConceptMaps of the {@link #getProfiles() profiles}. Without a match the element stays
 * empty, keeps the source code, gets a fixed value or the result of an expression (a function
 * call), or the message gets an error.
 *
 * The resource becomes the outbound message: tmp (FHIR XML or a JavaScript object, as the outbound
 * template) when there is an outbound template, otherwise msg (FHIR JSON text), which the engine
 * then sends on. Or it goes as FHIR JSON to a channel map variable. With {@link #getOutput() output}
 * "bundle", the resources are added to a Bundle the steps of the transformer share (fullUrl
 * urn:uuid:..., request for a transaction or batch), which becomes the outbound message instead;
 * fhirRef(name) gives the fullUrl of a resource an earlier step added under that reference name.
 *
 * {@link #getMappings() mappings} is JSON, written by the web administrator: an array of
 * <pre>
 * {"key": "Patient.identifier:ipp.value", "type": "string", "expression": "msg[...]",
 *  "path": [["identifier", "ipp#0"], ["value"]]}
 * {"key": "Patient.gender", "type": "code", "expression": "msg[...]", "path": [["gender"]],
 *  "translate": {"conceptMap": "http://...", "source": "http://...", "target": "", "unmapped": "none", "keepSource": false}}
 *  (unmapped: none, source, fixed or function with "value": "system|code" or an expression of code and system, error)
 * {"key": "Patient.identifier", "each": "msg['PID']['PID.3']", "variable": "item", "path": [["identifier", "#0"]]}
 * {"key": "Patient.identifier", "condition": "item['PID.3.5'].toString() == 'PI'", "path": [["identifier", "#0"]]}
 * {"key": "Observation", "each": "msg['OBX']", "variable": "obx", "path": []}   (the resource)
 * {"key": "Patient.identifier:ipp.system", "fixed": "urn:oid:...", "when": 1, "path": [...]}
 * </pre>
 * A path segment is [name] or [name, item]: an item of a repeating element. A fixed value is only
 * set when the first {@code when} segments of its path exist. An entry belongs to the innermost
 * iteration or condition whose key is its key or a prefix of it (followed by '.').
 */
public class FhirProfileMapperStep extends Step {

    public static final String PLUGIN_POINT = "FHIR Profile Mapper";

    private static final String RUNTIME_RESOURCE = "profile-mapper-runtime.js";
    /** Before the code of each element in the script, so that an error line leads to the element. */
    private static final String KEY_COMMENT = "//@ ";
    private static String runtime;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    /** Not usable as the variable of an iteration: the transformer's own variables and JavaScript words. */
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList("msg", "tmp", "await", "break", "case", "catch", "class", "const",
            "continue", "debugger", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for", "function", "if",
            "import", "in", "instanceof", "let", "new", "null", "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof",
            "var", "void", "while", "with", "yield"));
    private static final Set<String> UNMAPPED = new HashSet<>(Arrays.asList("none", "source", "fixed", "function", "error"));
    private static final Set<String> BUNDLE_TYPES = new HashSet<>(Arrays.asList("transaction", "batch", "collection", "document", "message", "searchset", "history"));
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("POST", "PUT", "PATCH", "DELETE", "GET", "HEAD"));

    private String fhirVersion = "R4";
    private String profiles = "";
    private String profileUrl = "";
    private String profileVersion = "";
    private String resourceType = "";
    private boolean addMetaProfile = true;
    private boolean setLastUpdated = false;
    private String variable = "";
    private String output = "resource";
    private String bundleType = "transaction";
    private String bundleProfile = "";
    private String refName = "";
    private String requestMethod = "POST";
    private String requestUrl = "";
    private String ifNoneExist = "";
    private String terminologyServer = "";
    private String terminologyAuthorization = "";
    private int terminologyCacheSeconds = 300;
    private int terminologyTimeoutSeconds = 10;
    private String mappings = "[]";

    public FhirProfileMapperStep() {}

    public FhirProfileMapperStep(FhirProfileMapperStep props) {
        super(props);
        fhirVersion = props.getFhirVersion();
        profiles = props.getProfiles();
        profileUrl = props.getProfileUrl();
        profileVersion = props.getProfileVersion();
        resourceType = props.getResourceType();
        addMetaProfile = props.isAddMetaProfile();
        setLastUpdated = props.isSetLastUpdated();
        variable = props.getVariable();
        output = props.getOutput();
        bundleType = props.getBundleType();
        bundleProfile = props.getBundleProfile();
        refName = props.getRefName();
        requestMethod = props.getRequestMethod();
        requestUrl = props.getRequestUrl();
        ifNoneExist = props.getIfNoneExist();
        terminologyServer = props.getTerminologyServer();
        terminologyAuthorization = props.getTerminologyAuthorization();
        terminologyCacheSeconds = props.getTerminologyCacheSeconds();
        terminologyTimeoutSeconds = props.getTerminologyTimeoutSeconds();
        mappings = props.getMappings();
    }

    /** The resource, or an iteration or condition: what its entries are and in which order. */
    private static final class Scope {
        final String key;
        /** The iteration of the element, or null. */
        final Map<?, ?> iteration;
        /** The condition on the element (per item when it iterates), or null. */
        final String condition;
        /** The path length of the element; with an iteration its last segment gets the index. */
        final int depth;
        final String indexVariable;
        Scope parent;
        /** Value entries (Map) and iterations or conditions (Scope), in tree order. */
        final List<Object> items = new ArrayList<>();
        final List<Map<?, ?>> fixed = new ArrayList<>();

        Scope(String key, Map<?, ?> iteration, String condition, int depth, String indexVariable) {
            this.key = key;
            this.iteration = iteration;
            this.condition = condition;
            this.depth = depth;
            this.indexVariable = indexVariable;
        }

        boolean contains(String other) {
            return key == null || other.equals(key) || other.startsWith(key + ".");
        }
    }

    @Override
    public String getScript(boolean loadFiles) throws ScriptBuilderException {
        if (resourceType == null || resourceType.trim().isEmpty()) {
            throw new ScriptBuilderException("FHIR Profile Mapper \"" + getName() + "\": no profile has been loaded (the resource type is empty).");
        }
        List<?> entries;
        try {
            Object parsed = FhirJson.parse(mappings == null || mappings.trim().isEmpty() ? "[]" : mappings);
            if (!(parsed instanceof List)) {
                throw new IllegalArgumentException("not a JSON array");
            }
            entries = (List<?>) parsed;
        } catch (IllegalArgumentException e) {
            throw new ScriptBuilderException("FHIR Profile Mapper \"" + getName() + "\": the mappings are invalid: " + e.getMessage(), e);
        }
        boolean bundle = isBundle();
        if (bundle && !BUNDLE_TYPES.contains(text(bundleType))) {
            throw error("unknown Bundle type \"" + bundleType + "\"");
        }
        boolean withRequest = bundle && ("transaction".equals(bundleType) || "batch".equals(bundleType));
        if (withRequest && !METHODS.contains(text(requestMethod))) {
            throw error("unknown request method \"" + requestMethod + "\"");
        }

        // The iterations and conditions per element; those on the resource itself (empty path) apart.
        Map<?, ?> resourceIteration = null;
        String resourceCondition = null;
        Map<String, Map<?, ?>> iterations = new HashMap<>();
        Map<String, String> conditions = new HashMap<>();
        Map<String, Integer> depths = new HashMap<>();
        for (Object entry : entries) {
            Map<?, ?> m = entry(entry);
            String key = key(m);
            boolean root = ((List<?>) m.get("path")).isEmpty();
            if (m.containsKey("each") && !text(m.get("each")).trim().isEmpty()) {
                String var = text(m.get("variable")).trim();
                if (!IDENTIFIER.matcher(var).matches() || RESERVED.contains(var) || var.startsWith("__")) {
                    throw error("the variable of the iteration on " + key + " must be a JavaScript name (not msg or tmp): \"" + var + "\"");
                }
                if (root) {
                    if (!bundle) {
                        throw error("one resource per item needs the output \"bundle\"");
                    }
                    resourceIteration = m;
                    continue;
                }
                List<?> path = (List<?>) m.get("path");
                if (!(path.get(path.size() - 1) instanceof List) || ((List<?>) path.get(path.size() - 1)).size() != 2) {
                    throw error(key + " does not repeat: it cannot iterate");
                }
                if (iterations.put(key, m) != null) {
                    throw error("two iterations on " + key);
                }
                depths.put(key, path.size());
            } else if (m.containsKey("condition") && !text(m.get("condition")).trim().isEmpty()) {
                if (root) {
                    resourceCondition = text(m.get("condition")).trim();
                    continue;
                }
                conditions.put(key, text(m.get("condition")).trim());
                depths.put(key, ((List<?>) m.get("path")).size());
            }
        }
        Scope resource = new Scope(null, null, null, 0, null);
        Map<String, Scope> scopes = new LinkedHashMap<>();
        List<String> scoped = new ArrayList<>(depths.keySet());
        scoped.sort((a, b) -> a.length() - b.length()); // outer ones first, so that each finds the one it is in
        for (String key : scoped) {
            Map<?, ?> it = iterations.get(key);
            Scope scope = new Scope(key, it, conditions.get(key), depths.get(key), it == null ? null : text(it.get("variable")).trim() + "Index");
            scope.parent = innermost(scopes.values(), resource, key, true);
            scopes.put(key, scope);
        }
        Set<String> placed = new HashSet<>();
        for (Object entry : entries) {
            Map<?, ?> m = entry(entry);
            String key = key(m);
            if (((List<?>) m.get("path")).isEmpty()) {
                continue;
            }
            if (m.containsKey("each") || m.containsKey("condition")) {
                Scope scope = scopes.get(key);
                if (scope != null && placed.add(key)) {
                    scope.parent.items.add(scope);
                }
            } else if (m.containsKey("fixed")) {
                innermost(scopes.values(), resource, key, false).fixed.add(m);
            } else if (!text(m.get("expression")).trim().isEmpty()) {
                innermost(scopes.values(), resource, key, false).items.add(m);
            }
        }

        String var = resourceIteration == null ? "" : text(resourceIteration.get("variable")).trim();
        String params = resourceIteration == null ? "" : var + ", " + var + "Index";
        StringBuilder sb = new StringBuilder();
        sb.append("// FHIR Profile Mapper: ").append(oneLine(profileUrl.isEmpty() ? resourceType : profileUrl)).append('\n');
        sb.append("(function () {\n");
        sb.append("var __fhir = ").append(runtime()).append(";\n");
        sb.append("__fhir.options = {terminologyServer: ").append(FhirJson.write(text(terminologyServer)))
                .append(", profiles: ").append(FhirJson.write(text(profiles)))
                .append(", fhirVersion: ").append(FhirJson.write(text(fhirVersion).isEmpty() ? "R4" : text(fhirVersion)))
                .append(", cacheSeconds: ").append(Math.max(0, terminologyCacheSeconds))
                .append(", timeoutSeconds: ").append(terminologyTimeoutSeconds > 0 ? terminologyTimeoutSeconds : 10)
                .append(", authorization: ").append(text(terminologyAuthorization).isEmpty() ? "''" : "String((function () {\nreturn (" + text(terminologyAuthorization) + ");\n})())")
                .append("};\n");
        // The mapping runs with msg as the inbound message, which a step before may have replaced.
        sb.append("var __out = (function (msg) {\n");
        sb.append("var __build = function (").append(params).append(") {\n");
        sb.append("var __r = {resourceType: ").append(FhirJson.write(resourceType.trim())).append("};\n");
        if (addMetaProfile && !profileUrl.trim().isEmpty()) {
            sb.append("__fhir.set(__r, [[\"meta\"], [\"profile\", \"#profile\"]], \"canonical\", function () { return ")
                    .append(FhirJson.write(profileUrl.trim())).append("; }, \"meta.profile\", null);\n");
        }
        if (setLastUpdated) {
            sb.append("__fhir.set(__r, [[\"meta\"], [\"lastUpdated\"]], \"instant\", function () { return String(java.time.OffsetDateTime.now()); }, \"meta.lastUpdated\", null);\n");
        }
        write(sb, resource);
        sb.append("return __fhir.finish(__r);\n};\n");

        sb.append("var __out;\n");
        sb.append("var __emit = function (").append(params).append(") {\n");
        if (resourceCondition != null) {
            sb.append("if (!__fhir.when(function () {\nreturn (").append(resourceCondition).append(");\n}, ").append(FhirJson.write(resourceType.trim())).append(")) {\nreturn;\n}\n");
        }
        sb.append("var __res = __build(").append(params).append(");\n");
        if (bundle && "Bundle".equals(resourceType.trim())) {
            // the Bundle itself: its elements, not an entry
            sb.append("__fhir.header(").append(FhirJson.write(text(bundleType))).append(", __res);\n");
        } else if (bundle) {
            String request = "null";
            if (withRequest) {
                request = "__fhir.request(" + FhirJson.write(text(requestMethod)) + ", __res, function () {\nreturn (" + orEmpty(requestUrl) + ");\n}, function () {\nreturn ("
                        + orEmpty(ifNoneExist) + ");\n})";
            }
            sb.append("__fhir.entry(").append(FhirJson.write(text(bundleType))).append(", ")
                    .append(text(refName).isEmpty() ? "null" : "String((function () {\nreturn (" + text(refName) + ");\n})())").append(", __res, ")
                    .append(request).append(", ").append(FhirJson.write(text(bundleProfile))).append(");\n");
        } else {
            sb.append("__out = __res;\n");
        }
        sb.append("};\n");
        if (resourceIteration != null) {
            sb.append("__fhir.each(function () {\nreturn (").append(text(resourceIteration.get("each")).trim()).append(");\n}, __emit, ")
                    .append(FhirJson.write(resourceType.trim())).append(");\n");
        } else {
            sb.append("__emit();\n");
        }
        if (bundle) {
            sb.append("__out = __fhir.bundle();\n");
        }
        sb.append("return __out;\n})(__fhir.source(msg));\n");
        sb.append("if (__out === undefined || __out === null) {\nreturn;\n}\n");
        String target = text(variable);
        if (target.isEmpty()) {
            sb.append("if ('xml' === typeof tmp) {\n");
            sb.append("    tmp = new XML(String(Packages.com.mirth.connect.plugins.datatypes.fhir.FhirXml.jsonToXml(__fhir.json(__out), tmp.namespace().uri == ")
                    .append(FhirJson.write(FhirXml.FHIR_NS)).append(")));\n");
            sb.append("} else if ('string' === typeof tmp) {\n");
            sb.append("    tmp = __fhir.json(__out);\n");
            sb.append("} else if ('undefined' !== typeof tmp && tmp !== null) {\n");
            sb.append("    tmp = __fhir.plain(__out);\n");
            // Without an outbound template the engine takes msg as the outbound message, not tmp.
            sb.append("} else {\n");
            sb.append("    msg = __fhir.json(__out);\n");
            sb.append("}\n");
        } else {
            sb.append("channelMap.put(").append(FhirJson.write(target)).append(", __fhir.json(__out));\n");
        }
        sb.append("})();\n");
        return sb.toString();
    }

    /** The element whose code is at a line (1-based) of the script, or null. */
    public static String keyAt(String script, int line) {
        String[] lines = script.split("\n", -1);
        for (int i = Math.min(line, lines.length) - 1; i >= 0; i--) {
            if (lines[i].startsWith(KEY_COMMENT)) {
                return lines[i].substring(KEY_COMMENT.length());
            }
        }
        return null;
    }

    /** A step from the JSON of the web administrator's element (strings, booleans and numbers, also as text). */
    public static FhirProfileMapperStep fromJson(String json) {
        Object parsed = FhirJson.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("The step is not a JSON object.");
        }
        Map<?, ?> m = (Map<?, ?>) parsed;
        FhirProfileMapperStep step = new FhirProfileMapperStep();
        step.setName(string(m, "name", ""));
        step.setFhirVersion(string(m, "fhirVersion", "R4"));
        step.setProfiles(string(m, "profiles", ""));
        step.setProfileUrl(string(m, "profileUrl", ""));
        step.setProfileVersion(string(m, "profileVersion", ""));
        step.setResourceType(string(m, "resourceType", ""));
        step.setAddMetaProfile(!"false".equals(string(m, "addMetaProfile", "true")));
        step.setSetLastUpdated("true".equals(string(m, "setLastUpdated", "false")));
        step.setVariable(string(m, "variable", ""));
        step.setOutput(string(m, "output", "resource"));
        step.setBundleType(string(m, "bundleType", "transaction"));
        step.setBundleProfile(string(m, "bundleProfile", ""));
        step.setRefName(string(m, "refName", ""));
        step.setRequestMethod(string(m, "requestMethod", "POST"));
        step.setRequestUrl(string(m, "requestUrl", ""));
        step.setIfNoneExist(string(m, "ifNoneExist", ""));
        step.setTerminologyServer(string(m, "terminologyServer", ""));
        step.setTerminologyAuthorization(string(m, "terminologyAuthorization", ""));
        step.setTerminologyCacheSeconds(integer(m, "terminologyCacheSeconds", 300));
        step.setTerminologyTimeoutSeconds(integer(m, "terminologyTimeoutSeconds", 10));
        step.setMappings(string(m, "mappings", "[]"));
        return step;
    }

    private static String string(Map<?, ?> m, String key, String fallback) {
        Object value = m.get(key);
        return value == null || value == FhirJson.NULL ? fallback : value.toString();
    }

    private static int integer(Map<?, ?> m, String key, int fallback) {
        try {
            return Integer.parseInt(string(m, key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isBundle() {
        return "bundle".equals(text(output));
    }

    private static String orEmpty(String expression) {
        return text(expression).isEmpty() ? "''" : text(expression);
    }

    /** The values, iterations and conditions of a scope in order, then its fixed values: they need to know which entries exist. */
    private void write(StringBuilder sb, Scope scope) throws ScriptBuilderException {
        for (Object item : scope.items) {
            if (item instanceof Scope) {
                Scope it = (Scope) item;
                sb.append(KEY_COMMENT).append(oneLine(it.key)).append('\n');
                if (it.iteration != null) {
                    String var = text(it.iteration.get("variable")).trim();
                    sb.append("__fhir.each(function () {\nreturn (").append(text(it.iteration.get("each")).trim()).append(");\n}, function (")
                            .append(var).append(", ").append(it.indexVariable).append(") {\n");
                    writeConditional(sb, it);
                    sb.append("}, ").append(FhirJson.write(it.key)).append(");\n");
                } else {
                    writeConditional(sb, it);
                }
            } else {
                Map<?, ?> m = (Map<?, ?>) item;
                sb.append(KEY_COMMENT).append(oneLine(key(m))).append('\n');
                sb.append("__fhir.set(__r, ").append(path(m, scope)).append(", ").append(FhirJson.write(text(m.get("type"))))
                        .append(", function () {\nreturn (").append(text(m.get("expression")).trim()).append(");\n}, ")
                        .append(FhirJson.write(key(m))).append(", ").append(translation(m)).append(");\n");
            }
        }
        for (Map<?, ?> m : scope.fixed) {
            int when;
            try {
                when = m.get("when") == null ? 0 : Integer.parseInt(m.get("when").toString());
            } catch (NumberFormatException e) {
                throw error("invalid \"when\" in " + key(m), e);
            }
            sb.append("__fhir.fixed(__r, ").append(path(m, scope)).append(", ").append(when).append(", ").append(FhirJson.write(m.get("fixed"))).append(");\n");
        }
    }

    private void writeConditional(StringBuilder sb, Scope scope) throws ScriptBuilderException {
        if (scope.condition == null) {
            write(sb, scope);
            return;
        }
        sb.append("if (__fhir.when(function () {\nreturn (").append(scope.condition).append(");\n}, ").append(FhirJson.write(scope.key)).append(")) {\n");
        write(sb, scope);
        sb.append("}\n");
    }

    /** The innermost iteration an entry is in; for an iteration (self), the one around it. */
    private static Scope innermost(Collection<Scope> scopes, Scope resource, String key, boolean self) {
        Scope found = resource;
        for (Scope s : scopes) {
            if ((self ? key.startsWith(s.key + ".") : s.contains(key)) && (found.key == null || s.key.length() > found.key.length())) {
                found = s;
            }
        }
        return found;
    }

    private String translation(Map<?, ?> m) throws ScriptBuilderException {
        if (!(m.get("translate") instanceof Map)) {
            return "null";
        }
        Map<?, ?> t = (Map<?, ?>) m.get("translate");
        if (text(t.get("conceptMap")).trim().isEmpty()) {
            throw error("the translation of " + key(m) + " has no ConceptMap");
        }
        String unmapped = text(t.get("unmapped")).trim().isEmpty() ? "none" : text(t.get("unmapped")).trim();
        if (!UNMAPPED.contains(unmapped)) {
            throw error("the translation of " + key(m) + " has an unknown \"unmapped\": " + unmapped);
        }
        String value = text(t.get("value")).trim();
        if (("fixed".equals(unmapped) || "function".equals(unmapped)) && value.isEmpty()) {
            throw error("the translation of " + key(m) + " has no " + ("fixed".equals(unmapped) ? "fixed value" : "expression") + " for codes without a match");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conceptMap", text(t.get("conceptMap")).trim());
        out.put("source", text(t.get("source")).trim());
        out.put("target", text(t.get("target")).trim());
        out.put("unmapped", unmapped);
        if ("fixed".equals(unmapped)) {
            out.put("value", value);
        }
        out.put("keepSource", Boolean.TRUE.equals(t.get("keepSource")));
        String json = FhirJson.write(out);
        if (!"function".equals(unmapped)) {
            return json;
        }
        // The expression runs where the code has no match, with the code and its system.
        return json.substring(0, json.lastIndexOf('}')).replaceAll("\\s+$", "") + ",\n  \"fallback\": function (code, system) {\nreturn (" + value + ");\n}\n}";
    }

    private Map<?, ?> entry(Object entry) throws ScriptBuilderException {
        if (!(entry instanceof Map) || !(((Map<?, ?>) entry).get("path") instanceof List)) {
            throw error("a mapping has no path");
        }
        Map<?, ?> m = (Map<?, ?>) entry;
        if (((List<?>) m.get("path")).isEmpty() && !m.containsKey("each") && !m.containsKey("condition")) {
            throw error("a mapping has no path");
        }
        return m;
    }

    /** The key of an entry; older entries only have the element. */
    private static String key(Map<?, ?> m) {
        return text(m.get("key")).isEmpty() ? text(m.get("element")) : text(m.get("key"));
    }

    /**
     * The path as a JavaScript array of segments, on one line. In an iteration, the item of the
     * iterated element gets the index: "#0" + "*" + itemIndex.
     */
    private String path(Map<?, ?> m, Scope scope) throws ScriptBuilderException {
        Map<Integer, String> indexes = new HashMap<>();
        for (Scope s = scope; s != null && s.key != null; s = s.parent) {
            if (s.iteration != null) {
                indexes.put(s.depth - 1, s.indexVariable);
            }
        }
        StringBuilder sb = new StringBuilder("[");
        List<?> path = (List<?>) m.get("path");
        for (int i = 0; i < path.size(); i++) {
            Object seg = path.get(i);
            if (!(seg instanceof List) || ((List<?>) seg).isEmpty() || ((List<?>) seg).size() > 2) {
                throw error("invalid path segment in " + key(m));
            }
            List<?> parts = (List<?>) seg;
            sb.append(i == 0 ? "" : ", ").append('[').append(FhirJson.write(text(parts.get(0))));
            if (parts.size() == 2) {
                sb.append(", ").append(FhirJson.write(text(parts.get(1))));
                if (indexes.containsKey(i)) {
                    sb.append(" + \"*\" + ").append(indexes.get(i));
                }
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    private ScriptBuilderException error(String message) {
        return new ScriptBuilderException("FHIR Profile Mapper \"" + getName() + "\": " + message + ".");
    }

    private ScriptBuilderException error(String message, Throwable cause) {
        return new ScriptBuilderException("FHIR Profile Mapper \"" + getName() + "\": " + message + ".", cause);
    }

    private static String text(Object value) {
        return value == null || value == FhirJson.NULL ? "" : value.toString();
    }

    private static String oneLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ");
    }

    private static synchronized String runtime() {
        if (runtime == null) {
            try (InputStream in = FhirProfileMapperStep.class.getResourceAsStream(RUNTIME_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RUNTIME_RESOURCE + " is missing from the FHIR data type.");
                }
                runtime = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return runtime;
    }

    @Override
    public String getType() {
        return PLUGIN_POINT;
    }

    @Override
    public Step clone() {
        return new FhirProfileMapperStep(this);
    }

    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = super.getPurgedProperties();
        purged.put("fhirVersion", fhirVersion);
        purged.put("addMetaProfile", addMetaProfile);
        return purged;
    }

    public String getFhirVersion() {
        return fhirVersion;
    }

    public void setFhirVersion(String fhirVersion) {
        this.fhirVersion = fhirVersion;
    }

    public String getProfiles() {
        return profiles;
    }

    public void setProfiles(String profiles) {
        this.profiles = profiles;
    }

    public String getProfileUrl() {
        return profileUrl;
    }

    public void setProfileUrl(String profileUrl) {
        this.profileUrl = profileUrl;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public boolean isAddMetaProfile() {
        return addMetaProfile;
    }

    public void setAddMetaProfile(boolean addMetaProfile) {
        this.addMetaProfile = addMetaProfile;
    }

    public String getVariable() {
        return variable;
    }

    public void setVariable(String variable) {
        this.variable = variable;
    }

    /** The version of the profile when the mappings were made, to notice a changed profile. */
    public String getProfileVersion() {
        return profileVersion;
    }

    public void setProfileVersion(String profileVersion) {
        this.profileVersion = profileVersion;
    }

    public boolean isSetLastUpdated() {
        return setLastUpdated;
    }

    public void setSetLastUpdated(boolean setLastUpdated) {
        this.setLastUpdated = setLastUpdated;
    }

    /** "resource" (the resource is the outbound message) or "bundle" (added to the Bundle of the transformer). */
    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getBundleType() {
        return bundleType;
    }

    public void setBundleType(String bundleType) {
        this.bundleType = bundleType;
    }

    /** Profile put in meta.profile of the Bundle when this step creates it, or empty. */
    public String getBundleProfile() {
        return bundleProfile;
    }

    public void setBundleProfile(String bundleProfile) {
        this.bundleProfile = bundleProfile;
    }

    /** JavaScript expression of the name later steps give fhirRef() for this resource, e.g. 'patient'; empty for none. */
    public String getRefName() {
        return refName;
    }

    public void setRefName(String refName) {
        this.refName = refName;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    /** JavaScript expression of Bundle.entry.request.url; empty for the resource type (POST) or type/id. */
    public String getRequestUrl() {
        return requestUrl;
    }

    public void setRequestUrl(String requestUrl) {
        this.requestUrl = requestUrl;
    }

    /** JavaScript expression of Bundle.entry.request.ifNoneExist (conditional create), or empty. */
    public String getIfNoneExist() {
        return ifNoneExist;
    }

    public void setIfNoneExist(String ifNoneExist) {
        this.ifNoneExist = ifNoneExist;
    }

    /** JavaScript expression of the Authorization header for the terminology server, e.g. 'Bearer ' + $cfg('txToken'); empty for none. */
    public String getTerminologyAuthorization() {
        return terminologyAuthorization;
    }

    public void setTerminologyAuthorization(String terminologyAuthorization) {
        this.terminologyAuthorization = terminologyAuthorization;
    }

    /** How long answers of the terminology server are reused; 0 for not at all. */
    public int getTerminologyCacheSeconds() {
        return terminologyCacheSeconds;
    }

    public void setTerminologyCacheSeconds(int terminologyCacheSeconds) {
        this.terminologyCacheSeconds = terminologyCacheSeconds;
    }

    public int getTerminologyTimeoutSeconds() {
        return terminologyTimeoutSeconds;
    }

    public void setTerminologyTimeoutSeconds(int terminologyTimeoutSeconds) {
        this.terminologyTimeoutSeconds = terminologyTimeoutSeconds;
    }

    /** Base URL of the FHIR terminology server for ConceptMap/$translate, as the OIE server reaches it. */
    public String getTerminologyServer() {
        return terminologyServer;
    }

    public void setTerminologyServer(String terminologyServer) {
        this.terminologyServer = terminologyServer;
    }

    public String getMappings() {
        return mappings;
    }

    public void setMappings(String mappings) {
        this.mappings = mappings;
    }
}
