# FrameCut

FrameCut trims Google Drive videos entirely in the browser. Choose a Drive video, set the start and end, add metadata, then save the trimmed copy and an optional JSON sidecar back to Drive—or download it locally.

**Live app:** https://nipunbatra.github.io/framecut/

**Playback companion:** [FrameCue](https://nipunbatra.github.io/framecue/) adds subtitles, metadata, and a responsive timecode overlay for screen sharing.

## What it does

- browses folders and video files in My Drive and shared drives;
- downloads the selected source directly into the browser;
- supports fast keyframe-aligned stream copy or precise H.264/AAC re-encoding;
- uploads the result with resumable Drive transfers;
- can create destination folders, save a JSON metadata sidecar, and optionally enable link sharing;
- keeps the short-lived Google access token in memory and revokes it on sign-out.

FrameCut requests the full Google Drive scope because it must open existing videos and save results in arbitrary folders chosen by the user. It does not request Gmail access and has no application server, analytics, advertising, API key, or client secret.

## Development

```bash
npm install
npm run dev
```

Google authorization is origin-restricted. The shared Web OAuth client currently authorizes the hosted GitHub Pages origin and `http://localhost:5173` for local development.

## Verification

```bash
npm test        # unit and integration tests
npm run build   # strict TypeScript check + production build
npm run check   # both
npm audit       # dependency advisory scan
```

The test suite covers Drive browsing and pagination, query escaping, downloads and ranged fallback, resumable uploads, folder/share/sidecar operations, OAuth initialization and token lifecycle, timeline bounds and seeking, trim command construction, deployment contracts, and secret absence. GitHub Actions runs tests and the production build on every pull request and push to `main`.

## License

MIT © Nipun Batra
