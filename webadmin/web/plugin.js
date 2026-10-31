// FHIR data type - properties panel for the web administrator (equivalent of the Swing
// DataTypeClientPlugin properties). Same shape as the built-in data type plugins.
const PKG = "com.mirth.connect.plugins.datatypes.fhir";

const bool = (key, label, def, hint) => ({ key, label, type: "checkbox", default: def, hint });
const text = (key, label, def, hint) => ({ key, label, type: "text", default: def, hint });
const opt = (key, label, values, def, hint) => ({ key, label, type: "select", options: values.map((value) => ({ value, label: value })), default: def, hint });
const code = (key, label, def, hint) => ({ key, label, type: "code", default: def, hint });

const BATCH_SCRIPT_HINT =
  "JavaScript that splits the batch and returns the next message. Has access to 'reader' (a Java BufferedReader); return null/empty to signal end of input. Only used when Process Batch is enabled in the connector.";

const DEF = {
  name: "FHIR",
  label: "FHIR",
  order: 73,
  propertiesClass: `${PKG}.FhirDataTypeProperties`,
  groups: [
    {
      key: "serializationProperties",
      label: "Serialization",
      class: `${PKG}.FhirSerializationProperties`,
      fields: [
        opt("fhirVersion", "FHIR Version", ["R4", "R4B", "R5"], "R4", "The FHIR version of the messages: R4 (4.0.1), R4B (4.3.0) or R5 (5.0.0). Used for validation and for converting between JSON and XML. Profile packages must be for the same version."),
        opt("transformerFormat", "Transformer Format", ["XML", "JSON"], "XML", "XML: the transformer works on FHIR XML with E4X (msg.name.family.@value). JSON: msg is a JavaScript object of the FHIR JSON (msg.name[0].family), and the outbound message is made from it. With JSON, decimals lose trailing zeros (1.50 becomes 1.5). On outbound properties it sets the format of the outbound template (tmp)."),
        bool("stripNamespaces", "Strip Namespaces", true, "If checked, the FHIR namespace (http://hl7.org/fhir) is removed from the XML the transformer works on, so you can write msg.name.family.@value. It is put back when the message leaves the transformer. The XHTML namespace of narratives (text.div) is kept."),
        opt("outputFormat", "Output Format", ["JSON", "XML"], "JSON", "The format of the outbound message (the encoded data): FHIR JSON or FHIR XML. Messages come in as either; the transformer always works on FHIR XML. After a transformer the FHIR engine writes the message in the element order of the specification."),
        bool("prettyPrint", "Pretty Print", true, "If checked, outbound JSON and XML created by the FHIR engine are indented."),
        bool("validateInbound", "Validate Inbound", true, "If checked, every inbound message is validated against the FHIR specification and the profiles below: structure, data types, cardinality, required elements, value set bindings and invariants."),
        bool("validateOutbound", "Validate Outbound", false, "If checked, the outbound message is validated too, after the transformer. An invalid outbound message is always rejected."),
        opt("invalidMessages", "Invalid Messages", ["Reject", "Accept"], "Reject", "Reject: an invalid inbound message gets status ERROR, with the validation issues as the error. Accept: it is processed normally, and the connector map gets fhirValid (true/false), fhirIssueCount and fhirIssues (the issues as text)."),
        opt("failOn", "Invalid When", ["Errors", "Warnings"], "Errors", "Errors: a message is invalid when validation finds errors. Warnings: warnings make it invalid too."),
        text("profiles", "Profile Packages", "", "Files or folders on the server with profiles, separated by ';'. FHIR packages (.tgz, e.g. nictiz.fhir.nl.r4.zib2020 with its dependencies) and StructureDefinition, ValueSet and CodeSystem files (.json or .xml). A resource is validated against the profiles in its meta.profile."),
        text("requiredProfile", "Required Profile", "", "Canonical URL of a profile every message must conform to, whatever its meta.profile says. Leave empty to only use meta.profile."),
        opt("unknownCodeSystems", "Unknown Code Systems", ["Warning", "Error", "Information"], "Warning", "How a code from a code system the validator does not know is reported (for example SNOMED CT or LOINC)."),
        bool("anyExtensionsAllowed", "Allow Unknown Extensions", true, "If checked, extensions whose definition the validator does not know are accepted. If not checked, they are errors."),
        text("terminologyServer", "Terminology Server", "", "Optional base URL of a FHIR terminology server to check codes the validator cannot check itself. Leave empty to validate offline.")
      ]
    },
    {
      key: "batchProperties",
      label: "Batch",
      class: `${PKG}.FhirBatchProperties`,
      fields: [
        {
          key: "splitType",
          label: "Split Batch By",
          type: "select",
          options: [
            { value: "Bundle_Entry", label: "Bundle Entry" },
            { value: "JavaScript", label: "JavaScript" }
          ],
          default: "Bundle_Entry",
          hint: "Bundle Entry: every Bundle.entry.resource becomes a separate message, in the format it came in. The source map gets fhirBundleType, fhirBundleId, fhirEntryIndex (0 for the first entry), fhirEntryCount, fhirEntryFullUrl, fhirEntryRequestMethod and fhirEntryRequestUrl. Entries without a resource are skipped; a message that is not a Bundle stays one message. Only used when Process Batch is enabled in the connector."
        },
        code("batchScript", "JavaScript", null, BATCH_SCRIPT_HINT)
      ]
    }
  ]
};

DEF.defaults = (version) => {
  const props = { "@class": DEF.propertiesClass, "@version": version };
  for (const group of DEF.groups) {
    const obj = { "@class": group.class, "@version": version };
    for (const f of group.fields) obj[f.key] = f.default ?? null;
    props[group.key] = obj;
  }
  return props;
};

// ---------------------------------------------------------------------------------------------
// FHIR Profile Mapper transformer step (FhirProfileMapperStep). The tree of the target profile is
// built from its snapshot (GET /api/extensions/datatype-fhir/structure); data types and extension
// profiles without children in the snapshot are loaded when they are opened. Every element can get
// a JavaScript expression, usually a path in msg (drag it from the Message Trees panel). The step is
// saved with the compiled mappings, so the server needs no profile to generate the script.
// ---------------------------------------------------------------------------------------------

const MAPPER_TYPE = `${PKG}.FhirProfileMapperStep`;
const MAPPER_API = "/extensions/datatype-fhir";
const FHIR_BASE = "http://hl7.org/fhir/StructureDefinition/";

let React = null;
const h = (...args) => React.createElement(...args);

/** Indexed StructureDefinitions per "version|packages|url": a promise, and the value once loaded. */
const sdPromises = new Map();
const sdLoaded = new Map();

const sdKey = (version, profiles, url) => `${version}|${profiles}|${url}`;

function indexSd(sd) {
  const elements = (sd.snapshot && sd.snapshot.element) || [];
  const byParent = new Map();
  const byId = new Map();
  for (const el of elements) byId.set(el.id, el);
  for (const el of elements.slice(1)) {
    const parent = el.id.slice(0, el.id.lastIndexOf("."));
    if (!byParent.has(parent)) byParent.set(parent, []);
    byParent.get(parent).push(el);
  }
  return { sd, root: elements[0], byParent, byId };
}

function fetchSd(platform, version, profiles, url) {
  const key = sdKey(version, profiles, url);
  if (!sdPromises.has(key)) {
    const promise = platform.api
      .get(`${MAPPER_API}/structure`, { url, fhirVersion: version, profiles }, { raw: true })
      .then((text) => {
        const index = indexSd(JSON.parse(text));
        sdLoaded.set(key, index);
        return index;
      })
      .catch((e) => {
        sdPromises.delete(key);
        throw e;
      });
    sdPromises.set(key, promise);
  }
  return sdPromises.get(key);
}

/** The message of a failed request: the engine answers a ClientException as XStream XML. */
function errorText(e) {
  const body = (e && e.body) || "";
  const m = /<detailMessage>([\s\S]*?)<\/detailMessage>/.exec(body);
  if (m) return m[1].replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
  return (e && e.message) || String(e);
}

