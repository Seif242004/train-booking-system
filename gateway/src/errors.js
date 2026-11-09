// One JSON error shape for every failure the Gateway produces:
//   { "error": { "code": "<machine readable>", "message": "<human readable>" } }

/**
 * An error that carries the HTTP answer to send: status, a stable machine code, a message and optional headers.
 * Thrown by auth.js and caught by the error handler in app.js.
 */
export class HttpError extends Error {
  constructor(status, code, message, headers = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.headers = headers;
  }
}

/** Writes the shared JSON error body { error: { code, message } } with the given status (and headers, for example WWW-Authenticate). */
export function sendError(res, status, code, message, headers = {}) {
  res.set(headers).status(status).json({ error: { code, message } });
}
