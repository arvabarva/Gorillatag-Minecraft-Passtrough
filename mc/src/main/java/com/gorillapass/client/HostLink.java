package com.gorillapass.client;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens on 127.0.0.1 only for the Gorilla Tag plugin. One connection at a time; a new one replaces the old.
 * Poses and menu pointer moves are handed over on the reader thread straight away (they are used every frame, not
 * every tick); everything else is queued for the client thread.
 */
final class HostLink {
	private static final Logger LOGGER = LoggerFactory.getLogger("gorillapass");

	record Message(byte type, byte[] payload) {
	}

	private final ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
	private final BiPredicate<Byte, byte[]> immediate;
	private final Object writeLock = new Object();
	private volatile @Nullable Socket socket;
	private volatile @Nullable DataOutputStream out;
	private volatile boolean newConnection;

	HostLink(BiPredicate<Byte, byte[]> immediate) {
		this.immediate = immediate;
	}

	void start() {
		Thread acceptor = new Thread(this::acceptLoop, "Gorilla Passthrough link");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	boolean isConnected() {
		Socket current = this.socket;
		return current != null && !current.isClosed();
	}

	/** True once for every new Gorilla Tag connection. */
	boolean takeNewConnection() {
		boolean result = this.newConnection;
		this.newConnection = false;
		return result;
	}

	@Nullable Message poll() {
		return this.inbox.poll();
	}

	void send(byte type, @Nullable ByteArrayOutputStream payload) {
		DataOutputStream stream = this.out;
		if (stream == null) {
			return;
		}
		int length = 1 + (payload != null ? payload.size() : 0);
		try {
			synchronized (this.writeLock) {
				stream.writeInt(length);
				stream.writeByte(type);
				if (payload != null) {
					payload.writeTo(stream);
				}
				stream.flush();
			}
		} catch (IOException e) {
			LOGGER.info("Gorilla Passthrough: Gorilla Tag disconnected ({})", e.getMessage());
			this.close();
		}
	}

	void close() {
		Socket current = this.socket;
		this.socket = null;
		this.out = null;
		if (current != null) {
			try {
				current.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void acceptLoop() {
		try (ServerSocket server = new ServerSocket(Protocol.PORT, 1, InetAddress.getLoopbackAddress())) {
			LOGGER.info("Gorilla Passthrough: waiting for Gorilla Tag on 127.0.0.1:{}", Protocol.PORT);
			while (true) {
				Socket client = server.accept();
				client.setTcpNoDelay(true);
				this.close();
				this.inbox.clear();
				this.socket = client;
				this.out = new DataOutputStream(new BufferedOutputStream(client.getOutputStream(), 1 << 16));
				this.newConnection = true;
				LOGGER.info("Gorilla Passthrough: Gorilla Tag connected");
				Thread reader = new Thread(() -> this.readLoop(client), "Gorilla Passthrough reader");
				reader.setDaemon(true);
				reader.start();
			}
		} catch (IOException e) {
			LOGGER.error("Gorilla Passthrough: can't listen on port {} (is another Minecraft running the mod?)", Protocol.PORT, e);
		}
	}

	private void readLoop(Socket client) {
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(client.getInputStream(), 1 << 16))) {
			while (!client.isClosed()) {
				int length = in.readInt();
				if (length < 1 || length > (1 << 20)) {
					throw new IOException("bad frame length " + length);
				}
				byte type = in.readByte();
				byte[] payload = new byte[length - 1];
				in.readFully(payload);
				boolean handled;
				try {
					handled = this.immediate.test(type, payload);
				} catch (RuntimeException | LinkageError e) {
					// one message that can't be handled mustn't drop the link (every reconnect rebuilds the whole world)
					LOGGER.warn("Gorilla Passthrough: couldn't handle message {}", type, e);
					handled = true;
				}
				if (!handled) {
					this.inbox.add(new Message(type, payload));
				}
			}
		} catch (IOException e) {
			if (this.socket == client) {
				LOGGER.info("Gorilla Passthrough: Gorilla Tag disconnected ({})", e.getMessage());
				this.close();
			}
		}
	}
}
