const {test}=require('node:test');
const assert=require('node:assert/strict');
const {MAX_SNAPSHOT_BYTES,serializeSnapshot}=require('../../../scripts/emc-snapshot-limits.cjs');
test('total EMC snapshot limit includes wrapper and counts UTF-8 bytes',()=>{
  const overhead=Buffer.byteLength(JSON.stringify({body:''}));
  assert.equal(Buffer.byteLength(serializeSnapshot({body:'a'.repeat(MAX_SNAPSHOT_BYTES-overhead)})),MAX_SNAPSHOT_BYTES);
  assert.throws(()=>serializeSnapshot({body:'a'.repeat(MAX_SNAPSHOT_BYTES-overhead+1)}),/exceeds limit/);
  assert.throws(()=>serializeSnapshot({body:'😀'.repeat(210000)}),/exceeds limit/);
});
