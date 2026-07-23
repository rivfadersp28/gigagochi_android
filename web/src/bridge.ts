import {
  BRIDGE_PROTOCOL_VERSION,
  BRIDGE_SCHEMA_HASH,
  WEB_BUNDLE_VERSION,
  type AppSnapshot,
  type BridgeEvent,
  type BridgeErrorCode,
  type BridgeMethod,
  type BridgeRequest,
  type BridgeResponse,
  type JsonValue,
  type ProductCommand,
  isAppSnapshot,
  isBridgeEvent,
  isBridgeResponse,
} from "./contracts";
import { uuidV4 } from "./uuid";

interface NativeMessageEvent {
  data: string;
}

interface NativeMessagePort {
  postMessage(message: string): void;
  onmessage: ((event: NativeMessageEvent) => void) | null;
}

declare global {
  interface Window {
    gigagochiNative?: NativeMessagePort;
  }
}

type PendingCall = {
  resolve: (response: BridgeResponse) => void;
  reject: (error: Error) => void;
  timeout: number;
};

export interface GigagochiBridge {
  bootstrap(): Promise<AppSnapshot>;
  dispatch(command: ProductCommand): Promise<AppSnapshot>;
  call(method: BridgeMethod, payload?: JsonValue): Promise<JsonValue>;
  dispose?(): void;
}

const REQUEST_TIMEOUT_MS = 10_000;

export class BridgeError extends Error {
  constructor(
    public readonly code: BridgeErrorCode | string,
    public readonly retryable: boolean,
    public readonly snapshot?: AppSnapshot,
  ) {
    super(code);
    this.name = "BridgeError";
  }
}

export class NativeGigagochiBridge implements GigagochiBridge {
  private readonly pending = new Map<string, PendingCall>();
  private readonly documentId = uuidV4();
  private bridgeSessionId: string | null = null;
  private lastEventSequence = 0;
  private bootstrapPromise: Promise<AppSnapshot> | null = null;

  constructor(private readonly nativePort: NativeMessagePort) {
    nativePort.onmessage = (event) => this.receive(event.data);
  }

  bootstrap(): Promise<AppSnapshot> {
    if (this.bootstrapPromise) return this.bootstrapPromise;
    const request = this.performBootstrap();
    this.bootstrapPromise = request;
    void request.finally(() => {
      if (this.bootstrapPromise === request) this.bootstrapPromise = null;
    }).catch(() => undefined);
    return request;
  }

  private async performBootstrap(): Promise<AppSnapshot> {
    const result = await this.call("bootstrap", {
      supportedProtocolVersions: [BRIDGE_PROTOCOL_VERSION],
      webBundleVersion: WEB_BUNDLE_VERSION,
      schemaHash: BRIDGE_SCHEMA_HASH,
    });
    if (!isAppSnapshot(result)) throw new Error("INVALID_BOOTSTRAP");
    return result;
  }

  async dispatch(command: ProductCommand): Promise<AppSnapshot> {
    const result = await this.call("dispatch", command as unknown as JsonValue);
    if (!isAppSnapshot(result)) throw new Error("INVALID_SNAPSHOT");
    return result;
  }

  call(method: BridgeMethod, payload: JsonValue = {}): Promise<JsonValue> {
    if (method !== "bootstrap" && !this.bridgeSessionId) {
      return Promise.reject(new Error("BRIDGE_NOT_READY"));
    }
    const requestId = uuidV4();
    const request: BridgeRequest = {
      kind: "request",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: this.documentId,
      bridgeSessionId: this.bridgeSessionId ?? undefined,
      requestId,
      method,
      payload,
    };

    return new Promise((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        this.pending.delete(requestId);
        reject(new BridgeError("BRIDGE_TIMEOUT", true));
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(requestId, {
        timeout,
        reject,
        resolve: (response) => {
          if (response.ok) {
            this.bridgeSessionId = response.bridgeSessionId;
            resolve(response.result);
          } else {
            reject(
              new BridgeError(
                response.error.code,
                response.error.retryable,
                response.result ?? undefined,
              ),
            );
          }
        },
      });
      try {
        this.nativePort.postMessage(JSON.stringify(request));
      } catch (error) {
        window.clearTimeout(timeout);
        this.pending.delete(requestId);
        reject(error instanceof Error ? error : new Error("BRIDGE_POST_FAILED"));
      }
    });
  }

  dispose(): void {
    this.nativePort.onmessage = null;
    for (const pending of this.pending.values()) {
      window.clearTimeout(pending.timeout);
      pending.reject(new Error("BRIDGE_DISPOSED"));
    }
    this.pending.clear();
    this.bridgeSessionId = null;
    this.bootstrapPromise = null;
  }

  private receive(raw: string): void {
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return;
    }
    if (isBridgeEvent(parsed)) {
      this.receiveEvent(parsed);
      return;
    }
    if (!isBridgeResponse(parsed) || parsed.documentId !== this.documentId) return;
    if (this.bridgeSessionId && parsed.bridgeSessionId !== this.bridgeSessionId) return;
    const pending = this.pending.get(parsed.requestId);
    if (!pending) return;
    window.clearTimeout(pending.timeout);
    this.pending.delete(parsed.requestId);
    pending.resolve(parsed);
  }

  private receiveEvent(event: BridgeEvent): void {
    if (event.documentId !== this.documentId || event.bridgeSessionId !== this.bridgeSessionId) {
      return;
    }
    if (event.sequence <= this.lastEventSequence) return;
    if (event.sequence !== this.lastEventSequence + 1) {
      this.lastEventSequence = event.sequence;
      window.dispatchEvent(new CustomEvent("gigagochi:native-event-gap"));
      return;
    }
    this.lastEventSequence = event.sequence;
    window.dispatchEvent(new CustomEvent("gigagochi:native-event", { detail: event }));
  }
}

class UnavailableGigagochiBridge implements GigagochiBridge {
  bootstrap(): Promise<AppSnapshot> {
    return Promise.reject(new BridgeError("NATIVE_BRIDGE_UNAVAILABLE", false));
  }

  dispatch(): Promise<AppSnapshot> {
    return Promise.reject(new BridgeError("NATIVE_BRIDGE_UNAVAILABLE", false));
  }

  call(): Promise<JsonValue> {
    return Promise.reject(new BridgeError("NATIVE_BRIDGE_UNAVAILABLE", false));
  }
}

export function createNativeBridge(): NativeGigagochiBridge | null {
  return window.gigagochiNative
    ? new NativeGigagochiBridge(window.gigagochiNative)
    : null;
}

export function createUnavailableBridge(): GigagochiBridge {
  return new UnavailableGigagochiBridge();
}
