// Only content-addressed API archives are cached. No source text or credentials.
const databaseName = "codeengine-api-artifacts-v1";
async function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1);
    request.onupgradeneeded = () => request.result.createObjectStore("jars");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("API cache unavailable"));
  });
}
export async function cachedJar(
  hash: string,
): Promise<ArrayBuffer | undefined> {
  try {
    const db = await open();
    try {
      return await new Promise((resolve, reject) => {
        const request = db.transaction("jars").objectStore("jars").get(hash);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
    } finally {
      db.close();
    }
  } catch {
    return undefined;
  }
}
export async function cacheJar(
  hash: string,
  bytes: ArrayBuffer,
): Promise<void> {
  try {
    const db = await open();
    try {
      await new Promise<void>((resolve, reject) => {
        const transaction = db.transaction("jars", "readwrite");
        transaction.objectStore("jars").put(bytes, hash);
        transaction.oncomplete = () => resolve();
        transaction.onerror = () => reject(transaction.error);
        transaction.onabort = () => reject(transaction.error);
      });
    } finally {
      db.close();
    }
  } catch {
    /* Storage denial/quota must not disable completions. */
  }
}
export async function pruneCache(hashes: string[]): Promise<void> {
  try {
    const db = await open();
    try {
      await new Promise<void>((resolve, reject) => {
        const transaction = db.transaction("jars", "readwrite");
        const store = transaction.objectStore("jars");
        const request = store.openKeyCursor();
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) return;
          if (!hashes.includes(String(cursor.key))) store.delete(cursor.key);
          cursor.continue();
        };
        transaction.oncomplete = () => resolve();
        transaction.onerror = () => reject(transaction.error);
      });
    } finally {
      db.close();
    }
  } catch {
    /* Cache is optional. */
  }
}
