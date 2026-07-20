/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.test;

import static net.wurstclient.test.WurstClientTestHelper.*;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wurstclient.WurstClient;

public enum AntiHungerHackTest
{
	;
	
	public static void testAntiHungerHack()
	{
		System.out.println("Testing AntiHunger hack");
		runChatCommand("gamemode survival");
		runChatCommand("gamerule naturalRegeneration false");
		runWurstCommand("t AutoMine off");
		runWurstCommand("t AutoSprint off");
		runWurstCommand("t NoFall off");
		runWurstCommand("t AntiHunger off");
		assertOnGround();
		healFully();
		
		assertAntiHungerReducesExhaustion();
		assertNoFallPreservesJetpackExhaustion();
		
		// AntiHunger alone should apply fall damage immediately.
		fallTenBlocks();
		float healthAfterLanding = getPlayerHealth();
		assertPlayerHealth(health -> Math.abs(health - 13) <= 1);
		takeScreenshot("antihunger_fall_damage", Duration.ZERO);
		
		// Breaking a block must not apply any delayed fall damage.
		breakTestBlock();
		waitForWorldTicks(5);
		assertPlayerHealth(
			health -> Math.abs(health - healthAfterLanding) < 0.01F);
		
		// AntiHunger followed by NoFall should leave both hacks enabled.
		healFully();
		runWurstCommand("t NoFall on");
		assertBothHacksEnabled();
		fallTenBlocks();
		assertPlayerHealth(health -> health == 20);
		breakTestBlock();
		waitForWorldTicks(5);
		assertPlayerHealth(health -> health == 20);
		
		// The reverse enable order must behave identically.
		runWurstCommand("t AntiHunger off");
		runWurstCommand("t NoFall off");
		healFully();
		runWurstCommand("t NoFall on");
		runWurstCommand("t AntiHunger on");
		assertBothHacksEnabled();
		fallTenBlocks();
		assertPlayerHealth(health -> health == 20);
		
		// Clean up
		runWurstCommand("t AutoMine off");
		runWurstCommand("t AutoSprint off");
		runWurstCommand("t AntiHunger off");
		runWurstCommand("t NoFall off");
		healFully();
		runChatCommand("gamerule naturalRegeneration true");
		runChatCommand("gamemode creative");
		runChatCommand("kill @e[type=item]");
	}
	
	private static void assertAntiHungerReducesExhaustion()
	{
		resetServerExhaustion();
		float normalBefore = getServerExhaustion();
		sprintForward(10);
		float normalExhaustion = getServerExhaustion() - normalBefore;
		
		resetServerExhaustion();
		runWurstCommand("t AntiHunger on");
		float antiHungerBefore = getServerExhaustion();
		sprintForward(10);
		float antiHungerExhaustion = getServerExhaustion() - antiHungerBefore;
		
		if(normalExhaustion < 0.05F
			|| antiHungerExhaustion >= normalExhaustion * 0.5F)
			throw new RuntimeException("AntiHunger exhaustion is wrong: normal="
				+ normalExhaustion + ", antiHunger=" + antiHungerExhaustion);
		
		System.out.println("AntiHunger exhaustion is correct: normal="
			+ normalExhaustion + ", antiHunger=" + antiHungerExhaustion);
	}
	
	private static void assertNoFallPreservesJetpackExhaustion()
	{
		float antiHungerOnly = measureJetpackExhaustion(false);
		float withNoFall = measureJetpackExhaustion(true);
		
		if(withNoFall > antiHungerOnly + 0.1F)
			throw new RuntimeException(
				"NoFall increased Jetpack exhaustion: " + "antiHungerOnly="
					+ antiHungerOnly + ", withNoFall=" + withNoFall);
		
		System.out
			.println("NoFall preserves Jetpack exhaustion: antiHungerOnly="
				+ antiHungerOnly + ", withNoFall=" + withNoFall);
	}
	
