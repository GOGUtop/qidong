/* Node-only integration smoke test for the WebView JS side of Blob export. */
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/blob_export.js', 'utf8');

function fixture() {
  const saved = [];
  const alerts = [];
  const bridge = { onmessage: null, postMessage(raw) {
    const msg = JSON.parse(raw);
    if (msg.kind === 'start') {
      this.active = { name: msg.name, expected: msg.size, mime: msg.mime, parts: [] };
    } else if (msg.kind === 'chunk') {
      this.active.parts.push(Buffer.from(msg.data, 'base64'));
    } else if (msg.kind === 'finish') {
      const result = Buffer.concat(this.active.parts);
      assert.equal(result.length, this.active.expected);
      saved.push({ name: this.active.name, data: result });
      this.active = null;
    } else if (msg.kind === 'cancel') {
      this.active = null;
    }
    setTimeout(() => bridge.onmessage?.({ data: JSON.stringify({
      id: msg.id, kind: msg.kind, seq: msg.seq || 0, ok: true, error: ''
    }) }), 0);
  } };
  let next = 0;
  const objectUrls = new Map();
  const URLMock = {
    createObjectURL(blob) { const url = `blob:mock/${++next}`; objectUrls.set(url, blob); return url; },
    revokeObjectURL(url) { objectUrls.delete(url); }
  };
  class Anchor {
    constructor(url, name) { this.href = url; this.download = name; }
    getAttribute(key) { return key === 'download' ? this.download : null; }
    click() { throw Error('Original click should be intercepted for blob/data'); }
  }
  const handlers = {};
  const context = {
    STNativeExport: bridge,
    URL: URLMock, Blob, HTMLAnchorElement: Anchor,
    document: { addEventListener(type, handler) { handlers[type] = handler; } },
    setTimeout: (fn, ms) => { const timer = setTimeout(fn, ms); timer.unref?.(); return timer; },
    clearTimeout,
    btoa: str => Buffer.from(str, 'binary').toString('base64'),
    atob: str => Buffer.from(str, 'base64').toString('binary'),
    fetch: async () => { throw new TypeError('Failed to fetch'); },
    console,
    alert: msg => alerts.push(msg),
    Uint8Array, Date, Math, Promise, decodeURIComponent,
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(script, context);
  return { context, Anchor, bridge, saved, alerts, handlers, objectUrls };
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  const f = fixture();
  assert.equal(typeof f.context.__stLauncherTestExport, 'function');
  f.context.__stLauncherTestExport();
  await sleep(60);
  assert.equal(f.saved.length, 1);
  assert.equal(f.saved[0].name, 'ST-Launcher-export-test.txt');
  assert.match(f.saved[0].data.toString('utf8'), /中文保存测试 OK/);

  // The exact failure reported in the screenshot: FileSaver creates an object
  // URL then revokes it immediately. fetch is deliberately disabled in this test.
  const data = JSON.stringify({ preset: '中文预设', text: '你好' });
  const blob = new Blob([data], { type: 'application/json' });
  const url = f.context.URL.createObjectURL(blob);
  f.context.URL.revokeObjectURL(url);
  new f.Anchor(url, 'preset.json').click();
  await sleep(60);
  assert.equal(f.saved.length, 2);
  assert.equal(f.saved[1].name, 'preset.json');
  assert.equal(f.saved[1].data.toString('utf8'), data);

  // Native DOM click (not .click()) on blob URL.
  const docUrl = f.context.URL.createObjectURL(new Blob(['角色卡'], { type: 'text/plain' }));
  let canceled = false;
  f.handlers.click({
    target: { closest: () => new f.Anchor(docUrl, 'character.txt') },
    preventDefault() { canceled = true; },
    stopImmediatePropagation() {}
  });
  await sleep(60);
  assert(canceled);
  assert.equal(f.saved[2].data.toString('utf8'), '角色卡');

  // data: URI export.
  new f.Anchor('data:text/plain;charset=utf-8,%E4%B8%AD%E6%96%87', 'data.txt').click();
  await sleep(60);
  assert.equal(f.saved[3].data.toString('utf8'), '中文');

  // Multi-chunk binary PNG/ZIP-like bytes with a Unicode filename.
  const bytes = Buffer.from(Array.from({length: 190123}, (_, index) => index % 256));
  const largeUrl = f.context.URL.createObjectURL(new Blob([bytes],
    { type: 'application/octet-stream' }));
  f.context.URL.revokeObjectURL(largeUrl);
  new f.Anchor(largeUrl, '人物卡_导出.bin').click();
  await sleep(200);
  assert.equal(f.saved.length, 5);
  assert.equal(f.saved[4].name, '人物卡_导出.bin');
  assert.deepEqual(f.saved[4].data, bytes);
  assert.deepEqual(f.alerts, []);
  console.log('Blob export smoke test: 5 cases PASS (TXT, revoked URL, DOM click, data URI, binary multi-chunk)');
})().catch(err => { console.error(err); process.exitCode = 1; });
