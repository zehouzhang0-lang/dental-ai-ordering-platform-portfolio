export async function captureRefreshTokenForLogout(pendingRefresh, getLatestToken, timeoutMs) {
  if (pendingRefresh) {
    let timeoutId
    await Promise.race([
      Promise.resolve(pendingRefresh).catch(() => undefined),
      new Promise((resolve) => {
        timeoutId = setTimeout(resolve, Math.max(0, timeoutMs))
      })
    ]).finally(() => {
      if (timeoutId !== undefined) clearTimeout(timeoutId)
    })
  }
  return getLatestToken()
}
