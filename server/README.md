# Castle of Magic room server

Small Node.js service for two-player mode. The game creates or joins a room over HTTP, then both
players open a WebSocket and the server passes every message to the other player.
The server does not look inside game messages.

Two keys protect it:
- **Access key** (`ACCESS_KEY`): one secret for the whole server. Only your copies of the game know it.
  Every `/v1` request and the WebSocket must send it in the `X-Access-Key` header.
- **Room key**: a PIN chosen by the host when creating a room. The friend needs the room code and this key to join.
  5 wrong keys lock the room for 1 minute.

Rooms live in memory. They disappear after 6 hours, after 30 minutes with nobody connected,
or when the server restarts.

## Deploy on Render

1. Render dashboard → **New** → **Blueprint** → pick the `TrishaAdio/MyGame` repo.
   Choose the branch that has this code (`castle-of-magic-pc`, or `main` after merging the PR).
2. Render reads `render.yaml`, creates the `castle-of-magic-rooms` web service and generates `ACCESS_KEY`.
3. After the deploy, open `https://<your-service>.onrender.com/health`. It should show `{"ok":true,...}`.
4. Copy `ACCESS_KEY` from the service's **Environment** tab. The game will need it together with the URL.

Free plan note: the service sleeps after 15 minutes without traffic. The first request after that
takes up to about a minute while it wakes up, and any open rooms are gone.

## API

All `/v1` requests need `X-Access-Key: <ACCESS_KEY>`. Bodies are JSON. Errors look like `{"error": "..."}`.

### `GET /health`
No key needed. `200 {"ok": true, "service": "castle-of-magic-rooms"}`

### `POST /v1/rooms`: create a room (you become host)
```json
{ "name": "Anirban", "roomKey": "4321" }
```
`name` 1-20 characters, `roomKey` 4-64 characters.

`201`
```json
{
  "roomCode": "R9Z4FP",
  "createdAt": "2026-10-31T09:18:09.515Z",
  "expiresAt": "2026-10-31T15:18:09.515Z",
  "players": [{ "role": "host", "name": "Anirban", "online": false }],
  "role": "host",
  "playerToken": "<secret, keep it>"
}
```
Errors: `400` bad input, `429` too many requests, `503` server full.

### `POST /v1/rooms/{code}/join`: join as the second player
Same body as above, with the host's room key. The code is not case-sensitive.
`200` same shape as above with `"role": "guest"`.
Errors: `403` wrong code or key (same message for both), `409` room full, `429` locked after wrong keys.

### `GET /v1/rooms/{code}`: room status
Header `Authorization: Bearer <playerToken>`. `200` same shape, without `playerToken`.

### `DELETE /v1/rooms/{code}`: leave
Header `Authorization: Bearer <playerToken>`. The host closes the room for both players, a guest just leaves. `204`.

### WebSocket `GET /v1/ws`
Headers: `X-Access-Key` and `Authorization: Bearer <playerToken>`. Use `wss://` on Render.

Messages from the server (text JSON):
| type | when |
|------|------|
| `room.welcome` | right after connecting: `roomCode`, `role`, `name`, `peer` (or `null`) |
| `room.peer-joined` | the other player joined (`online: false`) or connected (`online: true`) |
| `room.peer-left` | the other player disconnected or left |
| `room.closed` | room closed (`host-closed`, `expired`, `idle`, `server-shutdown`) |
| `pong` | answer to `{"type":"ping","t":...}` |

Everything else a client sends, binary or text, goes unchanged to the other player.
Limits: 512 KB per message, 4 MB per second per player. If the other player can't keep up,
messages are dropped instead of queued (fine for game frames, so send state, not deltas that must all arrive).

Reconnecting with the same `playerToken` replaces the old connection. If the guest stays away
longer than 60 seconds, someone else with the room key can take the guest slot.

Close codes: `4000` connected from elsewhere, `4001` removed from room, `4002` room closed, `1008` sending too fast.

## Settings (environment variables)
| name | default | meaning |
|------|---------|---------|
| `ACCESS_KEY` | (required, 16+ chars) | server-wide secret |
| `PORT` | 3000 | set by Render |
| `TRUST_PROXY` | off | `1` on Render, so rate limits use the real client IP |
| `MAX_ROOMS` | 50 | rooms at once |
| `ROOM_TTL_MINUTES` | 360 | room lifetime |
| `ROOM_IDLE_MINUTES` | 30 | close a room after this long with nobody connected |
| `RECONNECT_GRACE_SECONDS` | 60 | how long the guest slot is kept for a disconnected guest |
| `REQUESTS_PER_MINUTE` | 30 | create/join requests per IP |

## Run and test locally
```sh
cd server
npm ci
npm test
ACCESS_KEY=some-long-local-secret-123 npm start
```
