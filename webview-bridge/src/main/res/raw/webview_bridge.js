/*
 * Techoship WebView Bridge — page side.
 *
 * Injected by the app at document start, only into frames whose origin the app allowed.
 * The app appends "(config);" to call this function with the bridge configuration.
 * Defines window.TechoshipBridge, replaces window.print(), and turns blob:/data: downloads
 * and <a download> links into files saved on the device.
 */
(function (config) {
  'use strict';

  if (window.TechoshipBridge) return;
  var port = window[config.objectName];
  if (!port) return;

  var pending = Object.create(null);
  var listeners = Object.create(null);
  var nextId = 1;

  port.onmessage = function (event) {
    var message;
    try {
      message = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    if (message.id !== undefined && message.id !== null) {
      var entry = pending[message.id];
      if (!entry) return;
      delete pending[message.id];
      if (message.ok) {
        entry.resolve(message.result);
      } else {
        var info = message.error || {};
        var error = new Error(info.message || 'WebView bridge call failed');
        error.code = info.code || 'error';
        entry.reject(error);
      }
      return;
    }
    if (message.event) {
      (listeners[message.event] || []).slice().forEach(function (fn) {
        try {
          fn(message.data);
        } catch (e) {
          setTimeout(function () { throw e; });
        }
      });
    }
  };

  /** Calls a native method; resolves with its result or rejects with an Error that has a .code. */
  function call(method, params) {
    return new Promise(function (resolve, reject) {
      var id = nextId++;
      pending[id] = { resolve: resolve, reject: reject };
      try {
        port.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  /** Subscribes to an event the app emits. Returns a function that unsubscribes. */
  function on(event, fn) {
    (listeners[event] = listeners[event] || []).push(fn);
    return function off() {
      var list = listeners[event] || [];
      var index = list.indexOf(fn);
      if (index >= 0) list.splice(index, 1);
    };
  }

  var features = config.features.slice();
  function has(feature) {
    return features.indexOf(feature) >= 0;
  }
  function unsupported(feature) {
    var error = new Error('The app has not enabled ' + feature);
    error.code = 'unsupported';
    return Promise.reject(error);
  }

  var bridge = {
    version: config.version,
    features: features,
    has: has,
    call: call,
    on: on,
    print: function () { return unsupported('print'); },
    download: function () { return unsupported('downloads'); }
  };

  // ---- Print -------------------------------------------------------------------------------

  if (has('print')) {
    bridge.print = function () {
      return call('print', { title: document.title || '' });
    };
    window.print = function () {
      bridge.print().catch(function (error) {
        console.warn('[TechoshipBridge] print failed:', error.message);
      });
    };
  }

  // ---- Downloads ---------------------------------------------------------------------------

  if (has('downloads')) {
    var CHUNK = config.chunkSize;

    var isBlobOrData = function (href) { return /^(blob|data):/i.test(href); };
    var isHttp = function (href) { return /^https?:/i.test(href); };

    var toBase64 = function (buffer) {
      var bytes = new Uint8Array(buffer);
      var text = '';
      for (var i = 0; i < bytes.length; i += 0x8000) {
        text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      }
      return btoa(text);
    };

    var sendChunk = function (id, buffer) {
      if (config.binary) {
        var frame = new Uint8Array(4 + buffer.byteLength);
        new DataView(frame.buffer).setUint32(0, id);
        frame.set(new Uint8Array(buffer), 4);
        port.postMessage(frame.buffer);
        return call('download.flush', { id: id });
      }
      return call('download.chunk', { id: id, data: toBase64(buffer) });
    };

    var saveBlob = function (blob, name) {
      var mime = blob.type || 'application/octet-stream';
      return call('download.begin', { name: name || '', mime: mime, size: blob.size }).then(function (started) {
        var id = started.id;
        var offset = 0;
        var next = function () {
          if (offset >= blob.size) return call('download.end', { id: id });
          var end = Math.min(offset + CHUNK, blob.size);
          var part = blob.slice(offset, end);
          offset = end;
          return part.arrayBuffer().then(function (buffer) { return sendChunk(id, buffer); }).then(next);
        };
        return next().catch(function (error) {
          call('download.abort', { id: id }).catch(function () {});
          throw error;
        });
      });
    };

    var saveUrl = function (href, name) {
      if (isBlobOrData(href)) {
        // fetch() resolves a blob: URL when it is called, so this still works when the page
        // revokes the URL straight after clicking the link.
        return fetch(href)
          .then(function (response) { return response.blob(); })
          .then(function (blob) { return saveBlob(blob, name); });
      }
      if (isHttp(href)) return call('download.url', { url: href, name: name || '' });
      var error = new Error('Cannot download ' + String(href).slice(0, 40));
      error.code = 'unsupported_url';
      return Promise.reject(error);
    };

    /**
     * Saves a Blob/File, or the file at a blob:, data: or http(s) URL, to the device's
     * Downloads folder. Resolves with { name, uri, size } for files saved directly, or
     * { queued: true } for http(s) URLs handed to the system download manager.
     */
    bridge.download = function (source, name) {
      var job;
      if (typeof Blob !== 'undefined' && source instanceof Blob) {
        job = saveBlob(source, name || (source.name || ''));
      } else {
        job = saveUrl(String(source), name);
      }
      return job.catch(function (error) {
        // Errors with a code came from the app, which has already reported them.
        if (!error || !error.code) {
          call('download.error', { name: name || '', message: String((error && error.message) || error) }).catch(function () {});
        }
        throw error;
      });
    };

    var shouldHandle = function (anchor) {
      var href = anchor.href;
      if (!href) return false;
      if (isBlobOrData(href)) return true;
      return anchor.hasAttribute('download') && isHttp(href);
    };

    var handleAnchor = function (anchor) {
      bridge.download(anchor.href, anchor.getAttribute('download') || '').catch(function (error) {
        console.warn('[TechoshipBridge] download failed:', error.message);
      });
    };

    // Clicks on links in the page. Bubble phase, so a page that cancels the click wins.
    window.addEventListener('click', function (event) {
      if (event.defaultPrevented) return;
      var target = event.target;
      var anchor = target && target.closest ? target.closest('a[href]') : null;
      if (!anchor || !shouldHandle(anchor)) return;
      event.preventDefault();
      handleAnchor(anchor);
    }, false);

    // The usual "create a link, click it, throw it away" pattern: the link is never attached,
    // so its click event never reaches the window listener above.
    var nativeClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (!this.isConnected && shouldHandle(this)) {
        handleAnchor(this);
        return;
      }
      return nativeClick.apply(this, arguments);
    };
  }

  Object.defineProperty(window, 'TechoshipBridge', { value: bridge, writable: false, configurable: false });

  // Lets the app send events to this document.
  try {
    port.postMessage(JSON.stringify({ method: 'bridge.hello' }));
  } catch (e) {}
})
