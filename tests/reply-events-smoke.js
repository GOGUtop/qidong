'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/reply_events.js', 'utf8');

function run(sequence, expectedAlerts) {
  const handlers = new Map();
  const outgoing = [];
  const timeoutFns = [];
  const eventTypes = {
    GENERATION_STARTED: 'GENERATION_STARTED',
    GENERATION_ENDED: 'GENERATION_ENDED',
    GENERATION_STOPPED: 'GENERATION_STOPPED',
    MESSAGE_RECEIVED: 'MESSAGE_RECEIVED',
    CHARACTER_MESSAGE_RENDERED: 'CHARACTER_MESSAGE_RENDERED',
  };
  const eventSource = {on: (event, handler) => {
    handlers.set(event, [...(handlers.get(event) || []), handler]);
  }};
  const window = {
    SillyTavern: {getContext: () => ({eventSource, event_types: eventTypes})},
    STNativeEvents: {postMessage: raw => outgoing.push(JSON.parse(raw).type)}
  };
  vm.runInNewContext(source, {
    window, setTimeout: callback => {timeoutFns.push(callback); return 1;},
    setInterval: () => {throw new Error('unexpected polling');}, clearInterval: () => {},
  }, {filename:'reply_events.js'});
  for (const event of sequence) {
    for (const handler of handlers.get(event) || []) handler();
    // Timer callbacks normally occur after the current generation-end event loop.
    if (event === 'GENERATION_ENDED') timeoutFns.splice(0).forEach(fn => fn());
  }
  assert.equal(outgoing.filter(type => type === 'observer_ready').length, 1);
  assert.equal(outgoing.filter(type => type === 'reply_finished').length, expectedAlerts,
    `alerts for ${sequence.join(' -> ')}`);
}
run(['GENERATION_STARTED','MESSAGE_RECEIVED','GENERATION_ENDED'],1);
run(['GENERATION_STARTED','GENERATION_ENDED'],0);
run(['GENERATION_STARTED','MESSAGE_RECEIVED','GENERATION_STOPPED','GENERATION_ENDED'],0);
run(['GENERATION_STARTED','MESSAGE_RECEIVED','CHARACTER_MESSAGE_RENDERED','GENERATION_ENDED'],1);
run(['GENERATION_STARTED','MESSAGE_RECEIVED','GENERATION_ENDED','CHARACTER_MESSAGE_RENDERED'],1);
console.log('PASS: SillyTavern reply event simulation (5 scenarios)');
