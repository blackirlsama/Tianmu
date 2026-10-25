const BUCKET_PATH = /^\/tianmu-upload\/([^/]+\.(?:png|jpe?g|gif|webp|avif))$/i;

export function mediaUrl(value) {
  if (typeof value !== "string" || !value.trim()) return "";
  const source = value.trim();
  try {
    const parsed = new URL(source, window.location.origin);
    const match = parsed.pathname.match(BUCKET_PATH);
    if (match) return `/api/media/${encodeURIComponent(decodeURIComponent(match[1]))}`;
  } catch (_) {
    return source;
  }
  return source;
}
