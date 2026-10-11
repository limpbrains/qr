// One record per line: objects print one field per line, arrays of scalars and
// "flat" objects (scalar values only) print inline, arrays of records print one
// record per line. Keeps fixture diffs readable without changing their content.
const isScalar = (v) => v === null || typeof v !== 'object';
const isScalarArray = (v) => Array.isArray(v) && v.every(isScalar);
const isFlat = (v) => isScalar(v) || isScalarArray(v) || (!Array.isArray(v) && Object.values(v).every((x) => isScalar(x) || isScalarArray(x)));

export function compactJson(v, indent = '') {
  if (isScalar(v)) return JSON.stringify(v);
  const inner = indent + ' ';
  if (Array.isArray(v)) {
    if (v.every(isScalar)) return JSON.stringify(v);
    if (v.every(isFlat)) return '[\n' + v.map((e) => inner + JSON.stringify(e)).join(',\n') + '\n' + indent + ']';
    return '[\n' + v.map((e) => inner + compactJson(e, inner)).join(',\n') + '\n' + indent + ']';
  }
  const keys = Object.keys(v);
  if (isFlat(v)) return JSON.stringify(v);
  return '{\n' + keys.map((k) => `${inner}${JSON.stringify(k)}: ${compactJson(v[k], inner)}`).join(',\n') + '\n' + indent + '}';
}
