/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.RemoteTerminologyServiceValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.UnknownCodeSystemWarningValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.hl7.fhir.utilities.npm.NpmPackage;

import com.mirth.connect.plugins.datatypes.fhir.FhirEngine;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * HAPI FHIR behind {@link FhirEngine}. A validator is expensive to build (the specification's
 * StructureDefinitions and the profiles), so one is kept per set of options; FhirValidator is thread
 * safe. The specification itself is shared per version ({@link LazySpecificationSupport}).
 */
public final class HapiFhirEngine implements FhirEngine {

    /** Conformance resources taken from profile files and packages; examples and the like are skipped. */
    private static final Set<String> CONFORMANCE_TYPES = Set.of("StructureDefinition", "ValueSet", "CodeSystem", "ConceptMap", "NamingSystem");

    private final Map<String, CachedValidator> validators = new ConcurrentHashMap<>();
    private final Map<FhirVersionEnum, FhirContext> validationContexts = new HashMap<>();
    private final Map<FhirVersionEnum, LazySpecificationSupport> specifications = new HashMap<>();
    /** The ConceptMaps of the profile files and packages, per FHIR version and profiles, for local $translate. */
    private final Map<String, CachedConceptMaps> conceptMaps = new ConcurrentHashMap<>();

    @Override
    public List<Map<String, String>> validate(String resource, Map<String, String> options) throws Exception {
        return withEngineClassLoader(() -> {
            FhirValidator validator = validator(options);
            ValidationOptions validationOptions = new ValidationOptions();
            String required = options.getOrDefault(REQUIRED_PROFILE, "").trim();
            if (!required.isEmpty()) {
                validationOptions.addProfile(required);
            }
            ValidationResult result = validator.validateWithResult(resource, validationOptions);
            List<Map<String, String>> issues = new ArrayList<>();
            for (SingleValidationMessage m : result.getMessages()) {
                Map<String, String> issue = new LinkedHashMap<>();
                issue.put(SEVERITY, m.getSeverity() == null ? "error" : m.getSeverity().getCode());
                issue.put(LOCATION, m.getLocationString() == null ? "" : m.getLocationString());
                issue.put(LINE, m.getLocationLine() == null ? "" : String.valueOf(m.getLocationLine()));
                issue.put(COLUMN, m.getLocationCol() == null ? "" : String.valueOf(m.getLocationCol()));
                issue.put(MESSAGE, m.getMessage() == null ? "" : m.getMessage());
                issue.put(MESSAGE_ID, m.getMessageId() == null ? "" : m.getMessageId());
                issues.add(issue);
            }
            return issues;
        });
    }

    @Override
    public String toJson(String xml, String fhirVersion, boolean pretty) throws Exception {
        return withEngineClassLoader(() -> {
            FhirContext ctx = context(fhirVersion);
            IBaseResource resource = strict(ctx.newXmlParser()).parseResource(xml);
            return ctx.newJsonParser().setPrettyPrint(pretty).encodeResourceToString(resource);
        });
    }

    @Override
    public String toXml(String json, String fhirVersion, boolean pretty) throws Exception {
        return withEngineClassLoader(() -> {
            FhirContext ctx = context(fhirVersion);
            IBaseResource resource = strict(ctx.newJsonParser()).parseResource(json);
            return ctx.newXmlParser().setPrettyPrint(pretty).encodeResourceToString(resource);
        });
    }

    @Override
    public String reorderXml(String xml, String fhirVersion, boolean pretty) throws Exception {
        return withEngineClassLoader(() -> {
            FhirContext ctx = context(fhirVersion);
            IBaseResource resource = strict(ctx.newXmlParser()).parseResource(xml);
            return ctx.newXmlParser().setPrettyPrint(pretty).encodeResourceToString(resource);
        });
    }

    @Override
    public String reorderJson(String json, String fhirVersion, boolean pretty) throws Exception {
        return withEngineClassLoader(() -> {
            FhirContext ctx = context(fhirVersion);
            IBaseResource resource = strict(ctx.newJsonParser()).parseResource(json);
            return ctx.newJsonParser().setPrettyPrint(pretty).encodeResourceToString(resource);
        });
    }