	private static float measureJetpackExhaustion(boolean noFallEnabled)
	{
		runWurstCommand("t NoFall " + (noFallEnabled ? "on" : "off"));
		resetServerExhaustion();
		runWurstCommand("t Jetpack on");
		submitAndWait(mc -> mc.options.jumpKey.setPressed(true));
		waitForWorldTicks(8);
		submitAndWait(mc -> mc.options.jumpKey.setPressed(false));
		runWurstCommand("t Jetpack off");
		float exhaustion = getServerExhaustion();
		
		// Enable NoFall for the descent, then restore the tested state.
		runWurstCommand("t NoFall on");
		waitUntil("player lands after Jetpack test",
			mc -> mc.player.isOnGround());
		waitForWorldTicks(2);
		runWurstCommand("t NoFall off");
		return exhaustion;
	}
	
	private static void sprintForward(int ticks)
	{
		runWurstCommand("t AutoSprint on");
		submitAndWait(mc -> mc.options.forwardKey.setPressed(true));
		waitForWorldTicks(ticks);
		submitAndWait(mc -> mc.options.forwardKey.setPressed(false));
		runWurstCommand("t AutoSprint off");
		waitForWorldTicks(2);
	}
	
	private static void fallTenBlocks()
	{
		assertOnGround();
		runChatCommand("tp ~ ~10 ~");
		waitForWorldTicks(5);
		waitUntil("player is on ground", mc -> mc.player.isOnGround());
		waitForWorldTicks(5);
	}
	
	private static void breakTestBlock()
	{
		runChatCommand("setblock ~ ~1 ~2 minecraft:oak_planks");
		waitForBlock(0, 1, 2, Blocks.OAK_PLANKS);
		runWurstCommand("t AutoMine on");
		waitForBlock(0, 1, 2, Blocks.AIR);
		runWurstCommand("t AutoMine off");
	}
	
	private static void healFully()
	{
		runChatCommand("effect give @s minecraft:instant_health 1 4 true");
		waitUntil("player is at full health",
			mc -> mc.player.getHealth() == 20);
	}
	
	private static void resetServerExhaustion()
	{
		MinecraftServer server = submitAndGet(mc -> mc.getServer());
		UUID playerId = submitAndGet(mc -> mc.player.getUuid());
		server.submit(() -> {
			ServerPlayerEntity player =
				server.getPlayerManager().getPlayer(playerId);
			float exhaustion = player.getHungerManager().getExhaustion();
			player.getHungerManager().addExhaustion(-exhaustion);
		}).join();
	}
	
	private static float getServerExhaustion()
	{
		MinecraftServer server = submitAndGet(mc -> mc.getServer());
		UUID playerId = submitAndGet(mc -> mc.player.getUuid());
		return server.submit(() -> server.getPlayerManager().getPlayer(playerId)
			.getHungerManager().getExhaustion()).join();
	}
	
	private static void assertBothHacksEnabled()
	{
		boolean enabled = submitAndGet(mc -> {
			WurstClient wurst = WurstClient.INSTANCE;
			return wurst.getHax().antiHungerHack.isEnabled()
				&& wurst.getHax().noFallHack.isEnabled();
		});
		
		if(!enabled)
			throw new RuntimeException(
				"AntiHunger and NoFall are not both enabled");
	}
	
	private static void assertOnGround()
	{
		if(!submitAndGet(mc -> mc.player.isOnGround()))
			throw new RuntimeException("Player is not on ground");
	}
	
	private static float getPlayerHealth()
	{
		return submitAndGet(mc -> mc.player.getHealth());
	}
	
	private static void assertPlayerHealth(Predicate<Float> healthCheck)
	{
		float health = getPlayerHealth();
		if(!healthCheck.test(health))
			throw new RuntimeException("Player's health is wrong: " + health);
		
		System.out.println("Player's health is correct: " + health);
	}
}
