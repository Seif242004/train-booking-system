// One JSON error shape for every failure the Gateway produces:
//   { "error": { "code": "<machine readable>", "message": "<human readable>" } }

export class HttpError extends Error {
  constructor(status, code, message, headers = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.headers = headers;
  }
}

export function sendError(res, status, code, message, headers = {}) {
  res.set(headers).status(status).json({ error: { code, message } });
}
