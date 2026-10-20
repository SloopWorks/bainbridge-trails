export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: Record<string, unknown>,
    readonly headers?: Record<string, string>,
  ) {
    super(message);
  }
}

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...JSON_HEADERS, ...headers } });
}

export function errorResponse(e: ApiError): Response {
  const error: Record<string, unknown> = { code: e.code, message: e.message };
  if (e.details) Object.assign(error, e.details);
  return json({ error }, e.status, e.headers);
}

export const KB = 1024;

/** Reads a JSON body, refusing anything larger than maxBytes (checked on Content-Length and while streaming). */
export async function readJson(request: Request, maxBytes: number): Promise<unknown> {
  const declared = Number(request.headers.get("content-length") ?? "NaN");
  if (Number.isFinite(declared) && declared > maxBytes) throw tooLarge(maxBytes);
  if (!request.body) throw new ApiError(400, "invalid_json", "request body required");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > maxBytes) {
      await reader.cancel();
      throw tooLarge(maxBytes);
    }
    chunks.push(value);
  }
  const buf = new Uint8Array(size);
  let off = 0;
  for (const c of chunks) {
    buf.set(c, off);
    off += c.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder().decode(buf));
  } catch {
    throw new ApiError(400, "invalid_json", "body is not valid JSON");
  }
}

function tooLarge(maxBytes: number): ApiError {
  return new ApiError(413, "payload_too_large", `body exceeds ${maxBytes} bytes`);
}

export function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

export function bearer(request: Request): string | null {
  const h = request.headers.get("authorization");
  if (!h) return null;
  const m = /^Bearer\s+(\S+)$/i.exec(h);
  return m?.[1] ?? null;
}

export function badRequest(message: string, code = "bad_request"): ApiError {
  return new ApiError(400, code, message);
}
