package org.recompile.castle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * One multiplayer session: create or join a room, keep the WebSocket up, send our hero's state,
 * receive the friend's, and copy the host's save to the guest before the guest starts playing.
 */
final class MultiSession implements RoomClient.Listener
{
	enum Phase { IDLE, WORKING, IN_ROOM }

	private final Settings settings;
	private final File saveDir;                 // saves/<edition>/ (contains rms/)
	private final java.util.function.BooleanSupplier gameStarted;

	private volatile Phase phase = Phase.IDLE;
	private volatile String status = "Not connected";
	private volatile String error = "";
	private volatile RoomClient client;
	private volatile String role = "", code = "";
	private volatile String friendName = "";
	private volatile boolean friendOnline;
	private volatile boolean saveSynced;        // guest: got host's save
	private volatile String saveNote = "";

	private volatile Coop.State friend;
	private volatile long friendSeenAt;
	private final AtomicReference<String> outgoing = new AtomicReference<>();
	private Thread sender;

	private final ArrayDeque<String[]> toasts = new ArrayDeque<>(); // {text, expiresAtMillis}

	MultiSession(Settings settings, File saveDir, java.util.function.BooleanSupplier gameStarted)
	{
		this.settings = settings;
		this.saveDir = saveDir;
		this.gameStarted = gameStarted;
	}

	// ------------------------------------------------------------ status for the UI

	Phase phase() { return phase; }
	boolean inRoom() { return phase == Phase.IN_ROOM; }
	boolean isHost() { return "host".equals(role); }
	String role() { return role; }
	String roomCode() { return code; }
	String status() { return status; }
	String error() { return error; }
	String friendName() { return friendName; }
	boolean friendOnline() { return friendOnline && client != null && client.connected(); }
	boolean saveSynced() { return saveSynced; }
	boolean waitingForSave() { return inRoom() && !isHost() && saveRequested && !saveSynced; }
	String saveNote() { return saveNote; }

	/** The friend's latest state, or null if we have not heard from them for a few seconds. */
	Coop.State friend()
	{
		Coop.State f = friend;
		return f != null && System.currentTimeMillis() - friendSeenAt < 3000 ? f : null;
	}

	synchronized List<String> toasts()
	{
		long now = System.currentTimeMillis();
		while (!toasts.isEmpty() && Long.parseLong(toasts.peekFirst()[1]) < now) { toasts.pollFirst(); }
		List<String> out = new ArrayList<>();
		for (String[] t : toasts) { out.add(t[0]); }
		return out;
	}

	private synchronized void toast(String text)
	{
		toasts.addLast(new String[] { text, "" + (System.currentTimeMillis() + 5000) });
		while (toasts.size() > 3) { toasts.pollFirst(); }
	}

	// ------------------------------------------------------------ actions (called from the menu)

