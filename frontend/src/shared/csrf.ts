export function readCsrfToken(): string | undefined {
  const prefix = `${encodeURIComponent('XSRF-TOKEN')}=`
  const cookie = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
  return cookie ? decodeURIComponent(cookie.substring(prefix.length)) : undefined
}
