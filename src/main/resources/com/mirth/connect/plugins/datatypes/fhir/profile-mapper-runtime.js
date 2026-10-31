// FHIR Profile Mapper runtime: builds FHIR JSON resources from element paths.
// A path is a list of segments [name] (single element) or [name, item] (an item of a repeating
// element; items with the same key are the same array entry; an iteration adds "*" and the index
// to the key). Repeating elements are collected in FhirArray objects while mapping and become JSON
// arrays in finish(), without the items that got no value, so an empty source field never leaves
// an empty entry, an empty object or a null behind. Decimals are kept as written (1.50) in the
// JSON text and XML the step writes.
(function () {
    var global = this;
    var FhirTools = Packages.com.mirth.connect.plugins.datatypes.fhir.FhirTools;
    var FhirTerminology = Packages.com.mirth.connect.plugins.datatypes.fhir.FhirTerminology;

    function FhirArray() {
        this.keys = [];
        this.items = {};
    }

    // A FHIR decimal with its exact text. Recognized by its mark, not instanceof: every step has
    // its own runtime, and the Bundle carries the decimals of one step to the step that writes it.
    function Decimal(text) {
        this.text = text;
        this.__fhirDecimal = true;
    }

    function isDecimal(value) {
        return value !== null && typeof value === 'object' && value.__fhirDecimal === true;
    }

    function has(obj, key) {
        return Object.prototype.hasOwnProperty.call(obj, key);
    }

    function isObject(value) {
        return value !== null && typeof value === 'object' && !(value instanceof FhirArray) && !isDecimal(value)
                && Object.prototype.toString.call(value) === '[object Object]';
    }

    function isArray(value) {
        return Object.prototype.toString.call(value) === '[object Array]';
    }

    function child(node, seg, create) {
        var name = seg[0];
        if (seg.length < 2) {
            if ((node[name] === undefined || node[name] === null) && create) {
                node[name] = {};
            }
            return node[name];
        }
        var arr = node[name];
        if (!(arr instanceof FhirArray)) {
            if (!create) {
                return undefined;
            }
            arr = node[name] = new FhirArray();
        }
        if (!has(arr.items, seg[1])) {
            if (!create) {
                return undefined;
            }
            arr.keys.push(seg[1]);
            arr.items[seg[1]] = {};
        }
        return arr.items[seg[1]];
    }

    function get(node, seg) {
        var value = node[seg[0]];
        if (seg.length < 2) {
            return value;
        }
        return value instanceof FhirArray && has(value.items, seg[1]) ? value.items[seg[1]] : undefined;
    }

    function put(node, seg, value) {
        var name = seg[0];
        if (seg.length < 2) {
            node[name] = value;
            return;
        }
        var arr = node[name];
        if (!(arr instanceof FhirArray)) {
            arr = node[name] = new FhirArray();
        }
        if (!has(arr.items, seg[1])) {
            arr.keys.push(seg[1]);
        }
        arr.items[seg[1]] = value;
    }

    function copy(value) {
        if (isArray(value)) {
            var list = [];
            for (var i = 0; i < value.length; i++) {
                list.push(copy(value[i]));
            }
            return list;
        }
        if (isObject(value)) {
            var out = {};
            for (var key in value) {
                if (has(value, key)) {
                    out[key] = copy(value[key]);
                }
            }
            return out;
        }
        return value;
    }

    // Whether an item can take a pattern entry: nothing in it contradicts the pattern.
    function compatible(item, pattern) {
        if (!isObject(item) || !isObject(pattern)) {
            return String(item) === String(pattern);
        }
        for (var key in pattern) {
            if (has(pattern, key) && item[key] !== undefined && item[key] !== null && !(item[key] instanceof FhirArray) && !isArray(item[key])
                    && !compatible(item[key], pattern[key])) {
                return false;
            }
        }
        return true;
    }

    // Fixed and pattern values: added where nothing was mapped, never over a mapped value. Each
    // entry of a pattern on a repeating element goes into the first compatible item, or a new one.
    function merge(target, value) {
        for (var key in value) {
            if (!has(value, key)) {
                continue;
            }
            if (target[key] === undefined || target[key] === null) {
                target[key] = copy(value[key]);
            } else if (isObject(target[key]) && isObject(value[key])) {
                merge(target[key], value[key]);
            } else if (target[key] instanceof FhirArray && isArray(value[key])) {
                var arr = target[key];
                for (var p = 0; p < value[key].length; p++) {
                    var entry = value[key][p];
                    var found = null;
                    for (var i = 0; i < arr.keys.length && found === null; i++) {
                        if (compatible(arr.items[arr.keys[i]], entry)) {
                            found = arr.items[arr.keys[i]];
                        }
                    }
                    if (found !== null && isObject(found) && isObject(entry)) {
                        merge(found, entry);
                    } else if (found === null) {
                        put(target, [key, '#pattern' + p], copy(entry));
                    }
                }
            }
        }
    }

    function pad(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // HL7 v2 DT / TS (YYYY[MM[DD[HH[MM[SS[.S+]]]]]][+/-ZZZZ]) to FHIR date / dateTime / instant.
    // Values already in FHIR form (with '-' in the date) are kept. Without a time zone, the server's
    // (TZ) offset of that date and time is used: FHIR requires one as soon as there is a time.
    var TS = /^(\d{4})(\d{2})?(\d{2})?(?:(\d{2})(\d{2})?(\d{2})?(\.\d+)?)?([+-]\d{4})?$/;

    function dateValue(s, type, element) {
        var m = TS.exec(s);
        if (!m) {
            return s;
        }
        var out = m[1] + (m[2] ? '-' + m[2] + (m[3] ? '-' + m[3] : '') : '');
        if (type === 'date' || !m[4] || !m[3]) {
            if (type === 'instant') {
                throw new Error(element + ': "' + s + '" has no time, which an instant needs.');
            }
            return out;
        }
        var zone = m[8];
        if (zone) {
            zone = zone.substring(0, 3) + ':' + zone.substring(3);
        } else {
            var local = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5] || 0), Number(m[6] || 0));
            var offset = -local.getTimezoneOffset();
            zone = (offset < 0 ? '-' : '+') + pad(Math.floor(Math.abs(offset) / 60)) + ':' + pad(Math.abs(offset) % 60);
        }
        return out + 'T' + m[4] + ':' + (m[5] || '00') + ':' + (m[6] || '00') + (m[7] || '') + zone;
    }

    function timeValue(s) {
        var m = /^(\d{2})(\d{2})?(\d{2})?(\.\d+)?$/.exec(s);
        return m ? m[1] + ':' + (m[2] || '00') + ':' + (m[3] || '00') + (m[4] || '') : s;
    }

    function text(value) {
        if (value === undefined || value === null) {
            return '';
        }
        return String(typeof value === 'xml' ? value.toString() : value).trim();
    }

    // The value of a mapping in the JSON type of the element, or undefined when there is none.
    function convert(value, type, element) {
        if (value === undefined || value === null) {
            return undefined;
        }
        if (typeof value === 'xml') {
            value = value.toString();
        } else if (value instanceof java.lang.String) {
            value = String(value);
        } else if (isDecimal(value)) {
            return value;
        } else if (typeof value === 'object') {
            return value; // a JavaScript object or array for a complex element, used as it is
        }
        if (typeof value === 'boolean' || (typeof value === 'number' && type !== 'decimal')) {
            return value;
        }
        var s = String(value).trim();
        if (s === '') {
            return undefined;
        }
        switch (type) {
            case 'boolean':
                if (/^(true|t|y|yes|1)$/i.test(s)) {
                    return true;
                }
                if (/^(false|f|n|no|0)$/i.test(s)) {
                    return false;
                }
                throw new Error(element + ': "' + s + '" is not a boolean.');
            case 'integer':
            case 'positiveInt':
            case 'unsignedInt':
                if (!/^[+-]?\d+$/.test(s)) {
                    throw new Error(element + ': "' + s + '" is not an integer.');
                }
                return parseInt(s, 10);
            case 'decimal':
                if (!/^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$/.test(s)) {
                    throw new Error(element + ': "' + s + '" is not a decimal.');
                }
                return new Decimal(s.replace(/^\+/, '').replace(/^(-?)\./, '$10.').replace(/\.$/, ''));
            case 'date':
            case 'dateTime':
            case 'instant':
                return dateValue(s, type, element);
            case 'time':
                return timeValue(s);
            default:
                return s;
        }
    }

    function coding(c) {
        var out = {};
        if (c.system) {
            out.system = String(c.system);
        }
        out.code = String(c.code);
        if (c.display) {
            out.display = String(c.display);
        }
        return out;
    }

    // "system|code" or "code" as a coding; a JavaScript object is taken as a coding already.
    function codingOf(value, system) {
        if (value !== null && typeof value === 'object' && typeof value !== 'xml' && !(value instanceof java.lang.String)) {
            return value.code === undefined || value.code === null || String(value.code) === '' ? undefined : value;
        }
        var s = text(value);
        if (s === '') {
            return undefined;
        }
        var bar = s.lastIndexOf('|');
        return bar < 0 ? {system: system, code: s} : {system: s.substring(0, bar), code: s.substring(bar + 1)};
    }

    // ConceptMap/$translate of a source code (on the terminology server, or locally on the
    // ConceptMaps of the profiles without one), as the type of the element: the code for a
    // primitive, the Coding, or a CodeableConcept with that Coding (and the source coding when kept).
    function translateValue(value, translation, type, element, options) {
        var code = text(value);
        if (code === '') {
            return undefined;
        }
        var source = translation.source || '';
        var match = FhirTerminology.translate(options.terminologyServer, options.profiles, translation.conceptMap, source, code, translation.target || '',
                options.fhirVersion, options.authorization || '', options.cacheSeconds, options.timeoutSeconds);
        var c;
        if (match !== null && match !== undefined) {
            c = JSON.parse(String(match));
        } else if (translation.unmapped === 'source') {
            c = {system: source, code: code};
        } else if (translation.unmapped === 'fixed') {
            c = codingOf(translation.value, translation.target || '');
        } else if (translation.unmapped === 'function') {
            c = codingOf(translation.fallback(code, source), translation.target || '');
        } else if (translation.unmapped === 'error') {
            throw new Error('FHIR Profile Mapper, ' + element + ': code "' + code + '"' + (source ? ' of ' + source : '') + ' is not in ConceptMap ' + translation.conceptMap + '.');
        } else {
            return undefined;
        }
        if (c === undefined) {
            return undefined;
        }
        if (type === 'Coding') {
            return coding(c);
        }
        if (type === 'CodeableConcept') {
            var codings = [coding(c)];
            if (translation.keepSource && !(String(c.code) === code && String(c.system || '') === source)) {
                codings.push(coding({system: source, code: code}));
            }
            return {coding: codings};
        }
        return String(c.code);
    }

    // The items of an iteration: the nodes of an E4X list, the elements of an array or Java List,
    // or the value itself.
    function items(list) {
        if (list === undefined || list === null) {
            return [];
        }
        var out = [];
        var i;
        if (typeof list === 'xml') {
            for (i = 0; i < list.length(); i++) {
                out.push(list[i]);
            }
        } else if (list instanceof java.util.List) {
            for (i = 0; i < list.size(); i++) {
                out.push(list.get(i));
            }
        } else if (typeof list === 'object' && typeof list.length === 'number') {
            for (i = 0; i < list.length; i++) {
                out.push(list[i]);
            }
        } else {
            out.push(list);
        }
        return out;
    }

    function empty(value) {
        return value === undefined || value === null || (isArray(value) && value.length === 0) || (isObject(value) && Object.keys(value).length === 0);
    }

    // FhirArrays become arrays; empty objects and arrays (an entry that only got nothing) go.
    function finish(value) {
        var i;
        if (value instanceof FhirArray) {
            var list = [];
            for (i = 0; i < value.keys.length; i++) {
                var item = finish(value.items[value.keys[i]]);
                if (!empty(item)) {
                    list.push(item);
                }
            }
            return list;
        }
        if (isArray(value)) {
            var out = [];
            for (i = 0; i < value.length; i++) {
                var element = finish(value[i]);
                if (!empty(element)) {
                    out.push(element);
                }
            }
            return out;
        }
        if (isObject(value)) {
            for (var key in value) {
                if (has(value, key)) {
                    value[key] = finish(value[key]);
                    if (empty(value[key])) {
                        delete value[key];
                    }
                }
            }
        }
        return value;
    }

    // JSON text with the decimals as written.
    function json(value) {
        if (isDecimal(value)) {
            return value.text;
        }
        if (isArray(value)) {
            var parts = [];
            for (var i = 0; i < value.length; i++) {
                parts.push(json(value[i]));
            }
            return '[' + parts.join(',') + ']';
        }
        if (isObject(value)) {
            var fields = [];
            for (var key in value) {
                if (has(value, key) && value[key] !== undefined) {
                    fields.push(JSON.stringify(key) + ':' + json(value[key]));
                }
            }
            return '{' + fields.join(',') + '}';
        }
        if (value === null || value === undefined) {
            return 'null';
        }
        if (typeof value === 'number' || typeof value === 'boolean') {
            return String(value);
        }
        return JSON.stringify(String(value));
    }

    // A JavaScript object for a JSON template (tmp): decimals become numbers.
    function plain(value) {
        return JSON.parse(json(value));
    }

    // What the steps of one message share: the Bundle and the fullUrl of each named resource.
    function state() {
        if (!global.__fhirProfileMapper) {
            global.__fhirProfileMapper = {bundle: null, refs: {}};
        }
        return global.__fhirProfileMapper;
    }

    if (typeof global.fhirUuid !== 'function') {
        // A random UUID, e.g. for an id: fhirUuid().
        global.fhirUuid = function () {
            return String(java.util.UUID.randomUUID());
        };
    }
    if (typeof global.fhirRef !== 'function') {
        // The fullUrl (urn:uuid:...) of a resource an earlier FHIR Profile Mapper step added to the
        // Bundle under that reference name; index for a step that made several (0 by default).
        global.fhirRef = function (name, index) {
            var list = state().refs[name];
            return list ? list[index === undefined ? 0 : index] : undefined;
        };
        // All fullUrls added under a reference name, in order (an empty array for none).
        global.fhirRefs = function (name) {
            return (state().refs[name] || []).slice();
        };
    }

    if (typeof global.fhirFind !== 'function') {
        // The first item of a list (E4X list, array) the function holds for, or undefined:
        // fhirFind(msg['PID']['PID.3'], function (cx) { return cx['PID.3.5'] == 'PI'; })['PID.3.1']
        global.fhirFind = function (list, fn) {
            var all = items(list);
            for (var i = 0; i < all.length; i++) {
                if (fn(all[i], i)) {
                    return all[i];
                }
            }
            return undefined;
        };
        // The items of a list the function holds for, as an array (to iterate on).
        global.fhirFilter = function (list, fn) {
            var all = items(list);
            var out = [];
            for (var i = 0; i < all.length; i++) {
                if (fn(all[i], i)) {
                    out.push(all[i]);
                }
            }
            return out;
        };
    }

    // HL7 v2 XML of OIE is flat: the segments of a group are siblings. These find a group's segments.
    function segment(seg) {
        return typeof seg === 'xml' && seg.length() === 1 ? seg[0] : seg;
    }

    function stopsOf(seg, stop) {
        return stop === undefined || stop === null ? [seg.name().toString()] : [].concat(stop);
    }

    if (typeof global.hl7Following !== 'function') {
        // The segments named `name` after `seg`, up to the next segment named in `stop` (default: the
        // name of seg): hl7Following(obr, 'OBX', ['OBR', 'ORC']) is the OBX of an OBR.
        global.hl7Following = function (seg, name, stop) {
            seg = segment(seg);
            var stops = stopsOf(seg, stop);
            var siblings = seg.parent().children();
            var out = [];
            for (var i = seg.childIndex() + 1; i < siblings.length(); i++) {
                var n = siblings[i].name().toString();
                if (stops.indexOf(n) >= 0) {
                    break;
                }
                if (n === name) {
                    out.push(siblings[i]);
                }
            }
            return out;
        };
        // The closest segment named `name` before `seg`, not past a segment named in `stop` (default:
        // the name of seg): hl7Preceding(obr, 'ORC') is the ORC of an OBR; undefined when there is none.
        global.hl7Preceding = function (seg, name, stop) {
            seg = segment(seg);
            var stops = stopsOf(seg, stop);
            var siblings = seg.parent().children();
            for (var i = seg.childIndex() - 1; i >= 0; i--) {
                var n = siblings[i].name().toString();
                if (n === name) {
                    return siblings[i];
                }
                if (stops.indexOf(n) >= 0) {
                    return undefined;
                }
            }
            return undefined;
        };
        // The position (0, 1, ...) of a segment among the segments of its name: hl7Ordinal(obr).
        global.hl7Ordinal = function (seg) {
            seg = segment(seg);
            var siblings = seg.parent().children();
            var name = seg.name().toString();
            var n = 0;
            for (var i = 0; i < seg.childIndex(); i++) {
                if (siblings[i].name().toString() === name) {
                    n++;
                }
            }
            return n;
        };
    }

    return {
        // terminologyServer, profiles, fhirVersion, authorization, cacheSeconds, timeoutSeconds of the step
        options: {},

        // Sets the value of an element; the expression runs here, so a TypeError (a missing segment
        // or repetition in the source, e.g. msg['PID']['PID.3'][1] of one PID.3) is "no value".
        // translation: null, or {conceptMap, source, target, unmapped, value, keepSource, fallback}.
        set: function (root, path, type, expression, element, translation) {
            var value;
            try {
                value = expression();
            } catch (e) {
                if (e instanceof TypeError) {
                    return;
                }
                throw new Error('FHIR Profile Mapper, ' + element + ': ' + e.message);
            }
            if (translation) {
                value = translateValue(value, translation, type, element, this.options);
            }
            value = convert(value, type, element);
            if (value === undefined) {
                return;
            }
            var node = root;
            for (var i = 0; i < path.length - 1; i++) {
                node = child(node, path[i], true);
            }
            put(node, path[path.length - 1], value);
        },

        // A fixed or pattern value, set only when its container (the first `when` segments) got a value.
        fixed: function (root, path, when, value) {
            var node = root;
            for (var i = 0; i < when; i++) {
                node = child(node, path[i], false);
                if (node === undefined || node === null) {
                    return;
                }
            }
            if (when === path.length) {
                if (isObject(node) && isObject(value)) {
                    merge(node, value);
                }
                return;
            }
            for (var j = when; j < path.length - 1; j++) {
                node = child(node, path[j], true);
            }
            var last = path[path.length - 1];
            var current = get(node, last);
            if (current === undefined || current === null) {
                put(node, last, copy(value));
            } else if (isObject(current) && isObject(value)) {
                merge(current, value);
            } else if (current instanceof FhirArray && isArray(value)) {
                var holder = {};
                holder[last[0]] = current;
                merge(holder, (function () {
                    var p = {};
                    p[last[0]] = value;
                    return p;
                })());
            }
        },

        // Whether a condition holds; a TypeError (a missing segment) is false.
        when: function (expression, element) {
            try {
                return !!expression();
            } catch (e) {
                if (e instanceof TypeError) {
                    return false;
                }
                throw new Error('FHIR Profile Mapper, condition of ' + element + ': ' + e.message);
            }
        },

        // Runs body(item, index) for each item of the list the expression gives; nothing when it
        // gives nothing (or a TypeError: a missing segment).
        each: function (expression, body, element) {
            var list;
            try {
                list = expression();
            } catch (e) {
                if (e instanceof TypeError) {
                    return;
                }
                throw new Error('FHIR Profile Mapper, for each ' + element + ': ' + e.message);
            }
            var all = items(list);
            for (var i = 0; i < all.length; i++) {
                body(all[i], i);
            }
        },

        finish: finish,
        json: json,
        plain: plain,

        // Adds a resource to the Bundle of the message (created by the first step that adds one),
        // with a new urn:uuid fullUrl, remembered under the reference name for fhirRef().
        entry: function (bundleType, refName, resource, request, bundleProfile) {
            var s = state();
            if (s.bundle === null) {
                s.bundle = {resourceType: 'Bundle', type: bundleType, entry: []};
                if (bundleProfile) {
                    s.bundle.meta = {profile: [bundleProfile]};
                    s.bundle = {resourceType: 'Bundle', meta: s.bundle.meta, type: bundleType, entry: []};
                }
            }
            var fullUrl = 'urn:uuid:' + String(java.util.UUID.randomUUID());
            var entry = {fullUrl: fullUrl, resource: resource};
            if (request) {
                entry.request = request;
            }
            s.bundle.entry.push(entry);
            if (refName) {
                (s.refs[refName] = s.refs[refName] || []).push(fullUrl);
            }
            return fullUrl;
        },

        // The inbound message as the first FHIR Profile Mapper step of the transformer found it: the
        // expressions of every step see it as msg, also after a step made msg the outbound FHIR.
        source: function (msg) {
            var s = state();
            if (!has(s, 'source')) {
                s.source = msg;
            }
            return s.source;
        },

        // A step whose resource is a Bundle: its elements (identifier, timestamp, meta...) go to the
        // Bundle of the message, which keeps its entries and type.
        header: function (bundleType, resource) {
            var s = state();
            if (s.bundle === null) {
                s.bundle = {resourceType: 'Bundle', type: bundleType, entry: []};
            }
            var merged = {resourceType: 'Bundle'};
            var key;
            for (key in resource) {
                if (has(resource, key) && key !== 'resourceType' && key !== 'entry') {
                    merged[key] = resource[key];
                }
            }
            for (key in s.bundle) {
                if (has(s.bundle, key) && !has(merged, key)) {
                    merged[key] = s.bundle[key];
                }
            }
            s.bundle = merged;
        },

        // The Bundle of the message so far.
        bundle: function () {
            return state().bundle;
        },

        // The request of a transaction or batch entry: url and ifNoneExist from expressions.
        request: function (method, resource, url, ifNoneExist) {
            var u = text(url());
            if (u === '') {
                u = method === 'POST' ? resource.resourceType : (resource.id ? resource.resourceType + '/' + resource.id : '');
            }
            if (u === '') {
                throw new Error('FHIR Profile Mapper: a ' + method + ' entry needs a request URL (or an id).');
            }
            var request = {method: method, url: u};
            var none = text(ifNoneExist());
            if (none !== '') {
                request.ifNoneExist = none;
            }
            return request;
        }
    };
})()
