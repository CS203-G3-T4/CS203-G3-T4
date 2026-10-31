// Total includes all response bodies, receipt timestamps and wrapper metadata (UTF-8 bytes).
const MAX_RESPONSE_BYTES=240000, MAX_SNAPSHOT_BYTES=800000;
function serializeSnapshot(snapshot) {
  const content=JSON.stringify(snapshot);
  if(Buffer.byteLength(content,'utf8')>MAX_SNAPSHOT_BYTES) throw Error('EMC snapshot exceeds limit');
  return content;
}
module.exports={MAX_RESPONSE_BYTES,MAX_SNAPSHOT_BYTES,serializeSnapshot};
