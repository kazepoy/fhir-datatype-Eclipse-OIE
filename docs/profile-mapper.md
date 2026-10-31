# FHIR Profile Mapper

Reference of the FHIR Profile Mapper transformer step of this fork (a proof of concept; see the [README](../README.md)). The demo in [`demo-oru2fhir/`](../demo-oru2fhir/) shows it on a lab result (ORU^R01 to a transaction Bundle).

A transformer step, in the **web administrator only**, that builds FHIR resources conforming to profiles from any inbound message, for example HL7 v2. Several steps can build one Bundle (a lab ORU to a transaction Bundle) without FML.

## Setting up

1. Add a step of type *FHIR Profile Mapper*. Set *FHIR Version* and *Profile Packages* (as in the data type properties), then the *Target Profile*: its canonical URL (*List profiles* lists those in the packages), or a resource type of the specification (`Patient`). Click *Load*.
2. The tree of the profile appears, from its snapshot: cardinality, type, Must Support (**S**), and the fixed and pattern values. Data types and extension profiles open when you expand them. Choice elements (`value[x]`) show one child per allowed type. *extensions & ids* also shows `extension`, `modifierExtension` and `id` of every element, to add an extension the profile does not define (map its `url` and `value[x]`).
3. Give elements a JavaScript expression: drag a field from the *Message Trees* panel (`msg['PID']['PID.5']['PID.5.1'].toString()`) or type one (`'final'`, `$('patientId')`, a code template function). `({})` on a complex element creates an entry that only gets fixed values.

Next to each mapping:

- **+** adds an entry to a repeating element.
- **↻** makes one entry per item of a list: *For each* `msg['PID']['PID.3']` *as* `id`. Below it, `id` is the repetition and `idIndex` its index (0, 1, …). Fields dragged, clicked or pasted in below an iteration are rewritten to its variable, as in OIE's Iterator step. Iterations nest, and an empty list adds nothing.
- **?** maps the element and what is below it only when a condition holds, per item of an iteration (`id['PID.3.5'].toString() == 'PI'`). A condition that fails on a missing segment (a `TypeError`) is false.
- **⇄** (on `code`, `string`, `Coding` and `CodeableConcept`) translates the value with `ConceptMap/$translate`: the ConceptMap (canonical URL, from a list), the system of the source code and optionally a target system. The first match (not `unmatched`, `disjoint` or `not-related-to`) gives the code, the Coding, or a CodeableConcept with that Coding (and the source coding, when kept). Without a match: leave empty, keep the source code, a fixed value (`code` or `system|code`), an expression of `code` and `system` (`traduireSexe(code)`; `null` for no value), or an error. *Test* translates a code from the editor.
  - *Terminology Server* empty or `n/a` (as `TX_SERVER` of matchbox): **local**, on the ConceptMaps of the *Profile Packages* (`hl7.fhir.uv.v2mappings`, your own ConceptMap files), with their unmapped rules (`provided`/`use-source-code`, `fixed`, `other-map`); read again after every deploy.
  - A base URL: on that server, as the OIE server reaches it (`http://terminology-server:8080/fhir`, a Docker service name works). *Terminology server options*: an `Authorization` header as an expression (`'Bearer ' + $cfg('tx.token')`), the cache (300 s; 0 for none) and the timeout (10 s).

Below the tree, the required elements (and the Must Support ones) that nothing maps yet are listed. *Test with a sample message* runs the step on the server with an HL7 v2, XML, JSON or raw message and validates what it makes; *Generated script* shows the script and its syntax errors, by element.

## Bundles

 With *Output* "Add the resource to a Bundle", the FHIR Profile Mapper steps of a transformer add their resources to one Bundle, which becomes the outbound message:

- *Bundle Type* (`transaction` by default) and *Bundle Profile*. A step whose target profile is a Bundle fills the Bundle itself (`identifier`, `timestamp`, `meta.profile`) instead of adding an entry.
- Every entry gets a `fullUrl` `urn:uuid:…`. *Reference Name* (an expression, e.g. `'patient'`) lets later steps refer to it: `fhirRef('patient')` is its fullUrl, `fhirRefs('obs-0')` all the fullUrls added under a name.
- **↻** and **?** on the resource row: one resource per item (`msg['OBX']` as `obx`), and a condition on the resource.
- For a transaction or batch, the request: method, URL (an expression: `'Patient?identifier=…|' + …` for a conditional update; empty for the resource type) and If-None-Exist.

