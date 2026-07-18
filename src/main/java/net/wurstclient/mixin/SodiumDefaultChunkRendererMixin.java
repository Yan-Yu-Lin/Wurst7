/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.wurstclient.util.SodiumSkeletonRenderState;

@Pseudo
@Mixin(targets = {
	"me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer"},
	remap = false)
public class SodiumDefaultChunkRendererMixin
{
	@Inject(at = @At("RETURN"), method = "<init>", require = 0)
	private void onConstructed(RenderDevice device, ChunkVertexType vertexType,
		CallbackInfo ci)
	{
		SodiumSkeletonRenderState.markChunkRendererHookPresent();
	}

	@Inject(at = @At("HEAD"), method = "render", require = 0)
	private void onRender(ChunkRenderMatrices matrices, CommandList commandList,
		ChunkRenderListIterable renderLists, TerrainRenderPass pass,
		CameraTransform camera, CallbackInfo ci)
	{
		SodiumSkeletonRenderState.markChunkRendererHookPresent();
	}

	@Inject(at = @At(value = "INVOKE",
		target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;begin(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
		shift = At.Shift.AFTER), method = "render", require = 0)
	private void afterBegin(ChunkRenderMatrices matrices,
		CommandList commandList, ChunkRenderListIterable renderLists,
		TerrainRenderPass pass, CameraTransform camera, CallbackInfo ci)
	{
		SodiumSkeletonRenderState.applyCurrentPhase();
	}

	@Inject(at = @At(value = "INVOKE",
		target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V"),
		method = "render", require = 0)
	private void beforeEnd(ChunkRenderMatrices matrices,
		CommandList commandList, ChunkRenderListIterable renderLists,
		TerrainRenderPass pass, CameraTransform camera, CallbackInfo ci)
	{
		if(SodiumSkeletonRenderState.isActive())
			SodiumSkeletonRenderState.restoreDefaults();
	}
}
