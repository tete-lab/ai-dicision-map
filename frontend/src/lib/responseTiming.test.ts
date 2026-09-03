import { afterEach, describe, expect, it, vi } from "vitest";
import { withResponsePacing } from "./responseTiming";

afterEach(() => vi.useRealTimers());

describe("response pacing", () => {
  it("holds a fast response for only 650ms", async () => {
    vi.useFakeTimers();
    const shown = vi.fn();
    const request = vi.fn().mockResolvedValue("reply");
    const pending = withResponsePacing(request).then(shown);
    expect(request).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(649);
    expect(shown).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    await pending;
    expect(shown).toHaveBeenCalledWith("reply");
  });

  it("does not add waiting to a slow response", async () => {
    vi.useFakeTimers();
    const shown = vi.fn();
    const pending = withResponsePacing(() => new Promise<string>((resolve) => setTimeout(() => resolve("reply"), 1500))).then(shown);
    await vi.advanceTimersByTimeAsync(1500);
    await pending;
    expect(shown).toHaveBeenCalledWith("reply");
    expect(vi.getTimerCount()).toBe(0);
  });

  it("reports failures immediately", async () => {
    vi.useFakeTimers();
    await expect(withResponsePacing(() => Promise.reject(new Error("offline")))).rejects.toThrow("offline");
    expect(vi.getTimerCount()).toBe(0);
  });
});
