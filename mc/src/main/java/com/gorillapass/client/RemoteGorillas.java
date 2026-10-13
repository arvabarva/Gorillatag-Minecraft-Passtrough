package com.gorillapass.client;

import com.gorillapass.Gorilla;
import com.gorillapass.GorillaNet;
import com.gorillapass.client.WorldStreamer.Payload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Other players who play through Gorilla Tag are drawn by your Gorilla Tag as real Gorilla Tag gorillas, in exactly the
 * pose their Gorilla Tag has them in. Minecraft only carries the poses: yours from your Gorilla Tag to the server, and
 * theirs from the server to your Gorilla Tag, together with what they hold and wear. While a player is drawn that way,
 * Minecraft doesn't draw its own (blocky) model of them.
 */
final class RemoteGorillas {
	/** A pose older than this (their Gorilla Tag stopped, or they went out of sight) isn't used. */
	private static final long FRESH_MILLIS = 1500L;
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private record Pose(byte[] data, long time) {
	}

	/** Entity id -> latest pose, filled by the network thread. */
	private static final Map<Integer, Pose> POSES = new ConcurrentHashMap<>();
	private final Map<Integer, ItemStack[]> sentEquipment = new HashMap<>();
	/** Entity id -> the pose last passed on to our Gorilla Tag. */
	private final Map<Integer, byte[]> forwarded = new HashMap<>();
	private volatile byte @Nullable [] ownPose;
	private byte @Nullable [] sentOwnPose;

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(GorillaNet.Rig.TYPE,
			(rig, context) -> POSES.put(rig.entity(), new Pose(rig.data(), Util.getMillis())));
	}

	static void clear() {
		POSES.clear();
	}

	/** True if this player is being drawn by Gorilla Tag (so Minecraft shouldn't draw it). */
	static boolean drawnByGorillaTag(Entity entity) {
		Pose pose = POSES.get(entity.getId());
		return pose != null && Util.getMillis() - pose.time() < FRESH_MILLIS;
	}

	/** On the link's reader thread: our own gorilla's pose, from our Gorilla Tag. */
	void ownPose(byte[] data) {
		if (data.length <= GorillaNet.Rig.MAX_BYTES) {
			this.ownPose = data;
		}
	}

	void reset() {
		this.sentEquipment.clear();
		this.forwarded.clear();
		this.ownPose = null;
		this.sentOwnPose = null;
	}

	/** Every tick while linked: each new pose goes on once, as soon as it's here. */
	void tick(Minecraft minecraft, LocalPlayer player, ClientLevel level, HostLink link) {
		byte[] own = this.ownPose;
		if (own != null && own != this.sentOwnPose && ClientPlayNetworking.canSend(GorillaNet.Rig.TYPE)) {
			ClientPlayNetworking.send(new GorillaNet.Rig(player.getId(), own));
			this.sentOwnPose = own;
		}

		long now = Util.getMillis();
		Iterator<Map.Entry<Integer, Pose>> it = POSES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Pose> entry = it.next();
			int id = entry.getKey();
			Entity entity = level.getEntity(id);
			if (!(entity instanceof Player other) || now - entry.getValue().time() > FRESH_MILLIS) {
				it.remove();
				this.sentEquipment.remove(id);
				this.forwarded.remove(id);
				link.send(Protocol.S_REMOTE_GONE, new Payload().i(id).bytes);
				continue;
			}
			byte[] data = entry.getValue().data();
			if (this.forwarded.put(id, data) != data) {
				link.send(Protocol.S_REMOTE_RIG, new Payload().i(id).i(Gorilla.furColor(other)).raw(data).bytes);
			}
			this.sendEquipment(minecraft, player, other, link);
		}
	}

	/** What they hold and wear, whenever it changes. */
	private void sendEquipment(Minecraft minecraft, LocalPlayer player, Player other, HostLink link) {
		ItemStack[] now = {
			other.getMainHandItem(), other.getItemBySlot(EquipmentSlot.HEAD), other.getItemBySlot(EquipmentSlot.CHEST),
			other.getItemBySlot(EquipmentSlot.LEGS), other.getItemBySlot(EquipmentSlot.FEET)
		};
		ItemStack[] sent = this.sentEquipment.get(other.getId());
		boolean changed = sent == null;
		for (int i = 0; !changed && i < now.length; i++) {
			changed = !ItemStack.isSameItemSameComponents(now[i], sent[i]);
		}
		if (!changed) {
			return;
		}
		ItemStack[] copies = new ItemStack[now.length];
		Payload payload = new Payload().i(other.getId());
		for (int i = 0; i < now.length; i++) {
			copies[i] = now[i].copy();
			payload.raw((i == 0 ? HeldItemSender.encode(minecraft, player, now[i])
				: HeldItemSender.encodeArmor(minecraft, player, now[i], ARMOR_SLOTS[i - 1])).bytes.toByteArray());
		}
		this.sentEquipment.put(other.getId(), copies);
		link.send(Protocol.S_REMOTE_EQUIPMENT, payload.bytes);
	}
}
