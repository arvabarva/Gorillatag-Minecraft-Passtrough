package com.gorillapass.client;

import com.mojang.blaze3d.platform.Window;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Lets the gorilla's hand be Minecraft's mouse while a menu is open. Gorilla Tag shows the Minecraft window on a panel
 * in VR and sends where the right hand points at it; this feeds that to Minecraft's own mouse handler as if a real
 * mouse moved and clicked, so every menu (inventory, crafting, chests, furnaces, villagers) works the vanilla way:
 * click, right click, shift-click and drag-to-split included.
 */
final class MenuPointer {
	// Minecraft 26.3 reads the mouse through SDL3: 1 = left button, 3 = right button; modifier bits 1|2 = shift
	private static final int SDL_LEFT = 1;
	private static final int SDL_RIGHT = 3;
	private static final int SDL_SHIFT = 1;

	private record Event(float u, float v, int buttons, boolean shift) {
	}

	private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
	private int buttons;
	private double lastX = -1.0;
	private double lastY = -1.0;

	/** On the link's reader thread. */
	void queue(byte[] payload) {
		ByteBuffer in = ByteBuffer.wrap(payload);
		this.events.add(new Event(in.getFloat(), in.getFloat(), in.get(), in.get() != 0));
		while (this.events.size() > 64) {
			this.events.poll();
		}
	}

	/** On the render thread, once per frame. */
	void apply(Minecraft minecraft) {
		Window window = minecraft.getWindow();
		for (Event event; (event = this.events.poll()) != null; ) {
			double x = Math.clamp(event.u(), 0.0F, 1.0F) * window.getScreenWidth();
			double y = (1.0 - Math.clamp(event.v(), 0.0F, 1.0F)) * window.getScreenHeight();
			if (x != this.lastX || y != this.lastY) {
				double dx = this.lastX < 0.0 ? 0.0 : x - this.lastX;
				double dy = this.lastY < 0.0 ? 0.0 : y - this.lastY;
				minecraft.mouseHandler.onMove(window.handle(), x, y, dx, dy);
				this.lastX = x;
				this.lastY = y;
			}
			int modifiers = event.shift() ? SDL_SHIFT : 0;
			this.button(minecraft, window, event.buttons(), 1, SDL_LEFT, modifiers);
			this.button(minecraft, window, event.buttons(), 2, SDL_RIGHT, modifiers);
			this.buttons = event.buttons();
		}
	}

	private void button(Minecraft minecraft, Window window, int now, int bit, int sdlButton, int modifiers) {
		boolean was = (this.buttons & bit) != 0;
		boolean is = (now & bit) != 0;
		if (was != is) {
			minecraft.mouseHandler.onButton(window.handle(), new MouseButtonInfo(sdlButton, modifiers), is ? 1 : 0);
		}
	}

	/** Lets go of anything still held (a menu closed while a button was down). */
	void reset(Minecraft minecraft) {
		this.events.clear();
		if (this.buttons != 0) {
			Window window = minecraft.getWindow();
			this.button(minecraft, window, 0, 1, SDL_LEFT, 0);
			this.button(minecraft, window, 0, 2, SDL_RIGHT, 0);
			this.buttons = 0;
		}
	}
}
