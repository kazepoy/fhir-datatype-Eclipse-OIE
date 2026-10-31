/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConceptMap/$translate for the FHIR Profile Mapper and scripts: on a FHIR terminology server, or,
 * when there is none (empty or "n/a", as TX_SERVER of matchbox), locally on the ConceptMaps of the
 * profile files and packages (FhirEngine#translate). The server call is plain Java (no HAPI): a GET,
 * the answer a Parameters resource read with {@link FhirJson}; its answers are cached for
 * {@link #CACHE_MILLIS}, so a feed does not ask the server for every message.
 */
public final class FhirTerminology {

    /** How long a translation (or the absence of one) is reused, by default. */
    public static final long CACHE_MILLIS = 5 * 60 * 1000L;
    private static final int CACHE_MAX = 10_000;
    private static final int TIMEOUT_SECONDS = 10;

    /** Matches that are no translation: R4 equivalence and R5 relationship. */
    private static final Set<String> NO_MATCH = Set.of("unmatched", "disjoint", "not-related-to");

    private static final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private static volatile HttpClient client;

    private FhirTerminology() {}

    private static final class Cached {
        final String value;
        final long time = System.nanoTime() / 1_000_000;

        Cached(String value) {
            this.value = value;
        }
    }

    /** Whether a terminology server setting means "none": translate locally. */
    public static boolean isLocal(String server) {
        return text(server).isEmpty() || "n/a".equalsIgnoreCase(text(server));
    }

    /**
     * Translates a code with ConceptMap/$translate.
     *
     * @param server base URL of the terminology server, e.g. http://hapi:8080/fhir; empty or "n/a"
     *            to translate locally with the ConceptMaps of {@code profiles}
     * @param profiles files and folders with profiles and packages, separated by ';' (local only)
     * @param conceptMap canonical URL of the ConceptMap
     * @param system code system of the code, or empty
     * @param targetSystem code system to translate to, or empty
     * @param fhirVersion R4, R4B or R5: R5 names the parameters sourceCode and targetSystem
     * @return the first match as JSON {"system", "code", "display"}, or null without a match
     * @throws Exception when the server cannot be reached or refuses the request, or the ConceptMap
     *             is not in the profiles
     */
    public static String translate(String server, String profiles, String conceptMap, String system, String code, String targetSystem, String fhirVersion) throws Exception {
        return translate(server, profiles, conceptMap, system, code, targetSystem, fhirVersion, "", (int) (CACHE_MILLIS / 1000), TIMEOUT_SECONDS);
    }

    /**
     * As {@link #translate(String, String, String, String, String, String, String)}, with the
     * Authorization header for the server (e.g. "Bearer ..."; empty for none), how long answers are
     * reused (0: not cached) and how long to wait for the server.
     */
    public static String translate(String server, String profiles, String conceptMap, String system, String code, String targetSystem, String fhirVersion,
            String authorization, int cacheSeconds, int timeoutSeconds) throws Exception {
        if (text(conceptMap).isEmpty()) {
            throw new IllegalArgumentException("No ConceptMap for ConceptMap/$translate.");
        }
        if (isLocal(server)) {
            FhirEngine engine = FhirEngineLoader.engine();
            String match = engine.translate(text(conceptMap), text(system), text(code), text(targetSystem), options(profiles, fhirVersion));
            FhirValidatorUsage.startPeriodicRelease(engine);
            return match;
        }
        String base = base(server);
        boolean r5 = "R5".equalsIgnoreCase(text(fhirVersion));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", text(conceptMap));
        params.put(r5 ? "sourceCode" : "code", text(code));
        if (!text(system).isEmpty()) {
            params.put("system", text(system));
        }
        if (!text(targetSystem).isEmpty()) {
            params.put(r5 ? "targetSystem" : "targetsystem", text(targetSystem));
        }
        String url = base + "/ConceptMap/$translate" + query(params);
        return cached(url, authorization, cacheSeconds, () -> firstMatch(get(url, authorization, timeoutSeconds)));
    }

    /**
     * CodeSystem/$lookup: the display of a code, or null when the code is not known. Without a server
     * (empty or "n/a") on the code systems of the specification and the profiles.
     */
    public static String lookup(String server, String profiles, String system, String code, String fhirVersion, String authorization) throws Exception {
        if (isLocal(server)) {
            FhirEngine engine = FhirEngineLoader.engine();
            String display = engine.lookup(text(system), text(code), options(profiles, fhirVersion));
            FhirValidatorUsage.startPeriodicRelease(engine);
            return display;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("system", text(system));
        params.put("code", text(code));
        String url = base(server) + "/CodeSystem/$lookup" + query(params);
        return cached(url, authorization, (int) (CACHE_MILLIS / 1000), () -> {
            Object answer;
            try {
                answer = get(url, authorization, TIMEOUT_SECONDS);
            } catch (NotFound e) {
                return null;
            }
            for (Object p : list(map(answer).get("parameter"))) {
                if ("display".equals(map(p).get("name"))) {
                    return value(map(p).get("valueString"));
                }
            }
            return null;
        });
    }

    /**
     * ValueSet/$validate-code: whether a code is in a value set. Without a server (empty or "n/a") on
     * the value sets of the specification and the profiles.
     */
    public static boolean validateCode(String server, String profiles, String valueSet, String system, String code, String fhirVersion, String authorization)
            throws Exception {
        if (isLocal(server)) {
            FhirEngine engine = FhirEngineLoader.engine();
            boolean ok = engine.validateCode(text(valueSet), text(system), text(code), options(profiles, fhirVersion));
            FhirValidatorUsage.startPeriodicRelease(engine);
            return ok;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", text(valueSet));
        if (!text(system).isEmpty()) {
            params.put("system", text(system));
        }
        params.put("code", text(code));
        String url = base(server) + "/ValueSet/$validate-code" + query(params);
        String result = cached(url, authorization, (int) (CACHE_MILLIS / 1000), () -> {
            for (Object p : list(map(get(url, authorization, TIMEOUT_SECONDS)).get("parameter"))) {
                if ("result".equals(map(p).get("name"))) {
                    return String.valueOf(Boolean.TRUE.equals(map(p).get("valueBoolean")));
                }
            }
            return "false";
        });
        return "true".equals(result);
    }

    private interface Call {
        String call() throws IOException;
    }

    /** A server answer, reused for cacheSeconds; the Authorization is part of the key. */
    private static String cached(String url, String authorization, int cacheSeconds, Call call) throws IOException {
        String key = url + "\n" + text(authorization).hashCode();
        Cached cached = cache.get(key);
        if (cacheSeconds > 0 && cached != null && System.nanoTime() / 1_000_000 - cached.time < cacheSeconds * 1000L) {
            return cached.value;
        }
        String result = call.call();
        if (cacheSeconds > 0) {
            if (cache.size() >= CACHE_MAX) {
                cache.clear();
            }
            cache.put(key, new Cached(result));
        }
        return result;
    }

    /** The base URL of a server, which must be http or https. */
    private static String base(String server) {
        String base = text(server).replaceAll("/+$", "");
        if (!base.matches("(?i)https?://.+")) {
            throw new IllegalArgumentException("The terminology server must be an http or https URL, or n/a: " + base);
        }
        return base;
    }

    /**
     * The ConceptMaps of a server (first 200), or without one (empty or "n/a") of the profiles, as a
     * JSON array of objects with url, name, title, source and target.
     */
    public static String listConceptMaps(String server, String profiles, String fhirVersion) throws Exception {
        if (isLocal(server)) {
            FhirEngine engine = FhirEngineLoader.engine();
            String list = engine.listConceptMaps(options(profiles, fhirVersion));
            FhirValidatorUsage.startPeriodicRelease(engine);
            return list;
        }
        Object bundle = get(base(server) + "/ConceptMap?_summary=true&_count=200", "", TIMEOUT_SECONDS);
        List<Object> maps = new ArrayList<>();
        for (Object entry : list(map(bundle).get("entry"))) {
            Map<?, ?> resource = map(map(entry).get("resource"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", value(resource.get("url")));
            m.put("name", value(resource.get("name")));
            m.put("title", value(resource.get("title")));
            m.put("source", value(first(resource, "sourceUri", "sourceCanonical", "sourceScopeUri", "sourceScopeCanonical")));
            m.put("target", value(first(resource, "targetUri", "targetCanonical", "targetScopeUri", "targetScopeCanonical")));
            maps.add(m);
        }
        return FhirJson.write(maps);
    }

    /** Forgets the cached translations; for tests, and after changing ConceptMaps on the server. */
    public static void clearCache() {
        cache.clear();
    }

    /** The first usable match of a $translate answer (Parameters), or null. */
    static String firstMatch(Object answer) {
        Map<?, ?> parameters = map(answer);
        for (Object p : list(parameters.get("parameter"))) {
            Map<?, ?> parameter = map(p);
            if ("result".equals(parameter.get("name")) && Boolean.FALSE.equals(parameter.get("valueBoolean"))) {
                return null;
            }
        }
        for (Object p : list(parameters.get("parameter"))) {
            Map<?, ?> parameter = map(p);
            if (!"match".equals(parameter.get("name"))) {
                continue;
            }
            Map<?, ?> concept = null;
            String equivalence = "";
            for (Object part : list(parameter.get("part"))) {
                Map<?, ?> pp = map(part);
                if ("concept".equals(pp.get("name"))) {
                    concept = map(pp.get("valueCoding"));
                } else if ("equivalence".equals(pp.get("name")) || "relationship".equals(pp.get("name"))) {
                    equivalence = value(pp.get("valueCode"));
                }
            }
            if (concept == null || NO_MATCH.contains(equivalence) || value(concept.get("code")).isEmpty()) {
                continue;
            }
            Map<String, Object> coding = new LinkedHashMap<>();
            for (String field : new String[] { "system", "code", "display" }) {
                if (!value(concept.get(field)).isEmpty()) {
                    coding.put(field, value(concept.get(field)));
                }
            }
            return FhirJson.write(coding);
        }
        return null;
    }

    /** HTTP 404: an unknown code for $lookup. */
    private static final class NotFound extends IOException {
        private static final long serialVersionUID = 1L;

        NotFound(String message) {
            super(message);
        }
    }

    private static Object get(String url, String authorization, int timeoutSeconds) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : TIMEOUT_SECONDS))
                .header("Accept", "application/fhir+json");
        if (!text(authorization).isEmpty()) {
            builder.header("Authorization", text(authorization));
        }
        HttpRequest request = builder.GET().build();
        HttpResponse<String> response;
        try {
            response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + url, e);
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException("Cannot reach the terminology server: " + url + " (" + e.getMessage() + ")", e);
        }
        Object body;
        try {
            body = FhirJson.parse(response.body());
        } catch (IllegalArgumentException e) {
            body = null;
        }
        if (response.statusCode() == 404) {
            throw new NotFound("The terminology server answered HTTP 404 to " + url + diagnostics(body));
        }
        if (response.statusCode() >= 300) {
            throw new IOException("The terminology server answered HTTP " + response.statusCode() + " to " + url + diagnostics(body));
        }
        if (body == null) {
            throw new IOException("The terminology server did not answer FHIR JSON to " + url);
        }
        return body;
    }

    /** The diagnostics of an OperationOutcome, if the answer is one. */
    private static String diagnostics(Object body) {
        StringBuilder sb = new StringBuilder();
        for (Object issue : list(map(body).get("issue"))) {
            String text = value(map(issue).get("diagnostics"));
            if (!text.isEmpty()) {
                sb.append(sb.length() == 0 ? ": " : "; ").append(text);
            }
        }
        return sb.toString();
    }

    private static HttpClient client() {
        if (client == null) {
            synchronized (FhirTerminology.class) {
                if (client == null) {
                    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).followRedirects(HttpClient.Redirect.NORMAL).build();
                }
            }
        }
        return client;
    }

    private static Map<String, String> options(String profiles, String fhirVersion) {
        FhirSerializationProperties properties = new FhirSerializationProperties();
        String version = text(fhirVersion).toUpperCase();
        try {
            properties.setFhirVersion(version.isEmpty() ? FhirSerializationProperties.FhirVersion.R4 : FhirSerializationProperties.FhirVersion.valueOf(version));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("FHIR version " + fhirVersion + " is not supported. Use R4, R4B or R5.");
        }
        properties.setProfiles(text(profiles));
        return properties.engineOptions();
    }

    private static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sb.length() == 0 ? '?' : '&').append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : List.of();
    }

    private static String value(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }
}
