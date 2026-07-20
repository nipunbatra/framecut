# FrameCut

FrameCut trims Google Drive video entirely in your browser, then saves the result and optional metadata sidecar back to Drive—or downloads it locally.

**[Open FrameCut](https://nipunbatra.github.io/framecut/)** · Playback companion: **[FrameCue](https://nipunbatra.github.io/framecue/app.html)**

## Use it

1. **Continue with Google** and choose a Drive video.
2. Drag the timeline handles or press `I` / `O` to mark the section.
3. Add a file name, description, or custom metadata.
4. Save to a Drive folder, create a shareable link, or download locally.

Fast mode makes a lossless keyframe-aligned cut. **Precise cut** re-encodes to H.264/AAC when the exact frame matters.

## Privacy and permissions

FrameCut requests Google Drive access because it must open an existing video and save into a folder you choose. It does **not** request Gmail access and has no application server, analytics, advertising, API key, or client secret.

The short-lived access token stays in memory and is revoked on sign-out. Video bytes move directly between Drive and this browser tab.

## Reliability

| Area | Guardrails covered by tests |
|---|---|
| Google authorization | one-time initialization, concurrent requests, silent renewal, expiry, popup/script failure, revoke |
| Drive browsing | My Drive and shared drives, pagination, escaped search, folders, empty and failed states |
| Downloads | bearer authorization, progress, byte ranges, incomplete response rejection, safe stream fallback |
| Trimming | validated time bounds, seeking, fast and precise FFmpeg commands, cancellation |
| Uploads | resumable chunks, server offsets, interrupted/final-response recovery, folders, sharing, JSON sidecars |
| App contract | responsive UI, required controls, OAuth origin safety, secret absence, production build |

## Development

```bash
npm install
npm run dev
npm run check     # tests + strict TypeScript production build
npm audit
```

Google authorization is origin-restricted. The shared Web OAuth client authorizes the hosted GitHub Pages origin and `http://localhost:5173` for local development. A fork on a different origin needs its own authorized Web client configuration.

MIT © Nipun Batra