const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const lastSegment = (id) => id.slice(id.lastIndexOf(".") + 1);
const shortType = (code) => (code || "").replace("http://hl7.org/fhirpath/System.", "").replace(/^http:\/\/hl7\.org\/fhirpath\//, "");
const isPrimitive = (code) => /^[a-z]/.test(code) || code.startsWith("http://hl7.org/fhirpath/");
const NOT_EXPANDED = new Set(["Resource", "BackboneElement", "Element"]);

function fixedOf(el) {
  for (const k of Object.keys(el)) {
    if (/^(fixed|pattern)[A-Z]/.test(k)) return { kind: k.startsWith("fixed") ? "fixed" : "pattern", value: el[k] };
  }
  return null;
}

function hiddenElement(el, parent, showAll) {
  if (el.max === "0") return true;
  const name = lastSegment(el.id).split(":")[0];
  if (showAll) return (el.type || []).some((t) => t.code === "Resource");
  if ((name === "extension" || name === "modifierExtension") && !el.sliceName) return true;
  if (name === "id" && !parent.resourceRoot) return true;
  return (el.type || []).some((t) => t.code === "Resource");
}

/**
 * The tree of a step. Nodes are built on demand from the loaded StructureDefinitions; repeated
 * instances of an element (instances: keyBase -> count) are separate nodes, keys "x.given[1]".
 */
function makeTree(rootIndex, ctx) {
  const root = {
    key: rootIndex.sd.type,
    label: rootIndex.sd.type,
    el: rootIndex.root,
    sdx: rootIndex,
    path: [],
    depth: 0,
    resourceRoot: true,
    instance: 0,
    index: 0,
    code: rootIndex.sd.type,
    min: 1,
    max: "1"
  };
  const cache = new Map();

  function makeNode(parent, el, sdx, choiceType, instance, index) {
    const name = lastSegment(el.id).split(":")[0];
    let types = el.type || [];
    let sliceName = el.sliceName || null;
    let jsonName;
    let label;
    let choice = false;
    if (choiceType) {
      types = [choiceType];
      jsonName = name.replace("[x]", "") + cap(choiceType.code);
      label = jsonName;
      sliceName = null;
    } else if (name.endsWith("[x]")) {
      const base = name.slice(0, -3);
      // A type slice (value[x]:valueQuantity) may keep all the types of its base in the snapshot.
      const sliced = sliceName ? types.filter((t) => base + cap(t.code) === sliceName) : [];
      if (sliced.length === 1) types = sliced;
      if (types.length === 1) {
        jsonName = sliceName && sliceName.startsWith(base) ? sliceName : base + cap(types[0].code);
        label = jsonName;
        sliceName = null;
      } else {
        choice = true;
        label = sliceName ? `${name}:${sliceName}` : name;
        jsonName = null;
      }
    } else {
      jsonName = name;
      label = sliceName ? `${name}:${sliceName}` : name;
    }
    const baseMax = (el.base && el.base.max) || el.max || "1";
    const repeats = !choiceType && !choice && baseMax !== "1" && baseMax !== "0";
    const keyBase = `${parent.key}.${label}`;
    const key = keyBase + (instance ? `[${instance}]` : "");
    const segment = repeats ? [jsonName, `${sliceName || ""}#${instance}`] : [jsonName];
    const code = types.length === 1 ? types[0].code : null;
    const primitive = !!code && isPrimitive(code);
    let typeUrl = null;
    if (!choice && code && !primitive && !NOT_EXPANDED.has(code)) {
      typeUrl = (types[0].profile && types[0].profile[0]) || FHIR_BASE + code;
    }
    return {
      key,
      keyBase,
      label,
      jsonName,
      el,
      sdx,
      choiceType,
      choice,
      sliceName,
      repeats,
      instance,
      index,
      path: jsonName == null ? parent.path : parent.path.concat([segment]),
      code,
      primitive,
      typeUrl,
      types,
      min: choiceType ? parent.min : el.min,
      max: choiceType ? parent.max : el.max,
      short: el.short || "",
      mustSupport: !!el.mustSupport || (choiceType ? parent.mustSupport : false),
      fixed: choiceType ? null : fixedOf(el),
      depth: parent.depth + 1,
      parent
    };
  }

  /** The child elements of a node, or null while the StructureDefinition of its type is loading. */
  function childElements(node) {
    if (node.choice) return node.types.map((t) => ({ el: node.el, sdx: node.sdx, choiceType: t }));
    const own = node.choiceType ? [] : node.sdx.byParent.get(node.el.id) || [];
    if (own.length) return own.map((el) => ({ el, sdx: node.sdx }));
    if (!node.typeUrl) return [];
    const typeIndex = sdLoaded.get(sdKey(ctx.version, ctx.profiles, node.typeUrl));
    if (!typeIndex) return null;
    return (typeIndex.byParent.get(typeIndex.root.id) || []).map((el) => ({ el, sdx: typeIndex }));
  }

  function children(node) {
    if (cache.has(node.key)) return cache.get(node.key);
    const elements = childElements(node);
    if (elements === null) return null;
    const list = [];
    for (const { el, sdx, choiceType } of elements) {
      if (!choiceType && hiddenElement(el, node, ctx.showAll)) continue;
      const first = makeNode(node, el, sdx, choiceType, 0, list.length);
      const count = Math.max(1, ctx.instances.get(first.keyBase) || 1);
      list.push(first);
      for (let i = 1; i < count; i++) list.push(makeNode(node, el, sdx, choiceType, i, list.length));
    }
    cache.set(node.key, list);
    return list;
  }

  const expandable = (node) => node.choice || !!node.typeUrl || (!node.choiceType && (node.sdx.byParent.get(node.el.id) || []).length > 0);

  /** The nodes from the root to a key, or null when it is not (or not yet) in the loaded tree. */
  function chain(key) {
    if (key === root.key) return [root];
    if (!key.startsWith(root.key + ".")) return null;
    const result = [root];
    let node = root;
    for (const segment of key.slice(root.key.length + 1).split(".")) {
      const list = children(node);
      if (!list) return null;
      node = list.find((n) => n.key === `${node.key}.${segment}`);
      if (!node) return null;
      result.push(node);
    }
    return result;
  }

  /** Discriminator paths of a slice ("system", "url", "type.coding.code"), relative to the slice. */
  function discriminators(node) {
    if (!node.sliceName) return [];
    const base = node.sdx.byId.get(node.el.id.replace(/:[^.:]+$/, ""));
    return ((base && base.slicing && base.slicing.discriminator) || []).map((d) => d.path).filter((p) => p && p !== "$this");
  }

  return { root, children, expandable, chain, discriminators };
}

/** Keys and their count of instances, from the keys of saved mappings. */
function instancesOf(keys) {
  const instances = new Map();
  for (const key of keys) {
    const parts = key.split(".");
    for (let i = 1; i < parts.length; i++) {
      const m = /^(.*)\[(\d+)\]$/.exec(parts[i]);
      if (m) {
        const keyBase = parts.slice(0, i).join(".") + "." + m[1];
        instances.set(keyBase, Math.max(instances.get(keyBase) || 1, Number(m[2]) + 1));
      }
    }
  }
  return instances;
}

function parseMappings(text) {
  try {
    const list = JSON.parse(text || "[]");
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

/**
 * The mappings saved with the step: the values and iterations in tree order (that is the order of
 * the entries of repeating elements), then the fixed and pattern values of the entries they create:
 * of the slices and elements on the way, and of required elements and slice discriminators below
 * them. iterations: key -> {expression, variable}; translations: key -> {conceptMap, source, target, unmapped}.
 */
function compile(tree, values, iterations, translations, conditions) {
  const entries = [];
  const fixed = new Map();
  for (const [key, it] of iterations) {
    const nodes = it.expression.trim() ? tree.chain(key) : null;
    if (!nodes) continue;
    const node = nodes[nodes.length - 1];
    // the resource itself: one resource per item (Bundle output)
    if (node !== tree.root && !node.repeats) continue;
    entries.push({ key, each: it.expression.trim(), variable: (it.variable || "item").trim(), path: node.path, order: node === tree.root ? [] : nodes.map((n) => n.index) });
  }
  for (const [key, condition] of conditions) {
    const nodes = condition.trim() ? tree.chain(key) : null;
    if (!nodes) continue;
    const node = nodes[nodes.length - 1];
    entries.push({ key, condition: condition.trim(), path: node.path, order: node === tree.root ? [] : nodes.map((n) => n.index) });
  }
  const mapped = new Set([...values.keys()].filter((k) => values.get(k).trim() !== ""));
  for (const key of mapped) {
    const nodes = tree.chain(key);
    if (!nodes) continue;
    const leaf = nodes[nodes.length - 1];
    if (leaf.choice) continue;
    const entry = {
      key,
      element: key,
      type: shortType(leaf.code),
      expression: values.get(key).trim(),
      path: leaf.path,
      order: nodes.map((n) => n.index)
    };
    const t = translations.get(key);
    if (t && t.conceptMap && t.conceptMap.trim()) {
      entry.translate = { conceptMap: t.conceptMap.trim(), source: (t.source || "").trim(), target: (t.target || "").trim(), unmapped: t.unmapped || "none", keepSource: !!t.keepSource };
      if (entry.translate.unmapped === "fixed" || entry.translate.unmapped === "function") entry.translate.value = (t.value || "").trim();
    }
    entries.push(entry);
    const onChain = new Set(nodes.map((n) => n.key));
    for (const container of nodes.slice(0, -1)) {
      const when = container.path.length;
      if (container !== tree.root && container.fixed && container.jsonName != null && !fixed.has(container.key)) {
        fixed.set(container.key, { key: container.key, element: container.key, fixed: container.fixed.value, when, path: container.path });
      }
      const discriminators = tree.discriminators(container);
      const walk = (node, rel, required) => {
        for (const d of tree.children(node) || []) {
          if (d.instance || onChain.has(d.key) || mapped.has(d.key) || d.jsonName == null) continue;
          const path = rel ? `${rel}.${d.jsonName}` : d.jsonName;
          const req = required && Number(d.min) >= 1;
          if (d.fixed && (req || discriminators.includes(path))) {
            if (!fixed.has(d.key)) fixed.set(d.key, { key: d.key, element: d.key, fixed: d.fixed.value, when, path: d.path });
          } else if (req || discriminators.some((p) => p.startsWith(path + "."))) {
            walk(d, path, req);
          }
        }
      };
      walk(container, "", true);
    }
  }
  const cmp = (a, b) => {
    for (let i = 0; i < Math.min(a.length, b.length); i++) if (a[i] !== b[i]) return a[i] - b[i];
    return a.length - b.length;
  };
  entries.sort((a, b) => cmp(a.order, b.order));
  return [...entries.map(({ order, ...e }) => e), ...fixed.values()];
}

/**
 * Required (min 1) and Must Support elements that nothing maps, below the resource and below every
 * element that gets something: not mapped, not iterated, no fixed value. Only the loaded tree.
 */
function completeness(tree, saved) {
  const entries = saved.filter((e) => e.key || e.element).map((e) => ({ ...e, key: e.key || e.element }));
  const covered = (node) =>
    entries.some((e) => e.key === node.key || e.key.startsWith(node.key + ".") || e.key.startsWith(node.key + ":") || (e.key.startsWith(node.key + "[") && /^\[\d+\]/.test(e.key.slice(node.key.length))));
  const required = [];
  const mustSupport = [];
  const walk = (node) => {
    for (const child of tree.children(node) || []) {
      if (child.instance || child.choiceType) continue;
      if (covered(child) || child.fixed) {
        if (!child.primitive) walk(child);
      } else if (Number(child.min) >= 1) {
        required.push(child.key);
      } else if (child.mustSupport) {
        mustSupport.push(child.key);
      }
    }
  };
  walk(tree.root);
  return { required, mustSupport };
}

/** The text an edit inserted at once (a paste, or a click in the Message Trees panel), or null. */
function inserted(before, after) {
  let start = 0;
  while (start < before.length && start < after.length && before[start] === after[start]) start++;
  let end = 0;
  while (end < before.length - start && end < after.length - start && before[before.length - 1 - end] === after[after.length - 1 - end]) end++;
  const text = after.slice(start, after.length - end);
  return text.length > 1 ? { start, end: after.length - end, text } : null;
}

const asBool = (v, def) => (v === undefined || v === null || v === "" ? def : v === true || v === "true");
const fixedText = (f) => (typeof f.value === "object" ? JSON.stringify(f.value) : String(f.value));
const TRANSLATABLE = new Set(["code", "string", "Coding", "CodeableConcept"]);
const isLocalServer = (server) => !String(server || "").trim() || String(server).trim().toLowerCase() === "n/a";
const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * A field dragged from the Message Trees panel into an element below an iteration: the path of the
 * iterated list becomes its variable (msg['PID']['PID.3'][0]['PID.3.1'] -> item['PID.3.1']), as the
 * Iterator step does with its prefix substitutions. The innermost iteration that matches wins.
 */
function substitute(text, iterations) {
  for (const it of iterations) {
    const prefix = it.expression.trim().replace(/\.toString\(\)$/, "");
    if (!prefix) continue;
    const m = new RegExp("^" + escapeRegExp(prefix) + "(\\[\\d+\\])?").exec(text);
    if (m) return (it.variable || "item") + text.slice(m[0].length);
  }
  return text;
}

function ProfileMapperEditor({ element, onChange, platform }) {
  const [, rerender] = React.useReducer((x) => x + 1, 0);
  const state = React.useRef(null);
  if (state.current === null) {
    const saved = parseMappings(element.mappings);
    const values = new Map();
    const iterations = new Map();
    const translations = new Map();
    const conditions = new Map();
    for (const e of saved) {
      if (!e.key) continue;
      if (typeof e.each === "string") iterations.set(e.key, { expression: e.each, variable: e.variable || "item" });
      else if (typeof e.condition === "string") conditions.set(e.key, e.condition);
      else if (typeof e.expression === "string") values.set(e.key, e.expression);
      if (e.translate && typeof e.translate === "object") translations.set(e.key, { ...e.translate });
    }
    let sample = { message: "", dataType: "HL7V2" };
    try {
      sample = JSON.parse(localStorage.getItem("fhir-profile-mapper-sample") || "null") || sample;
    } catch {
      // no storage: an empty sample
    }
    state.current = {
      values,
      iterations,
      translations,
      conditions,
      // shown when something below an extension, modifierExtension or id (that "extensions & ids" shows) is mapped
      showAll: [...values.keys(), ...iterations.keys(), ...conditions.keys()].some((k) => /\.(extension|modifierExtension|id)(\[\d+\])?(\.|$)/.test(k)),
      sample,
      testResult: null,
      scriptResult: null,
      testing: false,
      versionNotice: "",
      instances: instancesOf([...values.keys(), ...iterations.keys(), ...translations.keys(), ...conditions.keys()]),
      conceptMaps: null,
      tests: new Map(),
      expanded: new Set(),
      rootIndex: null,
      loading: false,
      error: "",
      unresolved: [],
      notice: "",
      profileList: null,
      filter: "all"
    };
  }
  const st = state.current;
  const version = element.fhirVersion || "R4";
  const profiles = element.profiles || "";
  const ctx = { version, profiles, instances: st.instances, showAll: st.showAll };
  const tree = st.rootIndex ? makeTree(st.rootIndex, ctx) : null;

  const allKeys = () => new Set([...st.values.keys(), ...st.iterations.keys(), ...st.translations.keys(), ...st.conditions.keys()]);
  const forget = (key) => {
    st.values.delete(key);
    st.iterations.delete(key);
    st.translations.delete(key);
    st.conditions.delete(key);
  };
  // Compiled on the whole tree, also what "extensions & ids" hides, so that no mapping is lost.
  const compiled = (index, local) => JSON.stringify(compile(makeTree(index, { ...local, showAll: true }), st.values, st.iterations, st.translations, st.conditions));

  const save = () => {
    if (!tree) return;
    element.mappings = compiled(st.rootIndex, ctx);
    onChange();
  };

  // Loads the profile, then every type on the way to a saved mapping. Mappings of elements another
  // profile does not have are dropped (and listed); for the saved profile, a mapping that cannot be
  // found (a type that failed to load) blocks editing, so that saving cannot lose it.
  const load = async (url) => {
    st.loading = true;
    st.error = "";
    st.notice = "";
    st.unresolved = [];
    rerender();
    try {
      const index = await fetchSd(platform, version, profiles, url);
      const sameProfile = element.profileUrl === url && element.resourceType === index.sd.type;
      st.rootIndex = index;
      st.expanded.add(index.sd.type);
      const local = { version, profiles, instances: st.instances, showAll: true };
      const unresolved = [];
      for (const key of allKeys()) {
        const parts = key.split(".");
        let found = true;
        for (let i = 1; i <= parts.length && found; i++) {
          const prefix = parts.slice(0, i).join(".");
          let t = makeTree(index, local);
          let nodes = t.chain(prefix);
          if (!nodes) {
            // the parent may be a type that is not loaded yet
            const parentNodes = t.chain(parts.slice(0, i - 1).join("."));
            const parent = parentNodes && parentNodes[parentNodes.length - 1];
            if (parent && parent.typeUrl) {
              await fetchSd(platform, version, profiles, parent.typeUrl);
              t = makeTree(index, local);
              nodes = t.chain(prefix);
            }
          }
          if (!nodes) found = false;
          else if (i < parts.length) st.expanded.add(prefix);
        }
        if (!found) unresolved.push(key);
      }
      // Load the types of required elements below what is mapped, so that their fixed values are known.
      for (let round = 0; round < 4; round++) {
        const t = makeTree(index, local);
        const urls = new Set();
        const walk = (node, depth) => {
          for (const d of t.children(node) || []) {
            if (Number(d.min) < 1 || d.instance) continue;
            const kids = t.children(d);
            if (kids === null && d.typeUrl) urls.add(d.typeUrl);
            else if (kids && depth < 6) walk(d, depth + 1);
          }
        };
        for (const key of allKeys()) {
          const nodes = t.chain(key);
          if (nodes) for (const n of nodes.slice(0, -1)) walk(n, 0);
        }
        if (!urls.size) break;
        await Promise.all([...urls].map((u) => fetchSd(platform, version, profiles, u).catch(() => null)));
      }
      const sdVersion = index.sd.version || "";
      if (sameProfile && element.profileVersion && sdVersion && element.profileVersion !== sdVersion) {
        st.versionNotice = `The profile is now version ${sdVersion}; these mappings were made with version ${element.profileVersion}. Check them (elements no longer in the profile are listed).`;
      }
      if (sameProfile) {
        st.unresolved = unresolved;
        const before = parseMappings(element.mappings).filter((e) => "fixed" in e).length;
        const now = compiled(index, local);
        if (!unresolved.length && JSON.parse(now).filter((e) => "fixed" in e).length > before) {
          element.mappings = now;
          st.notice = "Fixed values of required elements were added to the mappings.";
          onChange();
        }
      } else {
        for (const key of unresolved) forget(key);
        if (unresolved.length) st.notice = `Not in this profile, removed: ${unresolved.join(", ")}.`;
      }
      if (!sameProfile) {
        element.profileUrl = url;
        element.profileVersion = index.sd.version || "";
        element.resourceType = index.sd.type;
        element.mappings = compiled(index, local);
        onChange();
      }
    } catch (e) {
      st.error = errorText(e);
    } finally {
      st.loading = false;
      rerender();
    }
  };

  React.useEffect(() => {
    if (element.profileUrl) load(element.profileUrl);
  }, []);

  // The transformer editor shows element.script in its Generated Script tab and validates it with
  // Validate Step: the server's script, as a property that is not enumerable, so never saved.
  const scriptFor = React.useRef({ source: null, timer: null });
  React.useEffect(() => {
    const source = JSON.stringify(element);
    if (source === scriptFor.current.source) return undefined;
    clearTimeout(scriptFor.current.timer);
    scriptFor.current.timer = setTimeout(async () => {
      try {
        const text = await platform.api.post(`${MAPPER_API}/script`, source, { contentType: "text/plain", raw: true });
        const result = JSON.parse(text);
        const errors = (result.errors || []).map((e) => `// ${e.key ? `${e.key}: ` : ""}${e.message}`).join("\n");
        Object.defineProperty(element, "script", { value: (errors ? errors + "\n" : "") + (result.script || ""), enumerable: false, writable: true, configurable: true });
        scriptFor.current.source = source;
      } catch {
        // no preview until the next change
      }
    }, 800);
    return undefined;
  });
  React.useEffect(() => () => clearTimeout(scriptFor.current.timer), []);

  const loadProfileList = async () => {
    try {
      const text = await platform.api.get(`${MAPPER_API}/profiles`, { fhirVersion: version, profiles }, { raw: true });
      st.profileList = JSON.parse(text);
      st.error = "";
    } catch (e) {
      st.error = errorText(e);
    }
    rerender();
  };

  const toggle = async (node) => {
    if (st.expanded.has(node.key)) {
      st.expanded.delete(node.key);
      rerender();
      return;
    }
    st.expanded.add(node.key);
    rerender();
    if (tree.children(node) === null) {
      try {
        await fetchSd(platform, version, profiles, node.typeUrl);
      } catch (e) {
        st.error = `${node.typeUrl}: ${errorText(e)}`;
      }
      rerender();
    }
  };

  const setValue = (node, value) => {
    if (value.trim() === "") st.values.delete(node.key);
    else st.values.set(node.key, value);
    save();
    rerender();
  };

  const addInstance = (node) => {
    st.instances.set(node.keyBase, Math.max(1, st.instances.get(node.keyBase) || 1) + 1);
    rerender();
  };

  const removeInstance = (node) => {
    for (const key of allKeys()) {
      if (key === node.key || key.startsWith(node.key + ".")) forget(key);
    }
    st.instances.set(node.keyBase, node.instance);
    save();
    rerender();
  };

  const removeUnresolved = () => {
    for (const key of st.unresolved) forget(key);
    st.unresolved = [];
    save();
    rerender();
  };

  const toggleIteration = (node) => {
    if (st.iterations.has(node.key)) st.iterations.delete(node.key);
    else st.iterations.set(node.key, { expression: "", variable: "item" });
    save();
    rerender();
  };
  const setIteration = (node, prop, value) => {
    st.iterations.get(node.key)[prop] = value;
    save();
    rerender();
  };
  const toggleCondition = (node) => {
    if (st.conditions.has(node.key)) st.conditions.delete(node.key);
    else st.conditions.set(node.key, "");
    save();
    rerender();
  };
  const setCondition = (node, value) => {
    st.conditions.set(node.key, value);
    save();
    rerender();
  };
  const saveSample = () => {
    try {
      localStorage.setItem("fhir-profile-mapper-sample", JSON.stringify(st.sample));
    } catch {
      // no storage: the sample is kept while the editor is open
    }
  };
  const runTest = async () => {
    st.testing = true;
    st.testResult = null;
    rerender();
    try {
      const text = await platform.api.post(`${MAPPER_API}/test`, JSON.stringify({ step: element, message: st.sample.message, dataType: st.sample.dataType }), {
        contentType: "text/plain",
        raw: true,
        timeoutMs: 60000
      });
      st.testResult = JSON.parse(text);
    } catch (e) {
      st.testResult = { output: null, issues: [], errors: [{ key: null, line: null, message: errorText(e) }] };
    }
    st.testing = false;
    rerender();
  };
  const showScript = async () => {
    if (st.scriptResult) {
      st.scriptResult = null;
      rerender();
      return;
    }
    try {
      const text = await platform.api.post(`${MAPPER_API}/script`, JSON.stringify(element), { contentType: "text/plain", raw: true });
      st.scriptResult = JSON.parse(text);
    } catch (e) {
      st.scriptResult = { script: "", errors: [{ key: null, line: null, message: errorText(e) }] };
    }
    rerender();
  };

  // A field clicked or pasted in below an iteration is rewritten to its variable, as when dragged.
  const withSubstitution = (node, before, after) => {
    const around = iterationsAround(node);
    const ins = around.length ? inserted(before, after) : null;
    if (!ins || !/^msg\[/.test(ins.text)) return after;
    return after.slice(0, ins.start) + substitute(ins.text, around) + after.slice(ins.end);
  };

  const toggleTranslation = (node) => {
    if (st.translations.has(node.key)) st.translations.delete(node.key);
    else st.translations.set(node.key, { conceptMap: "", source: "", target: "", unmapped: "none" });
    save();
    rerender();
  };
  const setTranslation = (node, prop, value) => {
    st.translations.get(node.key)[prop] = value;
    save();
    rerender();
  };
  // The ConceptMaps of the terminology server, or without one (empty or n/a) of the profile packages.
  const loadConceptMaps = async () => {
    const server = (element.terminologyServer || "").trim();
    const source = `${server}|${profiles}|${version}`;
    if (st.conceptMaps && st.conceptMaps.source === source) return;
    try {
      const text = await platform.api.get(`${MAPPER_API}/conceptMaps`, { server, profiles, fhirVersion: version }, { raw: true });
      st.conceptMaps = { source, list: JSON.parse(text) };
      st.error = "";
    } catch (e) {
      st.conceptMaps = { source, list: [] };
      st.error = errorText(e);
    }
    rerender();
  };
  const testTranslation = async (node) => {
    const t = st.translations.get(node.key);
    const test = st.tests.get(node.key) || {};
    test.result = "…";
    st.tests.set(node.key, test);
    rerender();
    try {
      const text = await platform.api.get(
        `${MAPPER_API}/translate`,
        { server: element.terminologyServer || "", profiles, conceptMap: t.conceptMap, system: t.source || "", code: test.code || "", targetSystem: t.target || "", fhirVersion: version },
        { raw: true }
      );
      const match = JSON.parse(text);
      test.result = match ? `→ ${[match.system, match.code].filter(Boolean).join("|")}${match.display ? ` (${match.display})` : ""}` : "→ no match (then the rule chosen for no match)";
    } catch (e) {
      test.result = errorText(e);
    }
    rerender();
  };

  /** The iterations from a node up to the root, innermost first. */
  const iterationsAround = (node) => {
    const list = [];
    for (let n = node; n; n = n.parent) {
      const it = st.iterations.get(n.key);
      if (it) list.push(it);
    }
    return list;
  };

  const field = (label, control, hint) => h("div", { className: "field" }, h("label", null, label), control, hint ? h("div", { className: "hint" }, hint) : null);
  const number = (prop, fallback) =>
    h("input", {
      type: "number",
      min: 0,
      value: element[prop] ?? fallback,
      onChange: (e) => {
        const n = parseInt(e.target.value, 10);
        element[prop] = Number.isFinite(n) && n >= 0 ? n : fallback;
        onChange();
        rerender();
      }
    });
  const text = (prop, placeholder, code) =>
    h("input", {
      type: "text",
      placeholder,
      style: code ? { fontFamily: "monospace", fontSize: "12px" } : undefined,
      value: element[prop] ?? "",
      onChange: (e) => {
        element[prop] = e.target.value;
        onChange();
        rerender();
      }
    });

  const hasMappedBelow = (node) => {
    for (const key of allKeys()) if (key === node.key || key.startsWith(node.key + ".") || key.startsWith(node.key + "[")) return true;
    return false;
  };
  const visible = (node) => {
    if (st.filter === "mapped") return hasMappedBelow(node);
    if (st.filter === "mustSupport") return node.mustSupport || Number(node.min) >= 1 || hasMappedBelow(node);
    return true;
  };

  const rows = [];
  const blocked = st.loading || st.unresolved.length > 0;
  const addRows = (node) => {
    const list = tree.children(node);
    if (list === null) {
      rows.push(h("tr", { key: node.key + "/loading" }, h("td", { colSpan: 3, style: { paddingLeft: `${(node.depth + 1) * 16 + 8}px` } }, h("span", { className: "text-text-faint" }, "Loading…"))));
      return;
    }
    for (const child of list) {
      if (!visible(child)) continue;
      rows.push(row(child, list));
      if (st.iterations.has(child.key)) rows.push(iterationRow(child));
      if (st.conditions.has(child.key)) rows.push(conditionRow(child));
      if (st.translations.has(child.key)) rows.push(translationRow(child));
      if (st.expanded.has(child.key)) addRows(child);
    }
  };
  const row = (node, siblings) => {
    const open = st.expanded.has(node.key);
    const canExpand = tree.expandable(node);
    const count = siblings.filter((s) => s.keyBase === node.keyBase).length;
    const iterated = st.iterations.has(node.key);
    const canAdd = !iterated && node.repeats && node.instance === count - 1 && (node.max === "*" || Number(node.max) > count);
    const canIterate = node.repeats && !node.choice;
    const canTranslate = !node.choice && TRANSLATABLE.has(node.code);
    const value = st.values.get(node.key) ?? "";
    const types = node.types.map((t) => shortType(t.code) + (t.profile ? ` (${t.profile.map((p) => p.slice(p.lastIndexOf("/") + 1)).join(", ")})` : "")).join(" | ");
    const placeholder = node.fixed ? `${node.fixed.kind}: ${fixedText(node.fixed)}` : node.choice ? "choose a type below" : "";
    const ellipsis = { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" };
    const tool = (label, title, onClick, active) =>
      h("button", { type: "button", className: "btn btn-sm", style: { flex: "none", padding: "0 6px", fontWeight: active ? 700 : 400, borderColor: active ? "#2563eb" : undefined }, title, disabled: blocked, onClick }, label);
    // Fixed column widths (see the table): the element and type are cut with an ellipsis, the
    // mapping keeps its room, and the actions sit next to it, where they cannot push it away.
    return h(
      "tr",
      { key: node.key, title: node.short ? `${node.key}: ${node.short}` : node.key },
      h(
        "td",
        { style: { paddingLeft: `${node.depth * 16}px` } },
        h(
          "div",
          { style: { display: "flex", alignItems: "center", gap: "4px", minWidth: 0 } },
          h(
            "button",
            {
              type: "button",
              className: "btn btn-sm",
              style: { flex: "none", visibility: canExpand ? "visible" : "hidden", minWidth: "22px", padding: "0 4px" },
              onClick: () => toggle(node)
            },
            open ? "▾" : "▸"
          ),
          h("span", { style: { ...ellipsis, minWidth: 0, fontWeight: node.mustSupport ? 600 : 400 } }, node.label + (node.instance ? ` [${node.instance}]` : "")),
          h("span", { className: "text-text-faint", style: { flex: "none", fontSize: "11px" } }, `${node.min ?? 0}..${node.max ?? "*"}`),
          node.mustSupport ? h("span", { title: "Must Support", style: { flex: "none", color: "#c2410c", fontSize: "11px" } }, "S") : null
        )
      ),
      h("td", { className: "text-text-faint", style: { ...ellipsis, fontSize: "11px" }, title: types }, types),
      h(
        "td",
        null,
        h(
          "div",
          { style: { display: "flex", alignItems: "center", gap: "4px", minWidth: 0 } },
          node.choice
            ? h("span", { className: "text-text-faint", style: { ...ellipsis, flex: "1 1 auto", fontSize: "11px" } }, placeholder)
            : h("input", {
                type: "text",
                value,
                disabled: blocked,
                placeholder,
                title: value || placeholder,
                style: { flex: "1 1 auto", width: "auto", minWidth: "160px", fontFamily: "monospace", fontSize: "12px" },
                onChange: (e) => setValue(node, withSubstitution(node, value, e.target.value)),
                onDrop: (e) => {
                  const dropped = e.dataTransfer && e.dataTransfer.getData("text/plain");
                  const around = iterationsAround(node);
                  if (!dropped || !around.length) return;
                  e.preventDefault();
                  const input = e.target;
                  const start = input.selectionStart ?? input.value.length;
                  const end = input.selectionEnd ?? start;
                  setValue(node, input.value.slice(0, start) + substitute(dropped, around) + input.value.slice(end));
                }
              }),
          canAdd ? tool("+", "Add an entry", () => addInstance(node), false) : null,
          node.instance && node.instance === count - 1 ? tool("−", "Remove this entry and its mappings", () => removeInstance(node), false) : null,
          canIterate ? tool("↻", "One entry for each item of a list (for each repetition)", () => toggleIteration(node), iterated) : null,
          canTranslate ? tool("⇄", "Translate the value with ConceptMap/$translate", () => toggleTranslation(node), st.translations.has(node.key)) : null,
          node.choice ? null : tool("?", "Only map this element (and what is below it) when a condition holds", () => toggleCondition(node), st.conditions.has(node.key))
        )
      )
    );
  };

  const subRow = (node, suffix, children) =>
    h("tr", { key: `${node.key}/${suffix}` }, h("td", { colSpan: 3, style: { paddingLeft: `${node.depth * 16 + 30}px`, background: "rgba(37, 99, 235, 0.05)" } }, h("div", { style: { display: "flex", gap: "6px", alignItems: "center", flexWrap: "wrap" } }, children)));
  const mono = { fontFamily: "monospace", fontSize: "12px" };

  const iterationRow = (node) => {
    const it = st.iterations.get(node.key);
    const variable = (it.variable || "item").trim() || "item";
    return subRow(node, "each", [
      h("span", { key: "l", title: node === tree.root ? "One resource for each item" : "One entry of this element for each item" }, node === tree.root ? "↻ One resource for each" : "↻ For each"),
      h("input", { key: "e", type: "text", value: it.expression, disabled: blocked, placeholder: "msg['PID']['PID.3']", style: { ...mono, flex: "1 1 240px" }, onChange: (e) => setIteration(node, "expression", e.target.value) }),
      h("span", { key: "a" }, "as"),
      h("input", { key: "v", type: "text", value: it.variable, disabled: blocked, style: { ...mono, width: "90px" }, onChange: (e) => setIteration(node, "variable", e.target.value) }),
      h("span", { key: "h", className: "text-text-faint", style: { fontSize: "11px" } }, `below: ${variable} (the item), ${variable}Index (0, 1, …); fields dragged in are rewritten to ${variable}[…]`)
    ]);
  };

  const conditionRow = (node) =>
    subRow(node, "condition", [
      h("span", { key: "l", title: "What is below this element is only mapped when the condition holds" }, node === tree.root ? "? Only make the resource if" : "? Only if"),
      h("input", {
        key: "c",
        type: "text",
        value: st.conditions.get(node.key) || "",
        disabled: blocked,
        placeholder: st.iterations.has(node.key) ? `${(st.iterations.get(node.key).variable || "item").trim()}['PID.3.5'].toString() == 'PI'` : "msg['PID']['PID.8']['PID.8.1'].toString() != ''",
        style: { ...mono, flex: "1 1 300px" },
        onChange: (e) => setCondition(node, withSubstitution(node, st.conditions.get(node.key) || "", e.target.value))
      }),
      h("span", { key: "h", className: "text-text-faint", style: { fontSize: "11px" } }, st.iterations.has(node.key) ? "per item: keeps the items it holds for" : "false, or an error on a missing segment: nothing below")
    ]);

  const translationRow = (node) => {
    const t = st.translations.get(node.key);
    const test = st.tests.get(node.key) || {};
    const maps = (st.conceptMaps && st.conceptMaps.list) || [];
    const listId = "fhir-profile-mapper-conceptmaps";
    const localTx = isLocalServer(element.terminologyServer);
    const needsValue = t.unmapped === "fixed" || t.unmapped === "function";
    return subRow(node, "translate", [
      h("span", { key: "l", title: localTx ? "ConceptMap/$translate locally, on the ConceptMaps of the Profile Packages" : "ConceptMap/$translate on the terminology server" }, localTx ? "⇄ ConceptMap (local)" : "⇄ ConceptMap"),
      h("input", {
        key: "m",
        type: "text",
        list: listId,
        value: t.conceptMap,
        disabled: blocked,
        placeholder: "canonical URL of the ConceptMap",
        style: { ...mono, flex: "1 1 220px" },
        onFocus: loadConceptMaps,
        onChange: (e) => setTranslation(node, "conceptMap", e.target.value)
      }),
      h("datalist", { key: "d", id: listId }, maps.map((m) => h("option", { key: m.url, value: m.url }, m.title || m.name || m.url))),
      h("input", { key: "s", type: "text", value: t.source, disabled: blocked, placeholder: "source system (optional)", title: "system of the source code", style: { ...mono, flex: "1 1 160px" }, onChange: (e) => setTranslation(node, "source", e.target.value) }),
      h("input", { key: "t", type: "text", value: t.target, disabled: blocked, placeholder: "target system (optional)", title: "targetsystem of $translate", style: { ...mono, flex: "1 1 140px" }, onChange: (e) => setTranslation(node, "target", e.target.value) }),
      h(
        "select",
        { key: "u", value: t.unmapped || "none", disabled: blocked, title: "When $translate gives no match", onChange: (e) => setTranslation(node, "unmapped", e.target.value) },
        h("option", { value: "none" }, "no match: leave empty"),
        h("option", { value: "source" }, "no match: keep the source code"),
        h("option", { value: "fixed" }, "no match: fixed value"),
        h("option", { value: "function" }, "no match: expression"),
        h("option", { value: "error" }, "no match: error")
      ),
      node.code === "CodeableConcept"
        ? h(
            "label",
            { key: "k", className: "check", title: "Add the source code as a second coding" },
            h("input", { type: "checkbox", checked: !!t.keepSource, disabled: blocked, onChange: (e) => setTranslation(node, "keepSource", e.target.checked) }),
            " + source coding"
          )
        : null,
      needsValue
        ? h("input", {
            key: "f",
            type: "text",
            value: t.value || "",
            disabled: blocked,
            placeholder: t.unmapped === "fixed" ? "code or system|code" : "myFunction(code, system)",
            title:
              t.unmapped === "fixed"
                ? "The value for codes without a match: a code, or system|code"
                : "JavaScript with code and system (the source code and its system), e.g. a code template function; gives a code, system|code or {system, code, display}, or null for no value",
            style: { ...mono, flex: "1 1 180px" },
            onChange: (e) => setTranslation(node, "value", e.target.value)
          })
        : null,
      h("input", {
        key: "c",
        type: "text",
        value: test.code || "",
        placeholder: "code",
        title: "a source code to try",
        style: { ...mono, width: "70px" },
        onChange: (e) => {
          st.tests.set(node.key, { ...test, code: e.target.value });
          rerender();
        }
      }),
      h("button", { key: "b", type: "button", className: "btn btn-sm", disabled: !t.conceptMap || !test.code, onClick: () => testTranslation(node) }, "Test"),
      test.result ? h("span", { key: "r", className: "text-text-faint", style: { fontSize: "11px" } }, test.result) : null
    ]);
  };
  const bundle = element.output === "bundle";
  if (tree) {
    const rootTool = (label, title, onClick, active) =>
      h("button", { type: "button", className: "btn btn-sm", style: { marginLeft: "6px", padding: "0 6px", fontWeight: active ? 700 : 400, borderColor: active ? "#2563eb" : undefined }, title, disabled: blocked, onClick }, label);
    rows.push(
      h(
        "tr",
        { key: tree.root.key },
        h(
          "td",
          { colSpan: 3 },
          h("strong", null, tree.root.label),
          h("span", { className: "text-text-faint", style: { marginLeft: "8px" } }, st.rootIndex.sd.url + (st.rootIndex.sd.version ? ` | ${st.rootIndex.sd.version}` : "")),
          bundle ? rootTool("↻", "One resource for each item of a list (e.g. each OBX)", () => toggleIteration(tree.root), st.iterations.has(tree.root.key)) : null,
          rootTool("?", "Only make the resource when a condition holds", () => toggleCondition(tree.root), st.conditions.has(tree.root.key))
        )
      )
    );
    if (st.iterations.has(tree.root.key)) rows.push(iterationRow(tree.root));
    if (st.conditions.has(tree.root.key)) rows.push(conditionRow(tree.root));
    addRows(tree.root);
  }
  const check = tree ? completeness(tree, parseMappings(element.mappings)) : { required: [], mustSupport: [] };
  const testResult = st.testResult;

  const profileOptions = (st.profileList || []).map((p) => h("option", { key: p.url, value: p.url }, `${p.title || p.name || p.url} (${p.type})`));

  return h(
    React.Fragment,
    null,
    h(
      "div",
      { className: "form-grid" },
      field(
        "FHIR Version",
        h(
          "select",
          {
            value: version,
            onChange: (e) => {
              element.fhirVersion = e.target.value;
              onChange();
              rerender();
            }
          },
          ["R4", "R4B", "R5"].map((v) => h("option", { key: v, value: v }, v))
        )
      ),
      field("Profile Packages", text("profiles", "/profiles"), "Files or folders on the server with profiles, separated by ';', as in the FHIR data type properties."),
      h(
        "div",
        { className: "span-2" },
        field(
          "Target Profile",
          h(
            "div",
            { style: { display: "flex", gap: "6px" } },
            h("input", {
              type: "text",
              list: "fhir-profile-mapper-profiles",
              placeholder: "Canonical URL of the profile, or a resource type (Patient)",
              value: element.profileUrl ?? "",
              style: { flex: 1 },
              onFocus: () => {
                if (st.profileList === null && profiles) loadProfileList();
              },
              onChange: (e) => {
                element.profileUrl = e.target.value;
                rerender();
              }
            }),
            h("datalist", { id: "fhir-profile-mapper-profiles" }, profileOptions),
            h("button", { type: "button", className: "btn btn-sm", disabled: st.loading || !element.profileUrl, onClick: () => load(element.profileUrl.trim()) }, st.loading ? "Loading…" : "Load"),
            h("button", { type: "button", className: "btn btn-sm", disabled: !profiles, onClick: loadProfileList }, "List profiles")
          ),
          "Loading another profile keeps the mappings of the elements it also has."
        )
      ),
      field(
        "Terminology Server",
        text("terminologyServer", "n/a"),
        "Empty or n/a: ConceptMap/$translate (⇄) runs locally, on the ConceptMaps of the Profile Packages (e.g. hl7.fhir.uv.v2mappings, or your own ConceptMap files). A base URL (http://terminology-server:8080/fhir, as the OIE server reaches it): on that terminology server, answers cached for 5 minutes."
      ),
      h(
        "details",
        { className: "span-2", style: { marginTop: "-4px" } },
        h("summary", { className: "text-text-faint", style: { cursor: "pointer", fontSize: "12px" } }, "Terminology server options"),
        h(
          "div",
          { className: "form-grid", style: { marginTop: "6px" } },
          field("Authorization", text("terminologyAuthorization", "'Bearer ' + $cfg('tx.token')", true), "JavaScript expression of the Authorization header, e.g. from the configuration map; empty for none."),
          field("Cache (seconds)", number("terminologyCacheSeconds", 300), "How long answers of the server are reused; 0 for not at all."),
          field("Timeout (seconds)", number("terminologyTimeoutSeconds", 10), "How long to wait for the server.")
        )
      ),
      field(
        "Output",
        h(
          "select",
          {
            value: element.output || "resource",
            onChange: (e) => {
              element.output = e.target.value;
              onChange();
              rerender();
            }
          },
          h("option", { value: "resource" }, "The resource is the outbound message"),
          h("option", { value: "bundle" }, "Add the resource to a Bundle")
        ),
        bundle
          ? "The FHIR Profile Mapper steps of this transformer add their resources to one Bundle, the outbound message. fhirRef('name') in a later step gives the fullUrl of a resource added under that name."
          : "The resource replaces the outbound message (with an outbound template, tmp)."
      ),
      field("Variable", text("variable", "the outbound message"), "Empty: the outbound message. Otherwise the resource (or Bundle) goes to the channel map as FHIR JSON under this name."),
      bundle
        ? h(
            "div",
            { className: "span-2 form-grid" },
            field(
              "Bundle Type",
              h(
                "select",
                {
                  value: element.bundleType || "transaction",
                  onChange: (e) => {
                    element.bundleType = e.target.value;
                    onChange();
                    rerender();
                  }
                },
                ["transaction", "batch", "collection", "document", "message"].map((v) => h("option", { key: v, value: v }, v))
              ),
              "Of the Bundle the first step creates."
            ),
            field("Bundle Profile", text("bundleProfile", "http://.../StructureDefinition/...-bundle"), "meta.profile of the Bundle, when this step creates it."),
            field("Reference Name", text("refName", "'patient'", true), "JavaScript expression: later steps get this resource's fullUrl with fhirRef(name), all of them with fhirRefs(name). Per item, e.g. 'obs-' + hl7Ordinal(hl7Preceding(obx, 'OBR'))."),
            element.bundleType === "transaction" || element.bundleType === "batch" || !element.bundleType
              ? field(
                  "Request",
                  h(
                    "div",
                    { style: { display: "flex", gap: "6px" } },
                    h(
                      "select",
                      {
                        value: element.requestMethod || "POST",
                        onChange: (e) => {
                          element.requestMethod = e.target.value;
                          onChange();
                          rerender();
                        }
                      },
                      ["POST", "PUT", "PATCH", "DELETE"].map((v) => h("option", { key: v, value: v }, v))
                    ),
                    text("requestUrl", "URL (empty: the resource type)", true)
                  ),
                  "Bundle.entry.request: method, and the URL as a JavaScript expression ('Patient?identifier=' + ... for a conditional update)."
                )
              : null,
            element.bundleType === "transaction" || element.bundleType === "batch" || !element.bundleType
              ? field("If-None-Exist", text("ifNoneExist", "'identifier=' + ...", true), "JavaScript expression: a conditional create (POST) only creates the resource when no other matches.")
              : null
          )
        : null,
      field(
        "Options",
        h(
          "label",
          { className: "check" },
          h("input", {
            type: "checkbox",
            checked: asBool(element.addMetaProfile, true),
            onChange: (e) => {
              element.addMetaProfile = e.target.checked;
              onChange();
              rerender();
            }
          }),
          " Add the profile to meta.profile"
        ),
        h(
          "label",
          { className: "check" },
          h("input", {
            type: "checkbox",
            checked: asBool(element.setLastUpdated, false),
            onChange: (e) => {
              element.setLastUpdated = e.target.checked;
              onChange();
              rerender();
            }
          }),
          " Set meta.lastUpdated"
        )
      )
    ),
    st.error ? h("div", { className: "hint", style: { color: "#b91c1c", marginTop: "6px" } }, st.error) : null,
    st.notice ? h("div", { className: "hint", style: { color: "#b45309", marginTop: "6px" } }, st.notice) : null,
    st.versionNotice
      ? h(
          "div",
          { className: "hint", style: { color: "#b45309", marginTop: "6px" } },
          st.versionNotice + " ",
          h(
            "button",
            {
              type: "button",
              className: "btn btn-sm",
              onClick: () => {
                element.profileVersion = st.rootIndex.sd.version || "";
                st.versionNotice = "";
                onChange();
                rerender();
              }
            },
            "Checked"
          )
        )
      : null,
    st.unresolved.length
      ? h(
          "div",
          { className: "hint", style: { color: "#b45309", marginTop: "6px" } },
          `Cannot find these mapped elements in the profile (editing is blocked so they are not lost): ${st.unresolved.join(", ")}. `,
          h("button", { type: "button", className: "btn btn-sm", onClick: removeUnresolved }, "Remove these mappings")
        )
      : null,
    tree
      ? h(
          "div",
          { style: { marginTop: "8px" } },
          h(
            "div",
            { className: "flex gap-2 mb-1.5", style: { alignItems: "center" } },
            h("span", { className: "text-text-faint" }, "Show"),
            h(
              "select",
              {
                value: st.filter,
                onChange: (e) => {
                  st.filter = e.target.value;
                  rerender();
                }
              },
              h("option", { value: "all" }, "All elements"),
              h("option", { value: "mustSupport" }, "Must Support and required"),
              h("option", { value: "mapped" }, "Mapped only")
            ),
            h(
              "label",
              { className: "check", title: "Also show extension, modifierExtension and id of every element (to add an extension the profile does not have)" },
              h("input", {
                type: "checkbox",
                checked: st.showAll,
                onChange: (e) => {
                  st.showAll = e.target.checked;
                  rerender();
                }
              }),
              " extensions & ids"
            ),
            h("span", { className: "text-text-faint", style: { fontSize: "11px" } }, "Drag a field from the Message Trees panel onto an element, or type any JavaScript expression. Empty values add nothing.")
          ),
          h(
            "div",
            { className: "dt-wrap border border-line rounded", style: { maxHeight: "none", overflowX: "auto", overflowY: "visible" } },
            h(
              "table",
              { className: "dt", style: { tableLayout: "fixed", width: "100%", minWidth: "720px" } },
              h("colgroup", null, h("col", { style: { width: "36%" } }), h("col", { style: { width: "14%" } }), h("col", { style: { width: "50%" } })),
              h("thead", null, h("tr", null, h("th", null, "Element"), h("th", null, "Type"), h("th", null, "Mapping"))),
              h("tbody", null, rows)
            )
          ),
          check.required.length || check.mustSupport.length
            ? h(
                "div",
                { className: "hint", style: { marginTop: "6px" } },
                check.required.length ? h("div", { style: { color: "#b91c1c" } }, `Required, not mapped: ${check.required.join(", ")}`) : null,
                check.mustSupport.length ? h("div", { style: { color: "#b45309" } }, `Must Support, not mapped: ${check.mustSupport.join(", ")}`) : null
              )
            : h("div", { className: "hint", style: { marginTop: "6px", color: "#15803d" } }, "Every required element below the mapped ones has a mapping or a fixed value."),
          h(
            "details",
            { style: { marginTop: "10px" } },
            h("summary", { style: { cursor: "pointer", fontWeight: 600 } }, "Test with a sample message"),
            h(
              "div",
              { style: { display: "flex", gap: "6px", alignItems: "center", margin: "6px 0" } },
              h(
                "select",
                {
                  value: st.sample.dataType,
                  onChange: (e) => {
                    st.sample.dataType = e.target.value;
                    saveSample();
                    rerender();
                  }
                },
                ["HL7V2", "XML", "JSON", "RAW"].map((v) => h("option", { key: v, value: v }, v))
              ),
              h("button", { type: "button", className: "btn btn-sm", disabled: st.testing || !st.sample.message.trim(), onClick: runTest }, st.testing ? "Running…" : "Run"),
              h("button", { type: "button", className: "btn btn-sm", onClick: showScript }, st.scriptResult ? "Hide the script" : "Generated script"),
              h("span", { className: "text-text-faint", style: { fontSize: "11px" } }, "Runs this step on the server and validates what it makes; fhirRef() of earlier steps gives stand-ins.")
            ),
            h("textarea", {
              rows: 6,
              value: st.sample.message,
              placeholder: "MSH|^~\\&|...",
              style: { width: "100%", fontFamily: "monospace", fontSize: "12px" },
              onChange: (e) => {
                st.sample.message = e.target.value;
                saveSample();
                rerender();
              }
            }),
            testResult
              ? h(
                  "div",
                  { style: { marginTop: "6px" } },
                  testResult.errors.map((e, i) => h("div", { key: `e${i}`, style: { color: "#b91c1c", fontSize: "12px" } }, (e.key ? `${e.key}: ` : "") + e.message)),
                  testResult.output !== null && testResult.output !== undefined
                    ? h(
                        "div",
                        null,
                        h(
                          "div",
                          { style: { fontSize: "12px", margin: "4px 0", color: testResult.issues.some((i) => i.severity === "error" || i.severity === "fatal") ? "#b91c1c" : "#15803d" } },
                          testResult.issues.length ? `${testResult.issues.length} validation issue(s):` : "Valid."
                        ),
                        testResult.issues.map((i, n) => h("div", { key: `i${n}`, style: { fontSize: "11px", fontFamily: "monospace" } }, `${i.severity} ${i.location}: ${i.message}`)),
                        h("pre", { style: { maxHeight: "360px", overflow: "auto", fontSize: "11px", background: "rgba(0,0,0,0.03)", padding: "6px" } }, prettyJson(testResult.output))
                      )
                    : null
                )
              : null,
            st.scriptResult
              ? h(
                  "div",
                  { style: { marginTop: "6px" } },
                  st.scriptResult.errors.map((e, i) => h("div", { key: `s${i}`, style: { color: "#b91c1c", fontSize: "12px" } }, (e.key ? `${e.key}: ` : "") + (e.line ? `line ${e.line}: ` : "") + e.message)),
                  h("pre", { style: { maxHeight: "360px", overflow: "auto", fontSize: "11px", background: "rgba(0,0,0,0.03)", padding: "6px" } }, st.scriptResult.script || "")
                )
              : null
          )
        )
      : null
  );
}

function prettyJson(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return String(text);
  }
}

function registerProfileMapper(platform) {
  React = platform.React;
  // The transformer editor names step types from this list (else from the class name).
  try {
    if (platform.oie && platform.oie.STEP_TYPES && !platform.oie.STEP_TYPES[MAPPER_TYPE]) platform.oie.STEP_TYPES[MAPPER_TYPE] = { label: "FHIR Profile Mapper" };
  } catch {
    // an administrator without that list: the class name is shown
  }
  platform.registerStepType(MAPPER_TYPE, {
    label: "FHIR Profile Mapper",
    create: () => ({
      __type: MAPPER_TYPE,
      name: "",
      enabled: true,
      fhirVersion: "R4",
      profiles: "",
      profileUrl: "",
      resourceType: "",
      profileVersion: "",
      addMetaProfile: true,
      setLastUpdated: false,
      variable: "",
      output: "resource",
      bundleType: "transaction",
      bundleProfile: "",
      refName: "",
      requestMethod: "POST",
      requestUrl: "",
      ifNoneExist: "",
      terminologyServer: "",
      terminologyAuthorization: "",
      terminologyCacheSeconds: 300,
      terminologyTimeoutSeconds: 10,
      mappings: "[]"
    }),
    validate: (el) => {
      if (!el.profileUrl || !String(el.profileUrl).trim()) return "Choose the target profile.";
      if (!el.resourceType) return "Load the target profile.";
      const entries = parseMappings(el.mappings);
      const noValue = entries.find((e) => e.translate && (e.translate.unmapped === "fixed" || e.translate.unmapped === "function") && !String(e.translate.value || "").trim());
      if (noValue) return `The translation of ${noValue.key} needs a ${noValue.translate.unmapped === "fixed" ? "fixed value" : "expression"} for codes without a match.`;
      const bad = entries.find((e) => typeof e.each === "string" && !/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(e.variable || ""));
      if (bad) return `The variable of the iteration on ${bad.key} must be a JavaScript name.`;
      if (el.output !== "bundle" && entries.some((e) => typeof e.each === "string" && Array.isArray(e.path) && !e.path.length))
        return "One resource for each item needs the output \"Add the resource to a Bundle\".";
      return "";
    },
    component: ProfileMapperEditor
  });
}

export function register(platform) {
  platform.registerDataType(DEF.name, DEF);
  registerProfileMapper(platform);
}