OIE's HL7 v2 XML is flat (the segments of a group are siblings), so the expressions have helpers: `hl7Following(obr, 'OBX', ['OBR', 'ORC'])` (the OBX of an OBR), `hl7Preceding(obr, 'ORC')` (its ORC), `hl7Ordinal(obr)` (0, 1, … among the OBR), `fhirFind(list, fn)` and `fhirFilter(list, fn)` (`fhirFind(msg['PID']['PID.3'], function (cx) { return cx['PID.3.5'] == 'PI'; })`), and `fhirUuid()`. For example, one Observation per OBX named `'obs-' + hl7Ordinal(hl7Preceding(obx, 'OBR', []))`, and per OBR a DiagnosticReport whose `result` iterates `fhirRefs('obs-' + obrIndex)`.

## When the message is processed

- An expression that gives nothing (an empty field, or a missing repetition such as `msg['PID']['PID.3'][1]`) adds nothing: no element, no empty entry or object.
- Fixed and pattern values of the profile are added to the entries that got a value: slice discriminators (`identifier.system` of a slice), extension URLs, `Quantity.system`, required elements with a fixed value (`Bundle.type`, `Observation.category:laboratory`). A mapped value always wins; a pattern on a repeating element goes into a compatible item, or a new one.
- Values are converted to the type of the element: booleans, integers, decimals (written as given: `5.40` stays `5.40`), and HL7 v2 dates (`19800115` becomes `1980-01-15`, `202605031430` becomes `2026-05-03T14:30:00+02:00`; without a time zone in the message, the server's (`TZ`) is used; an `instant` without a time is an error).
- Every step sees the inbound message as `msg`, also after an earlier step made the outbound message.
- *Add the profile to meta.profile* (on by default) puts the profile in `meta.profile`, so *Validate Outbound* checks the result against it; *Set meta.lastUpdated* adds the time of the transformation.
- The resource (or the Bundle) becomes the outbound message: `tmp` when the connector has an outbound template (FHIR XML, or a JavaScript object, where decimals become numbers), otherwise `msg`, as FHIR JSON text. With *Variable* set, it goes as FHIR JSON to the channel map under that name instead. Set the outbound data type to FHIR.
- An error names the element: `FHIR Profile Mapper, Patient.gender: …`.

The step is saved with the compiled mappings (element paths, types, fixed values), so the server needs neither the web administrator nor the profile to run it; the profile is only read while editing. When the profile has a new version, the editor says so and lists the mapped elements it no longer has. The editor uses `/api/extensions/datatype-fhir` (`profiles`, `structure`, `conceptMaps`, `translate`, `script`, `test`; permission *Manage Channels*): `test` runs the step's JavaScript on the server, in a sandbox with the maps, `$cfg`, the code templates and a 10-second limit, as deploying the channel would. The Swing Administrator does not know this step: edit channels that use it in the web administrator.

## In scripts

The functions behind the translations are also in `FhirTools` (`var FhirTools = Packages.com.mirth.connect.plugins.datatypes.fhir.FhirTools;`):

`FhirTools.lookup(server, profilePackages, system, code)` gives the display of a code (`CodeSystem/$lookup`) and `FhirTools.validateCode(server, profilePackages, valueSetUrl, system, code)` whether it is in a value set (`ValueSet/$validate-code`), both locally when the server is `'n/a'`.

`FhirTools.translate(server, profilePackages, conceptMapUrl, system, code)` calls `ConceptMap/$translate` on the server, or locally on the ConceptMaps of the packages when the server is `''` or `'n/a'`, and returns the first match as JSON (`{"system", "code", "display"}`) or null; `FhirTools.translate(server, profilePackages, conceptMapUrl, system, code, targetSystem, 'R5')` for a target system or R5.