    @Override
    public String listProfiles(Map<String, String> options) throws Exception {
        return withEngineClassLoader(() -> {
            CachedValidator cached = cached(options);
            if (cached.profiles == null) {
                return "[]";
            }
            FhirTerser terser = cached.ctx.newTerser();
            Map<String, String> sorted = new TreeMap<>();
            for (IBaseResource sd : cached.profiles.fetchAllStructureDefinitions()) {
                String url = terser.getSinglePrimitiveValueOrNull(sd, "url");
                if (url == null || !"resource".equals(terser.getSinglePrimitiveValueOrNull(sd, "kind"))
                        || !"constraint".equals(terser.getSinglePrimitiveValueOrNull(sd, "derivation"))) {
                    continue;
                }
                StringBuilder sb = new StringBuilder("{");
                field(sb, "url", url).append(',');
                field(sb, "name", terser.getSinglePrimitiveValueOrNull(sd, "name")).append(',');
                field(sb, "title", terser.getSinglePrimitiveValueOrNull(sd, "title")).append(',');
                field(sb, "type", terser.getSinglePrimitiveValueOrNull(sd, "type")).append('}');
                sorted.put(url, sb.toString());
            }
            return "[" + String.join(",", sorted.values()) + "]";
        });
    }

    @Override
    public String structureDefinition(String url, Map<String, String> options) throws Exception {
        return withEngineClassLoader(() -> {
            CachedValidator cached = cached(options);
            String canonical = url.trim().contains("/") ? url.trim() : "http://hl7.org/fhir/StructureDefinition/" + url.trim();
            IBaseResource sd = cached.chain.fetchStructureDefinition(canonical);
            if (sd == null) {
                throw new IllegalArgumentException("StructureDefinition " + canonical + " not found in the specification or the profile packages.");
            }
            FhirTerser terser = cached.ctx.newTerser();
            if (terser.getValues(sd, "snapshot.element").isEmpty()) {
                IBaseResource copy = cached.ctx.newTerser().clone(sd);
                sd = cached.chain.generateSnapshot(new ValidationSupportContext(cached.chain), copy, canonical, null, terser.getSinglePrimitiveValueOrNull(sd, "name"));
                if (sd == null || terser.getValues(sd, "snapshot.element").isEmpty()) {
                    throw new IllegalArgumentException("Cannot generate the snapshot of " + canonical + ": its base is not available.");
                }
            }
            return withOriginalBases(cached, cached.ctx.newJsonParser().encodeResourceToString(sd));
        });
    }

    // ------------------------------------------------------------------ local $translate

    private static final class CachedConceptMaps {
        /** By url, and by url|version. */
        final Map<String, JsonNode> byUrl;
        volatile long lastUsed = System.currentTimeMillis();

        CachedConceptMaps(Map<String, JsonNode> byUrl) {
            this.byUrl = byUrl;
        }
    }

    /** Matches that are no translation: R4 equivalence and R5 relationship. */
    private static final Set<String> NO_MATCH = Set.of("unmatched", "disjoint", "not-related-to");

    @Override
    public String listConceptMaps(Map<String, String> options) throws Exception {
        return withEngineClassLoader(() -> {
            ObjectMapper mapper = exactMapper();
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (JsonNode map : cachedConceptMaps(options).byUrl.values()) {
                String url = map.path("url").asText("");
                if (url.isEmpty() || sorted.containsKey(url)) {
                    continue;
                }
                ObjectNode item = mapper.createObjectNode();
                item.put("url", url);
                item.put("name", map.path("name").asText(""));
                item.put("title", map.path("title").asText(""));
                item.put("source", firstText(map, "sourceUri", "sourceCanonical", "sourceScopeUri", "sourceScopeCanonical"));
                item.put("target", firstText(map, "targetUri", "targetCanonical", "targetScopeUri", "targetScopeCanonical"));
                sorted.put(url, item);
            }
            return mapper.writeValueAsString(sorted.values());
        });
    }

