/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;

public enum SodiumSkeletonRenderState
{
	;

	private static final ThreadLocal<Phase> CURRENT =
		ThreadLocal.withInitial(() -> Phase.NONE);
	private static volatile boolean worldRendererHookPresent;
	private static volatile boolean chunkRendererHookPresent;

	public static void markWorldRendererHookPresent()
	{
		worldRendererHookPresent = true;
	}

	public static void markChunkRendererHookPresent()
	{
		chunkRendererHookPresent = true;
	}

	public static boolean areHooksReady()
	{
		return worldRendererHookPresent && chunkRendererHookPresent;
	}

	public static void run(Phase phase, Runnable action)
	{
		Phase previous = CURRENT.get();
		CURRENT.set(phase);
		try
		{
			action.run();

		}finally
		{
			restoreDefaults();
			CURRENT.set(previous);
		}
	}

	public static boolean isActive()
	{
		return CURRENT.get() != Phase.NONE;
	}

	public static void applyCurrentPhase()
	{
		switch(CURRENT.get())
		{
			case DEPTH:
				applyDepthPhase();
				break;

			case DEPTH_OFFSCREEN:
				applyOffscreenDepthPhase();
				break;

			case LINES:
				applyLinePhase();
				break;

			case ORES:
				applyOrePhase();
				break;

			case NONE:
				break;
		}
	}

	private static void applyDepthPhase()
	{
		RenderSystem.polygonMode(GlConst.GL_FRONT_AND_BACK, GlConst.GL_FILL);
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GlConst.GL_LEQUAL);
		RenderSystem.depthMask(true);
		RenderSystem.colorMask(false, false, false, false);
		RenderSystem.enablePolygonOffset();
		RenderSystem.polygonOffset(1, 1);
	}

	private static void applyOffscreenDepthPhase()
	{
		RenderSystem.polygonMode(GlConst.GL_FRONT_AND_BACK, GlConst.GL_FILL);
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GlConst.GL_LEQUAL);
		RenderSystem.depthMask(true);
		RenderSystem.colorMask(false, false, false, false);
		RenderSystem.disablePolygonOffset();
		RenderSystem.polygonOffset(0, 0);
	}

	private static void applyLinePhase()
	{
		RenderSystem.disablePolygonOffset();
		RenderSystem.polygonOffset(0, 0);
		RenderSystem.polygonMode(GlConst.GL_FRONT_AND_BACK, GlConst.GL_LINE);
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GlConst.GL_LEQUAL);
		RenderSystem.depthMask(false);
		RenderSystem.colorMask(true, true, true, true);
		RenderSystem.lineWidth(1);
	}

	private static void applyOrePhase()
	{
		// In Skeleton mode no terrain depth is written to the main
		// framebuffer, so ordinary depth testing still lets ores show
		// through walls while correctly occluding each other.
		RenderSystem.disablePolygonOffset();
		RenderSystem.polygonOffset(0, 0);
		RenderSystem.polygonMode(GlConst.GL_FRONT_AND_BACK, GlConst.GL_FILL);
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GlConst.GL_LEQUAL);
		RenderSystem.depthMask(true);
		RenderSystem.colorMask(true, true, true, true);
	}

	public static void restoreDefaults()
	{
		RenderSystem.polygonMode(GlConst.GL_FRONT_AND_BACK, GlConst.GL_FILL);
		RenderSystem.disablePolygonOffset();
		RenderSystem.polygonOffset(0, 0);
		RenderSystem.lineWidth(1);
		RenderSystem.colorMask(true, true, true, true);
		RenderSystem.depthMask(true);
		RenderSystem.depthFunc(GlConst.GL_LEQUAL);
	}

	public enum Phase
	{
		NONE,
		DEPTH,
		DEPTH_OFFSCREEN,
		LINES,
		ORES
	}
}
