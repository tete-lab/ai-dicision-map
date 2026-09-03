/** A short minimum display duration, not an extra delay on slow network responses. */
export async function withResponsePacing<T>(request: () => Promise<T>, minimumMs = 650): Promise<T> {
  const startedAt = Date.now();
  const response = await request();
  const remaining = minimumMs - (Date.now() - startedAt);
  if (remaining > 0) await new Promise<void>((resolve) => setTimeout(resolve, remaining));
  return response;
}
