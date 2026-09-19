/**
 * The fetch wrapper. Three things every call needs and nothing should have to remember:
 *
 * - `credentials: 'same-origin'`, so the session cookie travels. The app is served from the API's
 *   origin precisely so this is enough.
 * - The CSRF token. Spring writes it to the `XSRF-TOKEN` cookie (readable, not HttpOnly) and expects
 *   it back in the `X-XSRF-TOKEN` header on every mutating request.
 * - Errors as data. The API answers 422/409/404 with a list of `{field, message}`; this turns that
 *   into an `ApiError` the forms can attach to inputs, and anything else into a generic one.
 */
export type ApiFieldError = { field: string | null; message: string }

export class ApiError extends Error {
  readonly status: number
  readonly errors: ApiFieldError[]

  constructor(status: number, errors: ApiFieldError[]) {
    super(errors[0]?.message ?? `HTTP ${status}`)
    this.status = status
    this.errors = errors
  }

  /** The message for one field, or the first field-less message, or undefined. */
  for(field: string): string | undefined {
    return this.errors.find((e) => e.field === field)?.message ?? this.errors.find((e) => e.field === null)?.message
  }
}

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

async function request<T>(method: string, url: string, body?: unknown, form?: FormData): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    const token = csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }

  const response = await fetch(url, {
    method,
    headers,
    credentials: 'same-origin',
    body: form ?? (body !== undefined ? JSON.stringify(body) : undefined),
  })

  if (response.status === 204) return undefined as T

  const text = await response.text()
  const json = text ? safeJson(text) : null

  if (!response.ok) {
    const errors: ApiFieldError[] = Array.isArray(json)
      ? json
      : json && typeof json === 'object' && 'message' in json
        ? [{ field: null, message: String((json as { message: unknown }).message) }]
        : [{ field: null, message: defaultMessage(response.status) }]
    throw new ApiError(response.status, errors)
  }

  return json as T
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function defaultMessage(status: number): string {
  if (status === 401) return 'Su sesión expiró. Inicie sesión nuevamente.'
  // Also what a rejected CSRF token looks like; the page reloads its token on the next attempt.
  if (status === 403) return 'No tiene permiso para realizar esta acción. Intente nuevamente.'
  if (status === 404) return 'No encontrado.'
  return 'Ocurrió un error. Intente nuevamente.'
}

export const api = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body),
  postForm: <T>(url: string, form: FormData) => request<T>('POST', url, undefined, form),
}

/**
 * Refreshes the CSRF cookie before a login. Spring only sets it on a response, so a fresh browser
 * that goes straight to a POST would have nothing to echo back — and a browser that still holds a
 * token from a previous backend process has one that no longer matches. Both look the same from
 * here, so the cookie is always renewed rather than trusted when present. One cheap GET.
 */
export async function primeCsrf(): Promise<void> {
  try {
    await fetch('/api/auth/me', { credentials: 'same-origin' })
  } catch {
    // Offline or not started yet; the next call will surface a proper error.
  }
}
