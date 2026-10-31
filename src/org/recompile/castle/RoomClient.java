package org.recompile.castle;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Talks to the Castle of Magic room server (castle-api): create/join over HTTP, then a WebSocket relay. */
final class RoomClient
{
	interface Listener
	{
		/** Any JSON text message: server events (room.*) and messages from the friend. */
		void onMessage(Map<String, Object> msg);
		/** Connection state for the status line. */
		void onStatus(String text, boolean connected);
		/** The room is gone (closed by host, expired, server restarted). */
		void onRoomGone(String reason);
	}

	/** Result of create/join. */
	static final class Room
	{
		final String code, role, token;
		Room(String code, String role, String token) { this.code = code; this.role = role; this.token = token; }
	}

	static final class ApiException extends Exception
	{
		final int status;
		ApiException(int status, String message) { super(message); this.status = status; }
	}

	private final String baseUrl;
	private final String accessKey;
	private final HttpClient http;
	private volatile WebSocket socket;
	private volatile boolean closing;
	private volatile Room room;
	private Listener listener;

	RoomClient(String baseUrl, String accessKey)
	{
		String b = baseUrl.trim();
		while (b.endsWith("/")) { b = b.substring(0, b.length() - 1); }
		this.baseUrl = b;
		this.accessKey = accessKey.trim();
		this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
	}

	Room room() { return room; }

	// ------------------------------------------------------------ HTTP

	/** Free Render services sleep; the first call can take up to a minute. */
	boolean wakeUp()
	{
		try
		{
			HttpRequest r = HttpRequest.newBuilder(URI.create(baseUrl + "/health")).timeout(Duration.ofSeconds(90)).GET().build();
			return http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
		}
		catch (Exception e) { return false; }
	}

	Room create(String name, String roomKey) throws Exception
	{
		Map<String, Object> m = post("/v1/rooms", Json.of("name", name, "roomKey", roomKey));
		return room = new Room(Json.str(m, "roomCode"), Json.str(m, "role"), Json.str(m, "playerToken"));
	}

	Room join(String code, String name, String roomKey) throws Exception
	{
		String c = URLEncoder.encode(code.trim().toUpperCase(), StandardCharsets.UTF_8);
		Map<String, Object> m = post("/v1/rooms/" + c + "/join", Json.of("name", name, "roomKey", roomKey));
		return room = new Room(Json.str(m, "roomCode"), Json.str(m, "role"), Json.str(m, "playerToken"));
	}

	private Map<String, Object> post(String path, String body) throws Exception
	{
		HttpRequest r = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.timeout(Duration.ofSeconds(90))
			.header("Content-Type", "application/json")
			.header("X-Access-Key", accessKey)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
		Map<String, Object> m;
		try { m = Json.obj(res.body()); }
		catch (Exception e) { throw new ApiException(res.statusCode(), "Server answered " + res.statusCode()); }
		if (res.statusCode() / 100 != 2) { throw new ApiException(res.statusCode(), friendly(res.statusCode(), Json.str(m, "error"))); }
		return m;
	}

	private static String friendly(int status, String error)
	{
		switch (status)
		{
			case 401: return "Wrong server key";
			case 403: return "Wrong room code or room key";
			case 409: return "Room is full";
			case 429: return error != null ? error : "Too many tries, wait a minute";
			case 503: return "Server is full, try later";
			default: return error != null ? error : "Server error " + status;
		}
	}

	/** Leave politely: host closes the room, guest leaves it. */
	void leave()
	{
		closing = true;
		Room r = room;
		WebSocket ws = socket;
		if (ws != null) { try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) { } }
		if (r != null)
		{
			try
			{
				HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/rooms/" + r.code)).timeout(Duration.ofSeconds(10))
					.header("X-Access-Key", accessKey).header("Authorization", "Bearer " + r.token).DELETE().build();
				http.send(req, HttpResponse.BodyHandlers.discarding());
			}
			catch (Exception ignored) { }
		}
		room = null;
	}

	// ------------------------------------------------------------ WebSocket

	void connect(Listener l)
	{
		this.listener = l;
		closing = false;
		Thread t = new Thread(this::connectLoop, "RoomSocket");
		t.setDaemon(true);
		t.start();
	}

	private void connectLoop()
	{
		int attempt = 0;
		while (!closing && room != null)
		{
			if (socket != null) { sleep(1000); continue; } // connected; onClose clears it
			Room r = room;
			String ws = baseUrl.replaceFirst("^http", "ws") + "/v1/ws";
			try
			{
				listener.onStatus(attempt == 0 ? "Connecting..." : "Reconnecting...", false);
				socket = http.newWebSocketBuilder()
					.connectTimeout(Duration.ofSeconds(30))
					.header("X-Access-Key", accessKey)
					.header("Authorization", "Bearer " + r.token)
					.buildAsync(URI.create(ws), new Handler())
					.get(40, TimeUnit.SECONDS);
				attempt = 0;
			}
			catch (Exception e)
			{
				String msg = String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
				if (msg.contains("403"))
				{
					listener.onRoomGone("Room no longer exists");
					room = null;
					return;
				}
				attempt++;
				listener.onStatus("Connection problem, retrying (" + attempt + ")", false);
				sleep(Math.min(10_000, 1500L * attempt));
			}
		}
	}

	/** Sends a JSON text message to the friend. Drops it if not connected (state is resent constantly anyway). */
	boolean send(String json)
	{
		WebSocket ws = socket;
		if (ws == null) { return false; }
		synchronized (this)
		{
			try { ws.sendText(json, true).get(5, TimeUnit.SECONDS); return true; }
			catch (Exception e) { return false; }
		}
	}

	boolean connected() { return socket != null; }

	private final class Handler implements WebSocket.Listener
	{
		private final StringBuilder partial = new StringBuilder();

		@Override public void onOpen(WebSocket ws)
		{
			listener.onStatus("Connected", true);
			ws.request(1);
		}

		@Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last)
		{
			partial.append(data);
			if (last)
			{
				String text = partial.toString();
				partial.setLength(0);
				try { listener.onMessage(Json.obj(text)); }
				catch (Exception e) { System.out.println("bad message: " + e); }
			}
			ws.request(1);
			return null;
		}

		@Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason)
		{
			socket = null;
			if (code == 4002 || code == 4001) { room = null; listener.onRoomGone(code == 4002 ? "Room closed" : "Removed from room"); }
			else if (code == 4000) { room = null; listener.onRoomGone("Joined from another window"); }
			else if (!closing) { listener.onStatus("Disconnected, reconnecting...", false); }
			return null;
		}

		@Override public void onError(WebSocket ws, Throwable error)
		{
			socket = null;
			if (!closing) { listener.onStatus("Connection error, reconnecting...", false); }
		}
	}

	private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
