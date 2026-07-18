/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.Window;
import net.wurstclient.WurstClient;
import net.wurstclient.util.SodiumSkeletonRenderState;
import net.wurstclient.util.SodiumSkeletonRenderState.Phase;

@Pseudo
@Mixin(targets = {
	"me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer"},
	remap = false)
public abstract class SodiumWorldRendererMixin
{
	/**
	 * Screen-space jitter patterns for each line width. macOS core-profile
	 * OpenGL clamps GL line width to 1px, so thicker skeleton lines are
	 * achieved by redrawing the line pass at small pixel offsets.
	 */
	private static final int[][][] WURST_LINE_OFFSETS =
		{{{0, 0}}, {{0, 0}, {1, 0}, {0, 1}},
			{{0, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}},
			{{0, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}}};

	@Shadow
	private RenderSectionManager renderSectionManager;

	@Inject(at = @At("HEAD"), method = "drawChunkLayer", cancellable = true,
		require = 0)
	private void onDrawChunkLayer(RenderLayer renderLayer,
		ChunkRenderMatrices matrices, double x, double y, double z,
		CallbackInfo ci)
	{
		SodiumSkeletonRenderState.markWorldRendererHookPresent();
		if(!WurstClient.INSTANCE.getHax().xRayHack.isSodiumSkeletonMode())
			return;

		if(renderLayer == RenderLayer.getSolid())
		{
			render(Phase.DEPTH, matrices, x, y, z);
			renderJitteredLines(matrices, x, y, z);
			ci.cancel();
			return;
		}

		if(renderLayer == RenderLayer.getTranslucent())
		{
			SodiumSkeletonRenderState.run(Phase.ORES,
				() -> renderSectionManager.renderLayer(matrices,
					DefaultTerrainRenderPasses.TRANSLUCENT, x, y, z));
			ci.cancel();
		}
	}

	private void render(Phase phase, ChunkRenderMatrices matrices, double x,
		double y, double z)
	{
		SodiumSkeletonRenderState.run(phase,
			() -> renderSectionManager.renderLayer(matrices,
				DefaultTerrainRenderPasses.CUTOUT, x, y, z));
	}

	/**
	 * Draws the skeleton line pass one or more times with sub-pixel projection
	 * offsets to simulate thicker lines on platforms that clamp GL line width.
	 */
	private void renderJitteredLines(ChunkRenderMatrices matrices, double x,
		double y, double z)
	{
		int lineWidth =
			WurstClient.INSTANCE.getHax().xRayHack.getSkeletonLineWidth();
		int index = Math.min(Math.max(lineWidth, 1), WURST_LINE_OFFSETS.length)
			- 1;
		int[][] offsets = WURST_LINE_OFFSETS[index];

		Window window = WurstClient.MC.getWindow();
		float pixelX = 2F / Math.max(1, window.getFramebufferWidth());
		float pixelY = 2F / Math.max(1, window.getFramebufferHeight());

		for(int[] offset : offsets)
		{
			ChunkRenderMatrices jittered = matrices;
			if(offset[0] != 0 || offset[1] != 0)
			{
				// Shift the projection's z-column so the offset is a uniform
				// screen-space jitter regardless of distance (clip.w == -z).
				Matrix4f projection = new Matrix4f(matrices.projection());
				projection.m20(projection.m20() - offset[0] * pixelX);
				projection.m21(projection.m21() - offset[1] * pixelY);
				jittered =
					new ChunkRenderMatrices(projection, matrices.modelView());
			}

			render(Phase.LINES, jittered, x, y, z);
		}
	}
}
