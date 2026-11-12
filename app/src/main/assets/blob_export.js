/* SillyTavern Android export adapter v0.3.
 * WebView's DownloadListener cannot read blob: URLs. Keep a reference to Blobs
 * created by the site's JS BEFORE FileSaver revokes their object URLs.
 * Android WebMessageListener is restricted to the bound origin and main frame.
 */
(() => {
  if (window.__stNativeBlobExportInstalled) return;
  if (!window.STNativeExport || typeof window.STNativeExport.postMessage !== 'function') return;
  window.__stNativeBlobExportInstalled = true;

  const native = window.STNativeExport;
  const callbacks = new Map();
  const blobUrls = new Map();
  const MAX_RETAINED = 24;
  const BLOB_TTL_MS = 2 * 60 * 1000;
  let transferInProgress = false;

  const oldListener = native.onmessage;
  native.onmessage = (event) => {
    try {
      const reply = JSON.parse(event.data);
      const key = `${reply.id}|${reply.kind}|${reply.seq}`;
      const pending = callbacks.get(key);
      if (pending) {
        callbacks.delete(key);
        clearTimeout(pending.timer);
        if (reply.ok) pending.resolve();
        else pending.reject(new Error(reply.error || '安卓文件写入失败'));
      }
    } catch (error) { console.warn('[ST Launcher] export acknowledgement', error); }
    if (typeof oldListener === 'function') oldListener.call(native, event);
  };

  function request(message) {
    return new Promise((resolve, reject) => {
      const key = `${message.id}|${message.kind}|${message.seq || 0}`;
      const timer = setTimeout(() => {
        callbacks.delete(key);
        reject(new Error('安卓写文件超过 60 秒无响应'));
      }, 60000);
      callbacks.set(key, { resolve, reject, timer });
      try { native.postMessage(JSON.stringify(message)); }
      catch (error) { clearTimeout(timer); callbacks.delete(key); reject(error); }
    });
  }

  // Capture the original Blob; the webpage may call revokeObjectURL immediately
  // after link.click(). A Blob reference remains readable after URL revocation.
  function rememberUrl(url, value) {
    if (typeof Blob === 'undefined' || !(value instanceof Blob)) return;
    blobUrls.set(url, value);
    if (blobUrls.size > MAX_RETAINED) blobUrls.delete(blobUrls.keys().next().value);
    setTimeout(() => blobUrls.delete(url), BLOB_TTL_MS);
  }
  try {
    const create = URL.createObjectURL;
    if (typeof create === 'function') {
      URL.createObjectURL = function (value) {
        const url = create.call(this, value);
        rememberUrl(url, value);
        return url;
      };
    }
  } catch (error) { console.warn('[ST Launcher] object URL registry unavailable', error); }

  const downloadUrl = (url) => typeof url === 'string' &&
    (/^blob:/i.test(url) || /^data:/i.test(url));

  async function readDataUrl(url) {
    const comma = url.indexOf(',');
    if (comma < 0) throw new Error('无效 data: 导出链接');
    const header = url.substring(5, comma);
    const data = url.substring(comma + 1);
    const mime = header.split(';')[0] || 'application/octet-stream';
    if (/;base64(?:;|$)/i.test(header)) {
      const binary = atob(data);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      return new Blob([bytes], { type: mime });
    }
    return new Blob([decodeURIComponent(data)], { type: mime });
  }

  async function resolveBlob(url, captured) {
    if (captured) return captured; // synchronous click-time reference survives revocation
    if (blobUrls.has(url)) return blobUrls.get(url);
    if (/^data:/i.test(url)) return readDataUrl(url);
    // Fallback for pre-existing URLs, when script was injected after their creation.
    try {
      const response = await fetch(url);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return await response.blob();
    } catch (error) {
      throw new Error('无法读取临时导出链接；请重新打开该导出页面后重试。原因：' +
        (error && error.message ? error.message : String(error)));
    }
  }

  async function transfer(url, preferredName, preCapturedBlob) {
    if (transferInProgress) {
      alert('上一份导出尚未完成，请完成后再导出。');
      return;
    }
    transferInProgress = true;
    const id = `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
    try {
      const blob = await resolveBlob(url, preCapturedBlob);
      if (!(blob instanceof Blob)) throw new Error('不是可导出的文件对象');
      if (blob.size > 1024 * 1024 * 1024) throw new Error('单文件不能超过 1 GiB');
      const name = preferredName || `SillyTavern-export-${Date.now()}.bin`;
      await request({ kind: 'start', id, seq: 0, name,
        mime: blob.type || 'application/octet-stream', size: blob.size });
      const chunkSize = 48 * 1024;
      let seq = 0;
      for (let offset = 0; offset < blob.size; offset += chunkSize) {
        const bytes = new Uint8Array(await blob.slice(offset, offset + chunkSize).arrayBuffer());
        let binary = '';
        for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        await request({ kind: 'chunk', id, seq, data: btoa(binary) });
        seq++;
      }
      await request({ kind: 'finish', id, seq: 0 });
    } catch (error) {
      console.error('[ST Launcher] native export:', error);
      try { native.postMessage(JSON.stringify({ kind: 'cancel', id, seq: 0 })); } catch (_) {}
      alert('保存文件失败：' + (error && error.message ? error.message : String(error)));
    } finally {
      transferInProgress = false;
    }
  }

  function intercept(url, name) {
    if (!downloadUrl(url)) return false;
    // Capture synchronously, before FileSaver runs revokeObjectURL().
    const blob = blobUrls.get(url);
    void transfer(url, name, blob);
    return true;
  }

  document.addEventListener('click', (event) => {
    const target = event.target;
    const anchor = target && target.closest ? target.closest('a[href]') : null;
    if (!anchor || !downloadUrl(anchor.href)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    intercept(anchor.href, anchor.getAttribute('download'));
  }, true);

  const anchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function (...args) {
    if (intercept(this.href, this.getAttribute('download'))) return;
    return anchorClick.apply(this, args);
  };

  // Settings self-test: proves JS -> Android bridge -> Downloads/SillyTavern.
  // Uses the same Blob path as real preset/character/chat exports.
  window.__stLauncherTestExport = function () {
    const content = 'SillyTavern Launcher v0.3 导出功能测试\n中文保存测试 OK\n';
    void transfer('', 'ST-Launcher-export-test.txt', new Blob([content],
      { type: 'text/plain;charset=utf-8' }));
  };
})();
