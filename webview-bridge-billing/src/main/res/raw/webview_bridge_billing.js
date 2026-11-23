/*
 * Techoship WebView Bridge — Google Play Billing, page side.
 * Injected after the core bridge script; defines window.AndroidBilling.
 */
(function (config) {
  'use strict';

  var bridge = window.TechoshipBridge;
  if (!bridge || window.AndroidBilling) return;
  bridge.features.push('billing');

  var list = function (value) {
    if (!value) return [];
    return Array.isArray(value) ? value : [value];
  };

  var billing = {
    version: config.version,

    /** Connects to Play. Optional: every other method connects on demand. */
    connect: function () {
      return bridge.call('billing.connect');
    },

    /** getProducts({ inapp: ['coins_100'], subs: ['pro'] }) → { products, unfetched } */
    getProducts: function (query) {
      query = query || {};
      return bridge.call('billing.getProducts', { inapp: list(query.inapp), subs: list(query.subs) });
    },

    /**
     * purchase('pro', { offerToken, obfuscatedAccountId }) → { status, purchases }.
     * status is 'purchased', 'pending', 'cancelled' or 'already_owned'.
     * Pass { type: 'inapp' | 'subs' } if you have not called getProducts for this product.
     */
    purchase: function (productId, options) {
      var params = { productId: productId };
      options = options || {};
      Object.keys(options).forEach(function (key) { params[key] = options[key]; });
      return bridge.call('billing.purchase', params);
    },

    /** getPurchases() or getPurchases('subs') → { purchases } the user owns now. */
    getPurchases: function (type) {
      return bridge.call('billing.getPurchases', { type: type || '' });
    },

    /** Acknowledges a non-consumable purchase or subscription after your server verified it. */
    acknowledge: function (purchaseToken) {
      return bridge.call('billing.acknowledge', { purchaseToken: purchaseToken });
    },

    /** Consumes a consumable purchase (coins, credits) after granting it, so it can be bought again. */
    consume: function (purchaseToken) {
      return bridge.call('billing.consume', { purchaseToken: purchaseToken });
    },

    /** Called with { responseCode, purchases } whenever Play reports a change, including pending → purchased. */
    onPurchasesUpdated: function (handler) {
      return bridge.on('billing.purchasesUpdated', handler);
    }
  };

  Object.defineProperty(window, 'AndroidBilling', { value: Object.freeze(billing), writable: false, configurable: false });
})
