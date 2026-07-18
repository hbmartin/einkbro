/**
 * gm_shim.js contract tests: every native bridge call must present the per-document
 * capability token (SCRIPT_TOKEN) as its first argument — never the public script id —
 * so the Kotlin side (UserScriptBridge) can resolve the caller instead of trusting a
 * JS-supplied id.
 */
const { loadGmShim } = require('../helpers/loadScripts');

function makeBridgeStub() {
  const calls = [];
  const record = (name, returnValue) => (...args) => {
    calls.push({ name, args });
    return returnValue;
  };
  return {
    calls,
    callsTo(name) {
      return calls.filter((c) => c.name === name);
    },
    gmGetValue: record('gmGetValue', JSON.stringify('stored')),
    gmSetValue: record('gmSetValue'),
    gmDeleteValue: record('gmDeleteValue'),
    gmListValues: record('gmListValues', '["a","b"]'),
    gmXhr: record('gmXhr'),
    gmRegisterMenuCommand: record('gmRegisterMenuCommand'),
    gmUnregisterMenuCommand: record('gmUnregisterMenuCommand'),
    gmOpenInTab: record('gmOpenInTab'),
    gmSetClipboard: record('gmSetClipboard'),
    gmNotification: record('gmNotification'),
    gmLog: record('gmLog'),
  };
}

const SCRIPT_ID = 7;
const TOKEN = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

describe('gm_shim bridge calls carry the capability token', () => {
  let bridge;

  beforeEach(() => {
    // Fresh per-test page state: the hub and GM globals live on window.
    delete window.__einkbroGM;
    bridge = makeBridgeStub();
    window.einkbroGM = bridge;
    loadGmShim({ scriptId: SCRIPT_ID, token: TOKEN });
  });

  test('value store passes the token, not the script id', () => {
    expect(window.GM_getValue('k', 'fallback')).toBe('stored');
    window.GM_setValue('k', { v: 1 });
    window.GM_deleteValue('k');
    expect(window.GM_listValues()).toEqual(['a', 'b']);

    expect(bridge.callsTo('gmGetValue')[0].args).toEqual([TOKEN, 'k']);
    expect(bridge.callsTo('gmSetValue')[0].args).toEqual([TOKEN, 'k', JSON.stringify({ v: 1 })]);
    expect(bridge.callsTo('gmDeleteValue')[0].args).toEqual([TOKEN, 'k']);
    expect(bridge.callsTo('gmListValues')[0].args).toEqual([TOKEN]);
    bridge.calls.forEach((call) => {
      expect(call.args[0]).toBe(TOKEN);
      expect(call.args[0]).not.toBe(SCRIPT_ID);
    });
  });

  test('GM_getValue falls back to default when the bridge denies (returns null)', () => {
    bridge.gmGetValue = () => null;
    expect(window.GM_getValue('missing', 'fallback')).toBe('fallback');
  });

  test('GM_xmlhttpRequest passes the token while reqId still correlates by script id', () => {
    window.GM_xmlhttpRequest({ url: 'https://example.org', onload: () => {} });

    const call = bridge.callsTo('gmXhr')[0];
    expect(call.args[0]).toBe(TOKEN);
    const reqId = call.args[1];
    expect(reqId.startsWith(`${SCRIPT_ID}:`)).toBe(true);
    expect(JSON.parse(call.args[2]).url).toBe('https://example.org');
  });

  test('menu, tab, clipboard, notification and log calls all require the token first', () => {
    const fnId = window.GM_registerMenuCommand('caption', () => {});
    window.GM_unregisterMenuCommand(fnId);
    window.GM_openInTab('https://example.org', { active: true });
    window.GM_setClipboard('text');
    window.GM_notification('note');
    window.GM_log('a', 'b');

    expect(bridge.callsTo('gmRegisterMenuCommand')[0].args).toEqual([TOKEN, 'caption', fnId]);
    expect(bridge.callsTo('gmUnregisterMenuCommand')[0].args).toEqual([TOKEN, fnId]);
    expect(bridge.callsTo('gmOpenInTab')[0].args).toEqual([TOKEN, 'https://example.org', true]);
    expect(bridge.callsTo('gmSetClipboard')[0].args).toEqual([TOKEN, 'text']);
    expect(bridge.callsTo('gmNotification')[0].args).toEqual([TOKEN, 'note']);
    expect(bridge.callsTo('gmLog')[0].args).toEqual([TOKEN, 'a b']);
  });

  test('two shims share the hub but each passes its own token', () => {
    const OTHER_TOKEN = '11111111-2222-3333-4444-555555555555';
    loadGmShim({ scriptId: 8, token: OTHER_TOKEN });

    // Second load rebinds the GM_* globals to the second shim's closure.
    window.GM_setValue('k', 1);
    expect(bridge.callsTo('gmSetValue')[0].args[0]).toBe(OTHER_TOKEN);

    window.GM_xmlhttpRequest({ url: 'https://example.org', onload: () => {} });
    const xhr = bridge.callsTo('gmXhr')[0];
    expect(xhr.args[0]).toBe(OTHER_TOKEN);
    expect(xhr.args[1].startsWith('8:')).toBe(true);
    // The hub sequence is shared across shims, so reqIds never collide.
    expect(window.__einkbroGM.seq).toBeGreaterThan(0);
  });

  test('handleXhr still dispatches onload after native delivery', () => {
    jest.useFakeTimers();
    try {
      const onload = jest.fn();
      window.GM_xmlhttpRequest({ url: 'https://example.org', onload });
      const reqId = bridge.callsTo('gmXhr')[0].args[1];

      // Simulate UserScriptBridge.deliverXhr.
      window.__einkbroGM.handleXhr(reqId, 'load', JSON.stringify({ status: 200, responseText: 'ok' }));
      jest.runAllTimers();

      expect(onload).toHaveBeenCalledTimes(1);
      expect(onload.mock.calls[0][0].status).toBe(200);
    } finally {
      jest.useRealTimers();
    }
  });
});
