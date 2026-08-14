const AUTH_COOKIE_PATHS = ['/', '/api', '/api/v1', '/api/v1/auth', '/api/v1/auth/']
const KNOWN_COOKIE_NAMES = ['refresh_token', 'refresh_csrf_token']

export function clearVisibleTestCookies() {
  const names = new Set(KNOWN_COOKIE_NAMES)
  for (const cookie of document.cookie.split(';')) {
    const name = cookie.split('=')[0]?.trim()
    if (name) names.add(name)
  }
  for (const name of names) {
    for (const path of AUTH_COOKIE_PATHS) {
      document.cookie = `${name}=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=${path}`
    }
  }
}
