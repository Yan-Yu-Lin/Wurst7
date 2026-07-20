/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PostMotionListener;
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.PacketUtils;

@DontSaveState
@SearchTags({"anti hunger"})
public final class AntiHungerHack extends Hack
	implements ConnectionPacketOutputListener, PacketOutputListener,
	PostMotionListener, PreMotionListener
{
	private boolean trackingInitialized;
	private boolean wasOnGround;
	private double lastY;
	private int lastPlayerAge;
	private boolean groundSyncRequired;
	private boolean sendingMovementPackets;
	private PlayerMoveC2SPacket pendingGroundSyncPacket;
	
	public AntiHungerHack()
	{
		super("AntiHunger");
		setCategory(Category.MOVEMENT);
	}
	
	@Override
	protected void onEnable()
	{
		resetTracking();
		EVENTS.add(ConnectionPacketOutputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(PostMotionListener.class, this);
		EVENTS.add(PreMotionListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(ConnectionPacketOutputListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(PostMotionListener.class, this);
		EVENTS.remove(PreMotionListener.class, this);
		resetTracking();
	}
	
	@Override
	public void onPreMotion()
	{
		sendingMovementPackets = true;
		
		ClientPlayerEntity player = MC.player;
		if(player == null)
		{
			resetTracking();
			return;
		}
		
		boolean onGround = player.isOnGround();
		double y = player.getY();
		if(!trackingInitialized || player.age < lastPlayerAge)
		{
			trackingInitialized = true;
			wasOnGround = onGround;
			lastY = y;
			lastPlayerAge = player.age;
			groundSyncRequired = onGround;
			return;
		}
		
		if(onGround)
			groundSyncRequired |= !wasOnGround || y < lastY - 1.0E-6;
		else
			groundSyncRequired = false;
		
		wasOnGround = onGround;
		lastY = y;
		lastPlayerAge = player.age;
	}
	
	@Override
	public void onPostMotion()
	{
		sendingMovementPackets = false;
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!sendingMovementPackets
			|| !(event.getPacket() instanceof PlayerMoveC2SPacket packet))
			return;
		
		ClientPlayerEntity player = MC.player;
		if(player == null || !player.isOnGround())
			return;
		
		if(groundSyncRequired)
		{
			PlayerMoveC2SPacket syncPacket =
				PacketUtils.modifyOnGround(packet, true);
			pendingGroundSyncPacket = syncPacket;
			event.setPacket(syncPacket);
			return;
		}
		
		if(MC.interactionManager.isBreakingBlock())
		{
			event.setPacket(PacketUtils.modifyOnGround(packet, true));
			return;
		}
		
		event.setPacket(PacketUtils.modifyOnGround(packet, false));
	}
	
	@Override
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event)
	{
		if(event.getPacket() != pendingGroundSyncPacket)
			return;
		
		groundSyncRequired = false;
		pendingGroundSyncPacket = null;
	}
	
	private void resetTracking()
	{
		trackingInitialized = false;
		wasOnGround = false;
		lastY = 0;
		lastPlayerAge = 0;
		groundSyncRequired = false;
		sendingMovementPackets = false;
		pendingGroundSyncPacket = null;
	}
}
