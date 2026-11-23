/**
 * Types for the page-side API of Techoship WebView Bridge.
 * Copy this file into your web project, or reference it with
 * /// <reference path="webview-bridge.d.ts" />
 *
 * Both globals exist only when the page runs inside an Android app that installed the
 * bridge for this origin, so always feature-detect: `if (window.TechoshipBridge) { … }`.
 */

export interface BridgeError extends Error {
  /** For example 'too_large', 'permission_required', 'user_canceled', 'billing_unavailable'. */
  code: string;
}

export interface SavedFile {
  name: string;
  /** content:// URI on the device. */
  uri: string;
  size: number;
}

export interface TechoshipBridge {
  readonly version: string;
  /** Enabled features: 'print', 'downloads', 'billing'. */
  readonly features: readonly string[];
  has(feature: 'print' | 'downloads' | 'billing' | string): boolean;
  /** Opens Android's print dialog for the current page. `window.print()` does the same. */
  print(): Promise<void>;
  /**
   * Saves a Blob or File, or the file at a blob:, data: or http(s) URL, to Downloads.
   * http(s) URLs are handed to the system download manager and resolve with { queued: true }.
   */
  download(source: Blob | string, fileName?: string): Promise<SavedFile | { queued: true }>;
  /** Low-level: call a native method registered by the app. */
  call<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T>;
  /** Subscribes to an event from the app. Returns an unsubscribe function. */
  on(event: string, handler: (data: unknown) => void): () => void;
}

export interface PricingPhase {
  formattedPrice: string;
  priceAmountMicros: number;
  priceCurrencyCode: string;
  /** ISO 8601 period, for example 'P1M'. */
  billingPeriod: string;
  billingCycleCount: number;
  recurrenceMode: number;
}

export interface Product {
  productId: string;
  type: 'inapp' | 'subs';
  title: string;
  name: string;
  description: string;
  oneTimeOffers?: Array<{
    offerId: string | null;
    offerToken: string;
    offerTags: string[];
    formattedPrice: string;
    priceAmountMicros: number;
    priceCurrencyCode: string;
  }>;
  subscriptionOffers?: Array<{
    basePlanId: string;
    offerId: string | null;
    offerToken: string;
    offerTags: string[];
    pricingPhases: PricingPhase[];
  }>;
}

export interface Purchase {
  products: string[];
  /** Send this to your server, verify it with the Google Play Developer API, then grant. */
  purchaseToken: string;
  orderId: string | null;
  purchaseState: 'purchased' | 'pending' | 'unspecified';
  purchaseTime: number;
  quantity: number;
  isAcknowledged: boolean;
  isAutoRenewing: boolean;
  packageName: string;
  obfuscatedAccountId: string | null;
  obfuscatedProfileId: string | null;
  originalJson: string;
  signature: string;
}

export interface AndroidBilling {
  readonly version: string;
  connect(): Promise<{ ready: true }>;
  getProducts(query: { inapp?: string[]; subs?: string[] }): Promise<{ products: Product[]; unfetched: string[] }>;
  purchase(
    productId: string,
    options?: {
      type?: 'inapp' | 'subs';
      /** Required to pick a specific subscription offer; defaults to the first one. */
      offerToken?: string;
      obfuscatedAccountId?: string;
      obfuscatedProfileId?: string;
    },
  ): Promise<{ status: 'purchased' | 'pending' | 'cancelled' | 'already_owned' | 'unspecified'; purchases: Purchase[] }>;
  getPurchases(type?: 'inapp' | 'subs'): Promise<{ purchases: Purchase[] }>;
  acknowledge(purchaseToken: string): Promise<void>;
  consume(purchaseToken: string): Promise<void>;
  onPurchasesUpdated(handler: (update: { responseCode: string; purchases: Purchase[] }) => void): () => void;
}

declare global {
  interface Window {
    TechoshipBridge?: TechoshipBridge;
    AndroidBilling?: AndroidBilling;
  }
}
