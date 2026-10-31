// Tests of the FHIR Profile Mapper editor logic (web/plugin.js): node --test webadmin/test/
// The module is loaded without its export, on small hand-made StructureDefinitions.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const code = fs.readFileSync(path.join(here, "../web/plugin.js"), "utf8").replace("export function register", "function register");
const context = vm.createContext({ console });
vm.runInContext(code + "\nthis.t = { indexSd, makeTree, compile, substitute, inserted, completeness, instancesOf, sdLoaded, sdKey };", context);
const { indexSd, makeTree, compile, substitute, inserted, completeness, instancesOf, sdLoaded, sdKey } = context.t;

const el = (id, min, max, types, more = {}) => ({ id, path: id.replace(/:[^.]+/g, ""), min, max, base: { max: more.baseMax ?? max }, type: types.map((t) => (typeof t === "string" ? { code: t } : t)), ...more });

const patient = indexSd({
  resourceType: "StructureDefinition",
  url: "http://example.org/StructureDefinition/patient",
  version: "1.0.0",
  type: "Patient",
  snapshot: {
    element: [
      { id: "Patient", path: "Patient", min: 0, max: "*" },
      el("Patient.id", 0, "1", ["http://hl7.org/fhirpath/System.String"]),
      el("Patient.extension", 0, "*", ["Extension"]),
      el("Patient.identifier", 1, "*", ["Identifier"], { slicing: { discriminator: [{ type: "value", path: "system" }] } }),
      el("Patient.identifier:ipp", 0, "1", ["Identifier"], { sliceName: "ipp", baseMax: "*" }),
      el("Patient.identifier:ipp.system", 0, "1", ["uri"], { fixedUri: "urn:ipp" }),
      el("Patient.identifier:ipp.value", 0, "1", ["string"]),
      el("Patient.gender", 1, "1", ["code"], { mustSupport: true }),
      el("Patient.deceased[x]", 0, "1", ["boolean", "dateTime"]),
      el("Patient.name", 0, "*", ["HumanName"], { mustSupport: true })
    ]
  }
});
const humanName = indexSd({
  resourceType: "StructureDefinition",
  url: "http://hl7.org/fhir/StructureDefinition/HumanName",
  type: "HumanName",
  snapshot: {
    element: [
      { id: "HumanName", path: "HumanName", min: 0, max: "*" },
      el("HumanName.id", 0, "1", ["http://hl7.org/fhirpath/System.String"]),
      el("HumanName.family", 0, "1", ["string"]),
      el("HumanName.given", 0, "*", ["string"])
    ]
  }
});
sdLoaded.set(sdKey("R4", "", "http://hl7.org/fhir/StructureDefinition/HumanName"), humanName);

const tree = (keys = [], showAll = false) => makeTree(patient, { version: "R4", profiles: "", instances: instancesOf(keys), showAll });
// Objects of the vm context have other prototypes: compare them as plain JSON.
const plain = (x) => JSON.parse(JSON.stringify(x));
const labels = (t, node) => (t.children(node) || []).map((n) => n.label);

test("the tree hides ids and unsliced extensions unless asked, and opens data types", () => {
  const t = tree();
  assert.deepEqual(plain(labels(t, t.root)), ["id", "identifier", "identifier:ipp", "gender", "deceased[x]", "name"]);
  assert.deepEqual(plain(labels(t, t.chain("Patient.deceased[x]").at(-1))), ["deceasedBoolean", "deceasedDateTime"]);
  assert.deepEqual(plain(labels(t, t.chain("Patient.name").at(-1))), ["family", "given"]);
  const all = tree([], true);
  assert.ok(labels(all, all.root).includes("extension"));
  assert.ok(labels(all, all.chain("Patient.name").at(-1)).includes("id"));
});