    @Override
    public String translate(String conceptMap, String system, String code, String targetSystem, Map<String, String> options) throws Exception {
        return withEngineClassLoader(() -> {
            CachedConceptMaps maps = cachedConceptMaps(options);
            JsonNode map = maps.byUrl.get(conceptMap.trim());
            if (map == null) {
                throw new IllegalArgumentException("ConceptMap " + conceptMap.trim() + " is not in the profile packages (" + options.getOrDefault(PROFILES, "") + ").");
            }
            JsonNode match = translate(maps, map, text(system), text(code), text(targetSystem), 0);
            return match == null ? null : exactMapper().writeValueAsString(match);
        });
    }

    /**
     * $translate on a loaded ConceptMap, as a terminology server does it: the groups of the source
     * system (all without one) and the target system (all without one); the first target of the code
     * that is a match; otherwise the unmapped rule of the first such group (provided or
     * use-source-code, fixed, other-map). Both R4 (equivalence) and R5 (relationship).
     */
    private static JsonNode translate(CachedConceptMaps maps, JsonNode map, String system, String code, String targetSystem, int depth) {
        List<JsonNode> groups = new ArrayList<>();
        for (JsonNode group : map.path("group")) {
            if ((system.isEmpty() || system.equals(unversioned(group.path("source").asText("")))) && (targetSystem.isEmpty() || targetSystem.equals(unversioned(group.path("target").asText(""))))) {
                groups.add(group);
            }
        }
        for (JsonNode group : groups) {
            for (JsonNode element : group.path("element")) {
                if (!code.equals(element.path("code").asText(null))) {
                    continue;
                }
                for (JsonNode target : element.path("target")) {
                    String relation = target.has("relationship") ? target.path("relationship").asText("") : target.path("equivalence").asText("");
                    if (!NO_MATCH.contains(relation) && !target.path("code").asText("").isEmpty()) {
                        return coding(unversioned(group.path("target").asText("")), target.path("code").asText(), target.path("display").asText(""));
                    }
                }
            }
        }
        for (JsonNode group : groups) {
            JsonNode unmapped = group.path("unmapped");
            String mode = unmapped.path("mode").asText("");
            String targetGroupSystem = unversioned(group.path("target").asText(""));
            if ("provided".equals(mode) || "use-source-code".equals(mode)) {
                return coding(targetGroupSystem, code, "");
            }
            if ("fixed".equals(mode) && !unmapped.path("code").asText("").isEmpty()) {
                return coding(targetGroupSystem, unmapped.path("code").asText(), unmapped.path("display").asText(""));
            }
            if ("other-map".equals(mode) && depth < 5) {
                String other = unmapped.has("otherMap") ? unmapped.path("otherMap").asText("") : unmapped.path("url").asText("");
                JsonNode otherMap = maps.byUrl.get(other);
                if (otherMap != null) {
                    JsonNode match = translate(maps, otherMap, system, code, targetSystem, depth + 1);
                    if (match != null) {
                        return match;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String lookup(String system, String code, Map<String, String> options) throws Exception {
        return withEngineClassLoader(() -> {
            ValidationSupportChain chain = cached(options).chain;
            IValidationSupport.LookupCodeResult result = chain.lookupCode(new ValidationSupportContext(chain), new LookupCodeRequest(text(system), text(code)));
            return result != null && result.isFound() ? result.getCodeDisplay() : null;
        });
    }

    @Override
    public boolean validateCode(String valueSet, String system, String code, Map<String, String> options) throws Exception {
        return withEngineClassLoader(() -> {
            ValidationSupportChain chain = cached(options).chain;
            IValidationSupport.CodeValidationResult result = chain.validateCode(new ValidationSupportContext(chain), new ConceptValidationOptions(),
                    text(system).isEmpty() ? null : text(system), text(code), null, text(valueSet));
            return result != null && result.isOk();
        });
    }

    private static JsonNode coding(String system, String code, String display) {
        ObjectNode coding = JsonNodeFactory.instance.objectNode();
        if (!system.isEmpty()) {
            coding.put("system", system);
        }
        coding.put("code", code);
        if (!display.isEmpty()) {
            coding.put("display", display);
        }
        return coding;
    }

    private CachedConceptMaps cachedConceptMaps(Map<String, String> options) {
        FhirVersionEnum version = version(options.get(FHIR_VERSION));
        String paths = options.getOrDefault(PROFILES, "").trim();
        CachedConceptMaps cached = conceptMaps.computeIfAbsent(version.name() + "|" + paths, k -> new CachedConceptMaps(loadConceptMaps(version, paths)));
        cached.lastUsed = System.currentTimeMillis();
        return cached;
    }

    /** The ConceptMaps of profile files and packages; only they are read, as JSON. */
    private static Map<String, JsonNode> loadConceptMaps(FhirVersionEnum version, String paths) {
        List<File> files = new ArrayList<>();
        for (String path : paths.split(";")) {
            if (!path.trim().isEmpty()) {
                collect(new File(path.trim()), files);
            }
        }
        ObjectMapper mapper = exactMapper();
        Map<String, JsonNode> byUrl = new HashMap<>();
        for (File file : files) {
            String name = file.getName().toLowerCase();
            try {
                if (name.endsWith(".tgz") || name.endsWith(".tar.gz")) {
                    NpmPackage npm;
                    try (InputStream in = new FileInputStream(file)) {
                        npm = NpmPackage.fromPackage(in);
                    }
                    for (String resourceFile : npm.listResources("ConceptMap")) {
                        try (InputStream in = npm.loadResource(resourceFile)) {
                            addConceptMap(byUrl, mapper.readTree(in));
                        }
                    }
                } else if (name.endsWith(".json")) {
                    addConceptMap(byUrl, mapper.readTree(file));
                } else {
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    if (text.contains("<ConceptMap")) {
                        IBaseResource resource = FhirContext.forCached(version).newXmlParser().parseResource(text);
                        addConceptMap(byUrl, mapper.readTree(FhirContext.forCached(version).newJsonParser().encodeResourceToString(resource)));
                    }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot load profile file " + file.getAbsolutePath() + ": " + e.getMessage(), e);
            }
        }
        return byUrl;
    }

    private static void addConceptMap(Map<String, JsonNode> byUrl, JsonNode resource) {
        if (resource == null || !"ConceptMap".equals(resource.path("resourceType").asText()) || !resource.hasNonNull("url")) {
            return;
        }
        String url = resource.get("url").asText();
        byUrl.putIfAbsent(url, resource);
        if (resource.hasNonNull("version")) {
            byUrl.putIfAbsent(url + "|" + resource.get("version").asText(), resource);
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return node.get(field).asText();
            }
        }
        return "";
    }

    private static String unversioned(String canonical) {
        int bar = canonical.indexOf('|');
        return bar < 0 ? canonical : canonical.substring(0, bar);
    }

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }

    private static final String FHIR_BASE = "http://hl7.org/fhir/StructureDefinition/";

    private static ObjectMapper exactMapper() {
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    }

    /**
     * Snapshots of profiles can have base.max "*" where the specification has 1 (Observation.status),
     * both generated by HAPI and as published in packages. The profile mapper needs base.max to know
     * which elements are JSON arrays, so base.min and base.max are taken from the element that
     * base.path names, in the StructureDefinition of the specification.
     */
    private static String withOriginalBases(CachedValidator cached, String json) throws IOException {
        ObjectMapper mapper = exactMapper();
        JsonNode sd = mapper.readTree(json);
        Map<String, Map<String, JsonNode>> definitions = new HashMap<>();
        for (JsonNode element : sd.path("snapshot").path("element")) {
            JsonNode base = element.get("base");
            if (!(base instanceof ObjectNode) || !base.hasNonNull("path")) {
                continue;
            }
            String path = base.get("path").asText();
            String type = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
            Map<String, JsonNode> byPath = definitions.computeIfAbsent(type, t -> elementsByPath(cached, mapper, t));
            JsonNode original = byPath.get(path);
            if (original == null) {
                continue;
            }
            JsonNode originalBase = original.has("base") ? original.get("base") : original;
            for (String field : new String[] { "min", "max" }) {
                if (originalBase.has(field)) {
                    ((ObjectNode) base).set(field, originalBase.get(field));
                }
            }
        }
        return mapper.writeValueAsString(sd);
    }

    /** The snapshot elements of a type of the specification by path (slices left out), or none. */
    private static Map<String, JsonNode> elementsByPath(CachedValidator cached, ObjectMapper mapper, String type) {
        Map<String, JsonNode> byPath = new HashMap<>();
        IBaseResource definition = cached.chain.fetchStructureDefinition(FHIR_BASE + type);
        if (definition == null) {
            return byPath;
        }
        try {
            for (JsonNode element : mapper.readTree(cached.ctx.newJsonParser().encodeResourceToString(definition)).path("snapshot").path("element")) {
                if (element.hasNonNull("path") && !element.has("sliceName")) {
                    byPath.putIfAbsent(element.get("path").asText(), element);
                }
            }
        } catch (IOException e) {
            // no bases to take over
        }
        return byPath;
    }

    private static StringBuilder field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":");
        if (value == null) {
            return sb.append("null");
        }
        sb.append('"');
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"');
    }

    @Override
    public void warmUp(Map<String, String> options) {
        try {
            withEngineClassLoader(() -> validator(options));
        } catch (Exception e) {
            // Reported again, with the details, when the first message is validated.
        }
    }

    @Override
    public synchronized void retainValidators(List<Map<String, String>> inUse, long idleMillis) {
        Set<String> keep = new HashSet<>();
        for (Map<String, String> options : inUse) {
            keep.add(key(options));
        }
        long now = System.currentTimeMillis();
        validators.entrySet().removeIf(e -> !keep.contains(e.getKey()) && now - e.getValue().lastUsed > idleMillis);
        // Read again after a deploy (idleMillis 0), so changed ConceptMap files are used.
        conceptMaps.entrySet().removeIf(e -> now - e.getValue().lastUsed > idleMillis);
        Set<FhirVersionEnum> needed = new HashSet<>();
        for (CachedValidator cached : validators.values()) {
            needed.add(cached.version);
        }
        for (FhirVersionEnum version : new HashSet<>(validationContexts.keySet())) {
            if (!needed.contains(version)) {
                validationContexts.remove(version);
                specifications.remove(version);
                // Not filled by the engine any more (LazySpecificationSupport), but cleared in case anything did.
                releaseSpecification(version);
            }
        }
    }

    /**
     * HAPI's DefaultProfileValidationSupport keeps the specification of each version in a static
     * map for the life of its class loader (R5: about 520 MB), with no API to drop it. Since 1.5.1
     * the engine uses LazySpecificationSupport and never fills it; this is a safety net, in case
     * something in HAPI builds one after all. The map is this plugin's own: HAPI is loaded in the
     * plugin's class loader.
     */
    static boolean releaseSpecification(FhirVersionEnum version) {
        try {
            Map<?, ?> implementations = hapiStaticSpecifications();
            synchronized (implementations) {
                return implementations.remove(version) != null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A HAPI FHIR without this field: the specification stays loaded, as before 1.5.0.
            return false;
        }
    }

    /** DefaultProfileValidationSupport.ourImplementations; package-private for tests. */
    static Map<?, ?> hapiStaticSpecifications() throws ReflectiveOperationException {
        Field field = DefaultProfileValidationSupport.class.getDeclaredField("ourImplementations");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    /** The cache keys of the validators, for tests. */
    Set<String> validatorKeys() {
        return new HashSet<>(validators.keySet());
    }

    /** The FHIR versions whose specification is loaded for validation, for tests. */
    synchronized Set<FhirVersionEnum> loadedVersions() {
        return new HashSet<>(validationContexts.keySet());
    }

    // ------------------------------------------------------------------ validator

    private static final class CachedValidator {
        final FhirValidator validator;
        final FhirVersionEnum version;
        final FhirContext ctx;
        /** What the validator resolves profiles and types with; also used to look them up for the profile mapper. */
        final ValidationSupportChain chain;
        /** The profile files and packages, or null without any. */
        final PrePopulatedValidationSupport profiles;
        volatile long lastUsed = System.currentTimeMillis();

        CachedValidator(FhirValidator validator, FhirVersionEnum version, FhirContext ctx, ValidationSupportChain chain, PrePopulatedValidationSupport profiles) {
            this.validator = validator;
            this.version = version;
            this.ctx = ctx;
            this.chain = chain;
            this.profiles = profiles;
        }
    }

    static String key(Map<String, String> options) {
        Map<String, String> key = new TreeMap<>(options);
        key.remove(REQUIRED_PROFILE); // passed per call, not part of the validator
        key.put(FHIR_VERSION, version(options.get(FHIR_VERSION)).name());
        return key.toString();
    }

    private FhirValidator validator(Map<String, String> options) {
        return cached(options).validator;
    }

    private CachedValidator cached(Map<String, String> options) {
        CachedValidator cached = validators.computeIfAbsent(key(options), k -> build(options));
        cached.lastUsed = System.currentTimeMillis();
        return cached;
    }

    /**
     * The specification of a version is loaded into the context the validator uses, and stays there
     * as long as the context lives. Validators therefore get a context of this engine instead of
     * the static cached one, so it can be released with them.
     */
    private synchronized FhirContext validationContext(FhirVersionEnum version) {
        return validationContexts.computeIfAbsent(version, FhirContext::forVersion);
    }

    /**
     * One specification per version, shared by its validators like HAPI's static copy was. It is
     * also the context's own support, so HAPI never builds a DefaultProfileValidationSupport (and
     * its full, static copy of the specification) behind our back.
     */
    private synchronized LazySpecificationSupport specification(FhirVersionEnum version, FhirContext ctx) {
        return specifications.computeIfAbsent(version, v -> {
            LazySpecificationSupport spec = LazySpecificationSupport.load(ctx);
            ctx.setValidationSupport(spec);
            return spec;
        });
    }

    /** The loaded specification of a version, or null; for tests. */
    synchronized LazySpecificationSupport loadedSpecification(FhirVersionEnum version) {
        return specifications.get(version);
    }

    private CachedValidator build(Map<String, String> options) {
        FhirVersionEnum version = version(options.get(FHIR_VERSION));
        FhirContext ctx = validationContext(version);

        List<IValidationSupport> supports = new ArrayList<>();
        PrePopulatedValidationSupport profiles = loadProfiles(ctx, options.getOrDefault(PROFILES, ""));
        if (profiles != null) {
            supports.add(profiles);
        }
        supports.add(specification(version, ctx));
        supports.add(new CommonCodeSystemsTerminologyService(ctx));
        supports.add(new InMemoryTerminologyServerValidationSupport(ctx));
        supports.add(new SnapshotGeneratingValidationSupport(ctx));
        String terminologyServer = options.getOrDefault(TERMINOLOGY_SERVER, "").trim();
        if (!terminologyServer.isEmpty()) {
            supports.add(new RemoteTerminologyServiceValidationSupport(ctx, terminologyServer));
        }
        UnknownCodeSystemWarningValidationSupport unknown = new UnknownCodeSystemWarningValidationSupport(ctx);
        unknown.setNonExistentCodeSystemSeverity(severity(options.getOrDefault(UNKNOWN_CODE_SYSTEMS, "warning")));
        supports.add(unknown);

        ValidationSupportChain chain = new ValidationSupportChain(supports);
        FhirInstanceValidator module = new FhirInstanceValidator(chain);
        module.setAnyExtensionsAllowed(!"false".equalsIgnoreCase(options.get(ANY_EXTENSIONS_ALLOWED)));
        module.setErrorForUnknownProfiles(true);
        // Recommendations such as dom-6 ("should have narrative") would flag nearly every message.
        module.setBestPracticeWarningLevel(BestPracticeWarningLevel.Ignore);

        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(module);
        return new CachedValidator(validator, version, ctx, chain, profiles);
    }

    /**
     * Profiles from files and folders: NPM packages (.tgz, as published on packages.fhir.org, e.g.
     * nictiz.fhir.nl.r4.zib2020) and single conformance resources in JSON or XML.
     */
    private static PrePopulatedValidationSupport loadProfiles(FhirContext ctx, String paths) {
        List<File> files = new ArrayList<>();
        for (String path : paths.split(";")) {
            if (!path.trim().isEmpty()) {
                collect(new File(path.trim()), files);
            }
        }
        if (files.isEmpty()) {
            return null;
        }
        PrePopulatedValidationSupport support = new PrePopulatedValidationSupport(ctx);
        for (File file : files) {
            String name = file.getName().toLowerCase();
            try {
                if (name.endsWith(".tgz") || name.endsWith(".tar.gz")) {
                    loadPackage(ctx, file, support);
                } else {
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    IParser parser = name.endsWith(".xml") ? ctx.newXmlParser() : ctx.newJsonParser();
                    IBaseResource resource = parser.parseResource(text);
                    if ("StructureDefinition".equals(resource.fhirType())) {
                        checkVersion(ctx, ctx.newTerser().getSinglePrimitiveValueOrNull(resource, "fhirVersion"), "StructureDefinition " + file.getName());
                    }
                    add(support, resource);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot load profile file " + file.getAbsolutePath() + ": " + e.getMessage(), e);
            }
        }
        return support;
    }

    private static void loadPackage(FhirContext ctx, File file, PrePopulatedValidationSupport support) throws IOException {
        NpmPackage npm;
        try (InputStream in = new FileInputStream(file)) {
            npm = NpmPackage.fromPackage(in);
        }
        checkVersion(ctx, npm.fhirVersionList(), "Package " + npm.vid());
        IParser parser = ctx.newJsonParser();
        for (String resourceFile : npm.listResources(CONFORMANCE_TYPES.toArray(new String[0]))) {
            try (InputStream in = npm.loadResource(resourceFile)) {
                add(support, parser.parseResource(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
    }

    private static void add(PrePopulatedValidationSupport support, IBaseResource resource) {
        if (resource != null && CONFORMANCE_TYPES.contains(resource.fhirType())) {
            support.addResource(resource);
        }
    }

    private static void collect(File file, List<File> files) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                Arrays.sort(children);
                for (File child : children) {
                    collect(child, files);
                }
            }
        } else if (file.isFile()) {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".tgz") || name.endsWith(".tar.gz") || name.endsWith(".json") || name.endsWith(".xml")) {
                files.add(file);
            }
        } else {
            throw new IllegalArgumentException("Profile path not found: " + file.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** For parsing and encoding only: the cached context stays small as long as nothing validates with it. */
    private static FhirContext context(String fhirVersion) {
        return FhirContext.forCached(version(fhirVersion));
    }

    /** Each version needs its structures and validation-resources libraries in lib/. */
    private static FhirVersionEnum version(String fhirVersion) {
        String version = fhirVersion == null ? "" : fhirVersion.trim().toUpperCase();
        switch (version) {
            case "":
            case "R4":
                return FhirVersionEnum.R4;
            case "R4B":
                return FhirVersionEnum.R4B;
            case "R5":
                return FhirVersionEnum.R5;
            default:
                throw new IllegalArgumentException("FHIR version " + fhirVersion + " is not supported. Use R4, R4B or R5.");
        }
    }

    /**
     * Throws when profiles declare another FHIR version than the channel's: HAPI would otherwise load
     * them and report confusing errors (or none) for every message. Compares major.minor, so 4.0.0
     * and 4.0.1 are both R4.
     */
    private static void checkVersion(FhirContext ctx, String declared, String what) {
        if (declared == null || declared.trim().isEmpty()) {
            return;
        }
        FhirVersionEnum expected = ctx.getVersion().getVersion();
        for (String version : declared.split(",")) {
            if (majorMinor(version).equals(majorMinor(expected.getFhirVersionString()))) {
                return;
            }
        }
        throw new IllegalArgumentException(what + " is for FHIR " + declared.trim() + ", but FHIR Version is " + expected.name() + " ("
                + expected.getFhirVersionString() + "). Use profiles for " + expected.name() + ", or set FHIR Version to the version of the profiles.");
    }

    private static String majorMinor(String version) {
        String[] parts = version.trim().split("\\.");
        return parts.length < 2 ? version.trim() : parts[0] + "." + parts[1];
    }

    private static IParser strict(IParser parser) {
        return parser.setParserErrorHandler(new StrictErrorHandler());
    }

    private static IValidationSupport.IssueSeverity severity(String value) {
        switch (value.toLowerCase()) {
            case "error":
                return IValidationSupport.IssueSeverity.ERROR;
            case "information":
                return IValidationSupport.IssueSeverity.INFORMATION;
            default:
                return IValidationSupport.IssueSeverity.WARNING;
        }
    }

    /** HAPI finds its XML and JSON implementations through the context class loader. */
    private <T> T withEngineClassLoader(Callable<T> work) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(getClass().getClassLoader());
        try {
            return work.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
