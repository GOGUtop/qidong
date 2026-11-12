/* Injected by the Android host on same-origin pages. No server modification needed. */
(() => {
  if (window.__stNativeBlobExportInstalled) return;
  if (!window.STNativeExport || typeof window.STNativeExport.postMessage !== 'function') return;
  window.__stNativeBlobExportInstalled = true;

  const callbacks = new Map();
  const native = window.STNativeExport;
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
        else pending.reject(new Error(reply.error || '导出失败'));
      }
    } catch (e) { console.warn('ST native export response', e); }
    if (typeof oldListener === 'function') oldListener.call(native, event);
  };

  function request(message) {
    return new Promise((resolve, reject) => {
      const key = `${message.id}|${message.kind}|${message.seq || 0}`;
      const timer = setTimeout(() => {
        callbacks.delete(key);
        reject(new Error('系统文件写入超时'));
      }, 30000);
      callbacks.set(key, { resolve, reject, timer });
      try { native.postMessage(JSON.stringify(message)); }
      catch (error) { clearTimeout(timer); callbacks.delete(key); reject(error); }
    });
  }

  const isDownloadable = (url) => typeof url === 'string' &&
    (url.startsWith('blob:') || url.startsWith('data:'));

  async function transfer(url, preferredName) {
    const id = `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
    try {
      const response = await fetch(url);
      if (!response.ok) throw new Error(`文件获取失败：HTTP ${response.status}`);
      const blob = await response.blob();
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
      console.error('SillyTavern native export error:', error);
      try { native.postMessage(JSON.stringify({ kind: 'cancel', id, seq: 0 })); } catch (_) {}
      alert(`保存文件失败：${error.message}`);
    }
  }

  // Capture user clicks on visible links before WebView handles blob: downloads.
  document.addEventListener('click', (event) => {
    const target = event.target;
    const anchor = target && target.closest ? target.closest('a[href]') : null;
    if (!anchor || !isDownloadable(anchor.href)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    transfer(anchor.href, anchor.getAttribute('download'));
  }, true);

  // FileSaver-style exports commonly create an *unattached* <a> and call .click().
  const nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function (...args) {
    if (isDownloadable(this.href)) {
      transfer(this.href, this.getAttribute('download'));
      return;
    }
    return nativeClick.apply(this, args);
  };
})();
