import { describe, expect, it } from "vitest";
import { NativeGigagochiBridge, createUnavailableBridge } from "./bridge";
import {
  BRIDGE_PROTOCOL_VERSION,
  type BridgeEvent,
  type BridgeRequest,
  type BridgeResponse,
  type JsonValue,
  makeProductCommand,
} from "./contracts";
import { createMockSnapshot } from "./mockBridge";

class FakeNativePort {
  onmessage: ((event: { data: string }) => void) | null = null;
  posted: BridgeRequest[] = [];

  postMessage(message: string): void {
    this.posted.push(JSON.parse(message) as BridgeRequest);
  }

  respond(response: BridgeResponse | BridgeEvent): void {
    this.onmessage?.({ data: JSON.stringify(response) });
  }
}

describe("NativeGigagochiBridge", () => {
  it("rejects navigation readiness before bootstrap", async () => {
    const bridge = new NativeGigagochiBridge(new FakeNativePort());

    await expect(
      bridge.call("navigationReady", { canHandleBack: true, sequence: 1 }),
    ).rejects.toThrow("BRIDGE_NOT_READY");
  });

  it("fences bootstrap with document and bridge session identifiers", async () => {
    const port = new FakeNativePort();
    const bridge = new NativeGigagochiBridge(port);
    const pending = bridge.bootstrap();
    const request = port.posted[0];

    expect(request.kind).toBe("request");
    expect(request.method).toBe("bootstrap");
    expect(request.requestId).not.toBe(request.documentId);
    expect(request.bridgeSessionId).toBeUndefined();

    port.respond({
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: request.documentId,
      bridgeSessionId: crypto.randomUUID(),
      requestId: request.requestId,
      ok: true,
      result: createMockSnapshot("dashboard") as unknown as never,
      error: null,
    });

    await expect(pending).resolves.toMatchObject({ route: "dashboard" });
  });

  it("shares one native bootstrap when React starts it concurrently", async () => {
    const port = new FakeNativePort();
    const bridge = new NativeGigagochiBridge(port);
    const first = bridge.bootstrap();
    const second = bridge.bootstrap();

    expect(port.posted).toHaveLength(1);
    const request = port.posted[0];
    port.respond({
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: request.documentId,
      bridgeSessionId: crypto.randomUUID(),
      requestId: request.requestId,
      ok: true,
      result: createMockSnapshot("dashboard") as unknown as never,
      error: null,
    });

    await expect(Promise.all([first, second])).resolves.toHaveLength(2);
    expect(port.posted).toHaveLength(1);
  });

  it("delivers ordered native state events for the active document", async () => {
    const port = new FakeNativePort();
    const bridge = new NativeGigagochiBridge(port);
    const pending = bridge.bootstrap();
    const request = port.posted[0];
    const sessionId = crypto.randomUUID();
    port.respond({
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: request.documentId,
      bridgeSessionId: sessionId,
      requestId: request.requestId,
      ok: true,
      result: createMockSnapshot("dashboard") as unknown as never,
      error: null,
    });
    await pending;

    const delivered = new Promise<BridgeEvent>((resolve) => {
      window.addEventListener("gigagochi:native-event", (event) => {
        resolve((event as CustomEvent<BridgeEvent>).detail);
      }, { once: true });
    });
    const snapshot = createMockSnapshot("dashboard");
    port.respond({
      kind: "event",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: request.documentId,
      bridgeSessionId: sessionId,
      subscriptionId: "app-state",
      sequence: 1,
      type: "stateChanged",
      payload: snapshot as unknown as JsonValue,
    });

    await expect(delivered).resolves.toMatchObject({ sequence: 1, type: "stateChanged" });
  });

  it("requests a resync on an event sequence gap and accepts the next event", async () => {
    const port = new FakeNativePort();
    const bridge = new NativeGigagochiBridge(port);
    const pending = bridge.bootstrap();
    const request = port.posted[0];
    const sessionId = crypto.randomUUID();
    port.respond({
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: request.documentId,
      bridgeSessionId: sessionId,
      requestId: request.requestId,
      ok: true,
      result: createMockSnapshot("dashboard") as unknown as never,
      error: null,
    });
    await pending;

    let gaps = 0;
    let delivered = 0;
    window.addEventListener("gigagochi:native-event-gap", () => { gaps += 1; }, { once: true });
    window.addEventListener("gigagochi:native-event", () => { delivered += 1; }, { once: true });
    const event = (sequence: number): BridgeEvent => ({
      kind: "event",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: request.documentId,
      bridgeSessionId: sessionId,
      subscriptionId: "app-state",
      sequence,
      type: "stateChanged",
      payload: createMockSnapshot("dashboard") as unknown as JsonValue,
    });
    port.respond(event(2));
    port.respond(event(3));

    expect(gaps).toBe(1);
    expect(delivered).toBe(1);
  });

  it("preserves the native error code and retryability for callers", async () => {
    const port = new FakeNativePort();
    const bridge = new NativeGigagochiBridge(port);
    const snapshot = createMockSnapshot("dashboard");
    const bootstrap = bridge.bootstrap();
    const bootstrapRequest = port.posted[0];
    const sessionId = crypto.randomUUID();
    port.respond({
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: bootstrapRequest.documentId,
      bridgeSessionId: sessionId,
      requestId: bootstrapRequest.requestId,
      ok: true,
      result: snapshot as unknown as JsonValue,
      error: null,
    });
    await bootstrap;

    const pending = bridge.dispatch(makeProductCommand("PET_TAP", {}, snapshot.revision));
    const dispatchRequest = port.posted[1];
    port.respond({
      kind: "response",
      protocolVersion: BRIDGE_PROTOCOL_VERSION,
      documentId: dispatchRequest.documentId,
      bridgeSessionId: sessionId,
      requestId: dispatchRequest.requestId,
      ok: false,
      result: snapshot,
      error: { code: "STATE_CONFLICT", retryable: true },
    });

    await expect(pending).rejects.toMatchObject({
      name: "BridgeError",
      message: "STATE_CONFLICT",
      code: "STATE_CONFLICT",
      retryable: true,
      snapshot,
    });
  });

  it("does not silently install fixtures when the native port is absent", async () => {
    await expect(createUnavailableBridge().bootstrap()).rejects.toThrow(
      "NATIVE_BRIDGE_UNAVAILABLE",
    );
  });
});