test("compile: values in tree order, slices, instances, fixed discriminators", () => {
  const values = new Map([
    ["Patient.name.given[1]", "'B'"],
    ["Patient.name.given", "'A'"],
    ["Patient.identifier:ipp.value", "msg['PID']['PID.3']['PID.3.1']"],
    ["Patient.deceased[x].deceasedBoolean", "'false'"]
  ]);
  const entries = compile(tree([...values.keys()]), values, new Map(), new Map(), new Map());
  assert.deepEqual(
    plain(entries.map((e) => [e.key, JSON.stringify(e.path)])),
    [
      ["Patient.identifier:ipp.value", '[["identifier","ipp#0"],["value"]]'],
      ["Patient.deceased[x].deceasedBoolean", '[["deceasedBoolean"]]'],
      ["Patient.name.given", '[["name","#0"],["given","#0"]]'],
      ["Patient.name.given[1]", '[["name","#0"],["given","#1"]]'],
      ["Patient.identifier:ipp.system", '[["identifier","ipp#0"],["system"]]']
    ]
  );
  assert.equal(entries.at(-1).fixed, "urn:ipp");
  assert.equal(entries.at(-1).when, 1);
});

test("compile: iterations, conditions and translations, also on the resource", () => {
  const values = new Map([["Patient.identifier.value", "id['PID.3.1']"], ["Patient.gender", "msg['PID']['PID.8']"]]);
  const iterations = new Map([["Patient.identifier", { expression: "msg['PID']['PID.3']", variable: "id" }], ["Patient", { expression: "msg['PID']", variable: "pid" }], ["Patient.gender", { expression: "x", variable: "g" }]]);
  const conditions = new Map([["Patient.identifier", "id['PID.3.5'] == 'PI'"], ["Patient", "true"]]);
  const translations = new Map([["Patient.gender", { conceptMap: "http://cm", source: "urn:v2", unmapped: "fixed", value: "unknown" }]]);
  const entries = compile(tree([...values.keys()]), values, iterations, translations, conditions);
  const find = (pred) => entries.find(pred);
  assert.deepEqual(plain(find((e) => e.each && e.key === "Patient").path), []);
  assert.deepEqual(plain(find((e) => e.condition && e.key === "Patient").path), []);
  assert.deepEqual(plain(find((e) => e.each && e.key === "Patient.identifier").path), [["identifier", "#0"]]);
  assert.equal(find((e) => e.condition && e.key === "Patient.identifier").condition, "id['PID.3.5'] == 'PI'");
  assert.equal(entries.filter((e) => e.each).length, 2, "gender does not repeat: no iteration");
  assert.deepEqual(plain(find((e) => e.key === "Patient.gender" && e.expression).translate), { conceptMap: "http://cm", source: "urn:v2", target: "", unmapped: "fixed", keepSource: false, value: "unknown" });
});

test("fields dragged or clicked in below an iteration use its variable", () => {
  const its = [{ expression: "msg['PID']['PID.3']", variable: "id" }, { expression: "msg['PID']", variable: "p" }];
  assert.equal(substitute("msg['PID']['PID.3']['PID.3.1'].toString()", its), "id['PID.3.1'].toString()");
  assert.equal(substitute("msg['PID']['PID.3'][0]['PID.3.1']", its), "id['PID.3.1']");
  assert.equal(substitute("msg['PID']['PID.30']", its), "p['PID.30']");
  assert.equal(substitute("msg['OBX']", its), "msg['OBX']");
  assert.deepEqual(plain(inserted("ab", "aXYZb")), { start: 1, end: 4, text: "XYZ" });
  assert.equal(inserted("ab", "aXb"), null, "typing one character is no insertion");
});

test("completeness: required and Must Support elements nothing maps", () => {
  const t = tree();
  assert.deepEqual(plain(completeness(t, [])), { required: ["Patient.identifier", "Patient.gender"], mustSupport: ["Patient.name"] });
  const mapped = completeness(t, [{ key: "Patient.identifier:ipp.value" }, { key: "Patient.gender" }, { element: "Patient.name.family" }]);
  assert.deepEqual(plain(mapped), { required: [], mustSupport: [] });
});