	/** Room keys ignore capital letters and spaces, so "Castle 99" and "castle99" are the same key. */
	static String normalizeKey(String k) { return k == null ? "" : k.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT); }

	static String normalizeCode(String c)
	{
		return c == null ? "" : c.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
	}

	String validate(boolean joining)
	{
		if (settings.playerName.trim().isEmpty()) { return "Type your name first"; }
		if (normalizeKey(settings.roomKey).length() < 4) { return "Room key must be at least 4 characters"; }
		if (joining && normalizeCode(settings.roomCode).length() != 6) { return "The room code has 6 characters. You typed \"" + settings.roomCode + "\""; }
		if (settings.serverKey.trim().length() < 16) { return "Paste the server key in Server settings first"; }
		return null;
	}

	void create() { start(false); }

	void join() { start(true); }

	private void start(boolean joining)
	{
		String problem = validate(joining);
		if (problem != null) { error = problem; return; }
		if (phase != Phase.IDLE) { return; }
		error = "";
		phase = Phase.WORKING;
		status = "Waking up the server (can take up to a minute)...";
		Thread t = new Thread(() -> {
			RoomClient c = new RoomClient(settings.serverUrl, settings.serverKey);
			try
			{
				if (!c.wakeUp()) { throw new IOException("Server not reachable. Check your internet and the server address."); }
				status = joining ? "Joining room..." : "Creating room...";
				String key = normalizeKey(settings.roomKey);
				String wanted = normalizeCode(settings.roomCode);
				RoomClient.Room r;
				try
				{
					r = joining ? c.join(wanted, settings.playerName.trim(), key) : c.create(settings.playerName.trim(), key);
				}
				catch (RoomClient.ApiException e)
				{
					if (joining && e.status == 403)
					{
						throw new RoomClient.ApiException(403, "No room " + wanted + " with key \"" + key + "\". Check both with your friend. If the host's game was closed or the server restarted, they must create a new room.");
					}
					throw e;
				}
				client = c;
				role = r.role;
				code = r.code;
				if (joining) { settings.roomCode = r.code; settings.save(); }
				saveSynced = false;
				saveNote = "";
				phase = Phase.IN_ROOM;
				status = "Connecting...";
				c.connect(this);
				startSender();
			}
			catch (RoomClient.ApiException e) { phase = Phase.IDLE; error = e.getMessage(); status = "Not connected"; }
			catch (Exception e) { phase = Phase.IDLE; error = e.getMessage() == null ? e.toString() : e.getMessage(); status = "Not connected"; }
		}, "RoomJoin");
		t.setDaemon(true);
		t.start();
	}

	void leave()
	{
		RoomClient c = client;
		client = null;
		phase = Phase.IDLE;
		status = "Not connected";
		friend = null;
		friendName = "";
		friendOnline = false;
		role = "";
		code = "";
		if (c != null) { new Thread(c::leave, "RoomLeave").start(); }
	}

	// ------------------------------------------------------------ game thread hooks

	/** Called every game frame with our hero's state. Never blocks. */
	void publish(Coop.State mine)
	{
		outgoing.set(mine.toJson(settings.playerName.trim()));
	}

	private void startSender()
	{
		if (sender != null && sender.isAlive()) { return; }
		sender = new Thread(() -> {
			String last = null;
			long lastSent = 0;
			while (true)
			{
				RoomClient c = client;
				if (c == null) { return; }
				String msg = outgoing.get();
				long now = System.currentTimeMillis();
				if (msg != null && c.connected() && (!msg.equals(last) || now - lastSent > 1000))
				{
					if (c.send(msg)) { last = msg; lastSent = now; }
				}
				try { Thread.sleep(60); } catch (InterruptedException e) { return; }
			}
		}, "RoomSender");
		sender.setDaemon(true);
		sender.start();
	}

	// ------------------------------------------------------------ messages

	@Override public void onStatus(String text, boolean connected)
	{
		status = connected ? (friendOnline ? "Playing with " + friendName : waitingText()) : text;
	}

	private String waitingText() { return isHost() ? "Room " + code + " is open. Waiting for your friend..." : "Waiting for the host..."; }

	@Override public void onRoomGone(String reason)
	{
		if (phase == Phase.IDLE) { return; }
		toast("Multiplayer ended: " + reason);
		error = reason;
		leave();
	}

	@Override public void onMessage(Map<String, Object> m)
	{
		String type = String.valueOf(Json.str(m, "type"));
		switch (type)
		{
			case "room.welcome":
			{
				Map<String, Object> peer = Json.sub(m, "peer");
				if (peer != null) { friendName = String.valueOf(Json.str(peer, "name")); friendOnline = Json.bool(peer, "online"); }
				status = friendOnline ? "Playing with " + friendName : waitingText();
				if (!isHost() && friendOnline) { requestSave(); }
				break;
			}
			case "room.peer-joined":
			{
				friendName = String.valueOf(Json.str(m, "name"));
				boolean online = Json.bool(m, "online");
				if (online && !friendOnline) { toast(friendName + " joined the game"); }
				friendOnline = online;
				status = online ? "Playing with " + friendName : waitingText();
				if (!isHost() && online) { requestSave(); }
				break;
			}
			case "room.peer-left":
				if (friendOnline) { toast(friendName + " left"); }
				friendOnline = false;
				friend = null;
				status = waitingText();
				break;
			case "room.closed":
				onRoomGone("Room closed");
				break;
			case "state":
			{
				Coop.State s = Coop.State.fromJson(m);
				Coop.State old = friend;
				friend = s;
				friendSeenAt = System.currentTimeMillis();
				String n = Json.str(m, "name");
				if (n != null) { friendName = n; }
				if (!friendOnline) { friendOnline = true; status = "Playing with " + friendName; }
				if (old != null && !old.place.equals(s.place) && !s.place.isEmpty() && !"In the menus".equals(s.place))
				{
					toast(friendName + " entered " + s.place + (s.mode.equals("level") ? ", go there to play together!" : ""));
				}
				break;
			}
			case "save-request":
				if (isHost()) { sendSave(); }
				break;
			case "save":
				if (!isHost()) { receiveSave(m); }
				break;
			default:
		}
	}

	// ------------------------------------------------------------ save copy (host -> guest)

	private volatile boolean saveRequested;

	private void requestSave()
	{
		if (saveSynced || saveRequested) { return; }
		if (gameStarted.getAsBoolean())
		{
			saveNote = "You started before joining, so you keep your own progress.";
			return;
		}
		saveRequested = true;
		saveNote = "Getting the host's progress...";
		RoomClient c = client;
		if (c != null) { c.send(Json.of("type", "save-request")); }
	}

	private void sendSave()
	{
		Path root = saveDir.toPath().resolve("rms");
		StringBuilder files = new StringBuilder("[");
		int count = 0;
		if (Files.isDirectory(root))
		{
			try (Stream<Path> walk = Files.walk(root))
			{
				for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator)
				{
					byte[] data = Files.readAllBytes(p);
					if (data.length > 200_000) { continue; }
					String rel = root.relativize(p).toString().replace('\\', '/');
					if (count++ > 0) { files.append(','); }
					files.append(Json.of("path", rel, "data", Base64.getEncoder().encodeToString(data)));
				}
			}
			catch (IOException e) { System.out.println("save read failed: " + e); }
		}
		files.append(']');
		RoomClient c = client;
		if (c != null)
		{
			// Json.of only writes flat values, so put the array in by hand
			c.send("{\"type\":\"save\",\"files\":" + files + "}");
			toast("Sent your progress to " + friendName);
		}
	}

	@SuppressWarnings("unchecked")
	private void receiveSave(Map<String, Object> m)
	{
		saveRequested = false;
		if (gameStarted.getAsBoolean()) { saveNote = "Already playing, kept your own progress."; return; }
		Object list = m.get("files");
		if (!(list instanceof List)) { saveNote = "Host sent no progress."; saveSynced = true; return; }
		Path root = saveDir.toPath().resolve("rms").toAbsolutePath().normalize();
		try
		{
			// keep the guest's own save, just in case
			if (Files.isDirectory(root))
			{
				Path backup = root.resolveSibling("rms-own-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
				Files.move(root, backup);
			}
			Files.createDirectories(root);
			int n = 0;
			for (Object o : (List<Object>) list)
			{
				if (!(o instanceof Map)) { continue; }
				Map<String, Object> f = (Map<String, Object>) o;
				String rel = Json.str(f, "path"), data = Json.str(f, "data");
				if (rel == null || data == null) { continue; }
				Path target = root.resolve(rel).normalize();
				if (!target.startsWith(root) || rel.contains("..")) { continue; } // never write outside the save folder
				Files.createDirectories(target.getParent());
				Files.write(target, Base64.getDecoder().decode(data));
				n++;
			}
			saveSynced = true;
			saveNote = n > 0 ? "Got " + friendName + "'s progress. You can play now." : "Host has no saved progress yet. You can play now.";
			toast(saveNote);
		}
		catch (Exception e) { saveNote = "Could not copy progress: " + e.getMessage(); }
	}
}
