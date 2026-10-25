const BUCKET_PATH = /^\/tianmu-upload\/([^/]+\.(?:png|jpe?g|gif|webp|avif))$/i;
const VIDEO_BUCKET_PATH = /^\/tianmu-upload\/([A-Za-z0-9_-]{8,128}\.(?:mp4|webm|mov|avi))$/i;
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

export function mediaVideoUrl(value) {
  if (typeof value !== "string" || !value.trim()) return "";
  const source = value.trim();
  try {
    const parsed = new URL(source, window.location.origin);
    const match = parsed.pathname.match(VIDEO_BUCKET_PATH);
    if (match) return `/api/media/videos/${encodeURIComponent(decodeURIComponent(match[1]))}`;
  } catch (_) {
    return source;
  }
  return source;
}
