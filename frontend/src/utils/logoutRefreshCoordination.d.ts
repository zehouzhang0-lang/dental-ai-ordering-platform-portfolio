export function captureRefreshTokenForLogout(
  pendingRefresh: Promise<unknown> | null,
  getLatestToken: () => string,
  timeoutMs: number
): Promise<string>
