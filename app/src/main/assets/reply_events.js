/* SillyTavern's documented extension events: generation started/received/ended.
   Injection is only installed on the bound site's main frame. */
(() => {
  if (window.__stLauncherReplyWatcher) return;
  window.__stLauncherReplyWatcher = true;
  let attempts = 0;
  let bound = false;
  const emit = type => {
    try { window.STNativeEvents?.postMessage(JSON.stringify({type})); }
    catch (_) {}
  };
  const connect = () => {
    if (bound) return true;
    let ctx;
    try { ctx = window.SillyTavern?.getContext?.(); } catch (_) { return false; }
    if (!ctx || !ctx.eventSource || !ctx.event_types) return false;
    const {eventSource, event_types: events} = ctx;
    if (!events.GENERATION_STARTED || !events.GENERATION_ENDED || !events.MESSAGE_RECEIVED)
      return false;
    bound = true;
    let active = false;
    let received = false;
    let ended = false;
    let notified = false;
    let stopped = false;
    const maybeDone = () => {
      if (active && received && ended && !stopped && !notified) {
        notified = true;
        emit('reply_finished');
      }
    };
    eventSource.on(events.GENERATION_STARTED, () => {
      active = true;
      received = ended = stopped = notified = false;
    });
    eventSource.on(events.MESSAGE_RECEIVED, () => {
      if (active) { received = true; maybeDone(); }
    });
    if (events.CHARACTER_MESSAGE_RENDERED) {
      eventSource.on(events.CHARACTER_MESSAGE_RENDERED, () => {
        if (active) { received = true; maybeDone(); }
      });
    }
    if (events.GENERATION_STOPPED) {
      eventSource.on(events.GENERATION_STOPPED, () => { stopped = true; active = false; });
    }
    eventSource.on(events.GENERATION_ENDED, () => {
      if (!active || stopped) return;
      ended = true;
      setTimeout(maybeDone, 400); // allow final streaming render to be emitted
      setTimeout(() => { active = false; }, 2500);
    });
    emit('observer_ready');
    return true;
  };
  if (connect()) return;
  const interval = setInterval(() => {
    attempts++;
    if (connect() || attempts >= 240) clearInterval(interval);
  }, 250);
})();
