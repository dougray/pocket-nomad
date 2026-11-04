// store.js — Pocket Nomad web app, Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Saves messages, nodes and radio info (the "snapshot") and small settings
// (the "prefs": remembered radio, auto-reconnect, blocked radios) in this
// browser's IndexedDB. Ported from App/Sources/Mesh/MeshStore.swift, which
// writes JSON to Application Support. Nothing leaves the device.
//
// If IndexedDB can't be opened (private windows in some browsers, storage
// blocked), it falls back to memory: everything works until the page closes.

export const MAX_MESSAGES = 2000;

/** Keeps the newest `limit` messages. */
export function capped(messages, limit = MAX_MESSAGES) {
  return messages.length > limit ? messages.slice(messages.length - limit) : messages;
}

export const emptySnapshot = () => ({ messages: [], nodes: [], radio: {}, lastRead: {} });
export const emptyPrefs = () => ({ lastDeviceID: null, lastDeviceName: null, autoReconnect: false, blocked: [] });

const clone = (v) => (typeof structuredClone === "function" ? structuredClone(v) : JSON.parse(JSON.stringify(v)));

/** In-memory store: tests, the demo, and the fallback. */
export class MemoryStore {
  constructor() {
    this.kind = "memory";
    this.data = new Map();
  }
  async get(key) {
    return this.data.has(key) ? clone(this.data.get(key)) : null;
  }
  async set(key, value) {
    this.data.set(key, clone(value));
  }
  async clear() {
    this.data.clear();
  }
}

const DB_NAME = "pocket-nomad-mesh";
const STORE = "kv";

export class IndexedDBStore {
  constructor(db) {
    this.kind = "indexeddb";
    this.db = db;
  }

  static open(name = DB_NAME) {
    return new Promise((resolve, reject) => {
      let req;
      try {
        req = indexedDB.open(name, 1);
      } catch (e) {
        reject(e);
        return;
      }
      req.onupgradeneeded = () => {
        if (!req.result.objectStoreNames.contains(STORE)) req.result.createObjectStore(STORE);
      };
      req.onsuccess = () => resolve(new IndexedDBStore(req.result));
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error("IndexedDB blocked"));
    });
  }

  _tx(mode, fn) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction(STORE, mode);
      const req = fn(tx.objectStore(STORE));
      tx.oncomplete = () => resolve(req?.result ?? null);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  }

  get(key) {
    return this._tx("readonly", (s) => s.get(key)).then((v) => v ?? null);
  }
  set(key, value) {
    return this._tx("readwrite", (s) => s.put(value, key)).then(() => undefined);
  }
  clear() {
    return this._tx("readwrite", (s) => s.clear()).then(() => undefined);
  }
}

/** Opens IndexedDB, or returns a MemoryStore if that fails. */
export async function openStore() {
  if (typeof indexedDB === "undefined") return new MemoryStore();
  try {
    return await Promise.race([
      IndexedDBStore.open(),
      new Promise((_, reject) => setTimeout(() => reject(new Error("IndexedDB timed out")), 3000)),
    ]);
  } catch {
    return new MemoryStore();
  }
}

/** Snapshot + prefs on top of any key-value store. */
export class MeshStore {
  constructor(kv) {
    this.kv = kv;
  }
  get persistent() {
    return this.kv.kind !== "memory";
  }
  async load() {
    try {
      const snap = (await this.kv.get("snapshot")) ?? {};
      return { ...emptySnapshot(), ...snap };
    } catch {
      return emptySnapshot();
    }
  }
  async save(snapshot) {
    const trimmed = { ...snapshot, messages: capped(snapshot.messages) };
    await this.kv.set("snapshot", trimmed);
  }
  async loadPrefs() {
    try {
      return { ...emptyPrefs(), ...((await this.kv.get("prefs")) ?? {}) };
    } catch {
      return emptyPrefs();
    }
  }
  async savePrefs(prefs) {
    try {
      await this.kv.set("prefs", prefs);
    } catch {
      // Not fatal: the setting lasts until the page closes.
    }
  }
}
