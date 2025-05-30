package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkIndexType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkVertexInputRate;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import imgui.ImDrawData;
import imgui.ImFontAtlas;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.type.ImInt;

import java.nio.ByteBuffer;
import java.util.List;

public class ImGUIRenderer implements VulkanResource{
	
	private static final class PushConstant{
		private static final int SIZE = (2 + 2)*Float.BYTES;
		public static float[] make(float offX, float offY, float sizeX, float sizeY){
			return new float[]{offX, offY, sizeX, sizeY};
		}
	}
	
	public static class RenderResource implements VulkanResource{
		
		private BackedVkBuffer vtxBuf;
		private BackedVkBuffer idxBuf;
		
		@Override
		public void destroy() throws VulkanCodeException{
			if(vtxBuf == null) return;
			vtxBuf.destroy();
			idxBuf.destroy();
		}
		private void ensureBuffers(VulkanCore core, int vtxSize, int idxSize) throws VulkanCodeException{
			
			if(vtxBuf == null || vtxBuf.size()<vtxSize){
				if(vtxBuf != null){
					core.device.waitIdle();
					vtxBuf.destroy();
				}
				vtxBuf = core.allocateHostBuffer(vtxSize, VkBufferUsageFlag.VERTEX_BUFFER);
			}
			if(idxBuf == null || idxBuf.size()<idxSize){
				if(idxBuf != null){
					core.device.waitIdle();
					idxBuf.destroy();
				}
				idxBuf = core.allocateHostBuffer(idxSize, VkBufferUsageFlag.INDEX_BUFFER);
			}
		}
	}
	
	private final ShaderModuleSet shader;
	
	private final VulkanCore core;
	
	private final VkPipeline pipeline;
	
	private final VkDescriptorSetLayout dsLayoutConst;
	private final VkDescriptorSet       dsSetConst;
	
	private final VulkanTexture emptyTexture;
	private       VulkanTexture imGuiFontTexture;
	
	public ImGUIRenderer(VulkanCore core) throws VulkanCodeException{
		this.core = core;
		shader = new ShaderModuleSet(core, "ImGUI", ShaderType.VERTEX, ShaderType.FRAGMENT);
		
		emptyTexture = core.uploadTexture(1, 1, ByteBuffer.allocateDirect(1), VkFormat.R8_UNORM, 1);
		
		dsLayoutConst = core.globalDescriptorPool.createDescriptorSetLayout(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.UNIFORM_BUFFER),
			new Descriptor.LayoutBinding(1, VkShaderStageFlag.FRAGMENT, VkDescriptorType.COMBINED_IMAGE_SAMPLER)
		);
		dsSetConst = dsLayoutConst.createDescriptorSet();
		
		pipeline = VkPipeline.builder(core.renderPass, shader)
		                     .blending(VkPipeline.Blending.STANDARD)
		                     .multisampling(core.physicalDevice.samples, false)
		                     .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
		                     .cullMode(VkCullModeFlag.NONE)
		                     .addDesriptorSetLayout(dsLayoutConst)
		                     .addPushConstantRange(VkShaderStageFlag.VERTEX, 0, PushConstant.SIZE)
		                     .addVertexInput(0, 0, VkFormat.R32G32_SFLOAT, 0)
		                     .addVertexInput(1, 0, VkFormat.R32G32_SFLOAT, 4*2)
		                     .addVertexInput(2, 0, VkFormat.R8G8B8A8_UNORM, 4*4)
		                     .addVertexInputBinding(0, ImDrawData.sizeOfImDrawVert(), VkVertexInputRate.VERTEX)
		                     .build();
	}
	
	private VulkanTexture createFontsTexture() throws VulkanCodeException{
		final ImFontAtlas fontAtlas = ImGui.getIO().getFonts();
		
		ImInt width  = new ImInt(), height = new ImInt();
		var   pixels = fontAtlas.getTexDataAsAlpha8(width, height);
		
		return core.uploadTexture(width.get(), height.get(), pixels, VkFormat.R8_UNORM, 1);
	}
	
	public void submit(CommandBuffer buf, int frameID, RenderResource resource, ImDrawData drawData) throws VulkanCodeException{
		var sizeOfVertex = ImDrawData.sizeOfImDrawVert();
		var sizeOfIndex  = ImDrawData.sizeOfImDrawIdx();
		
		var vtxSize = Iters.range(0, drawData.getCmdListsCount()).map(drawData::getCmdListVtxBufferSize).sum()*sizeOfVertex;
		var idxSize = Iters.range(0, drawData.getCmdListsCount()).map(drawData::getCmdListIdxBufferSize).sum()*sizeOfIndex;
		if(vtxSize == 0 || idxSize == 0) return;
		
		var textures = Iters.range(0, drawData.getCmdListsCount()).box()
		                    .flatMap(n -> Iters.range(0, drawData.getCmdListCmdBufferSize(n))
		                                       .mapToObj(cmdIdx -> drawData.getCmdListCmdBufferTextureId(n, cmdIdx))
		                    ).toModSequencedSet();
		
		var winSize = drawData.getDisplaySize();
		var winPos  = drawData.getDisplayPos();
		
		buf.bindPipeline(pipeline);
		buf.setViewport(new Rect2D((int)winSize.x, (int)winSize.y));
		switch(textures.size()){
			case 1 -> {
				long textureID = textures.getFirst();
				if(textureID != 0){
					throw new NotImplementedException("More imgui textures");
				}
				if(imGuiFontTexture == null){
					imGuiFontTexture = createFontsTexture();
					dsSetConst.update(List.of(
						new Descriptor.LayoutDescription.TextureBuff(1, imGuiFontTexture, VkImageLayout.SHADER_READ_ONLY_OPTIMAL)
					), frameID);
				}
			}
			default -> throw new IllegalStateException("Unsupported texture count: " + textures.size());
		}
		buf.bindDescriptorSet(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 0, dsSetConst);
		
		resource.ensureBuffers(core, vtxSize, idxSize);
		
		var vtxCmdOffsets = new int[drawData.getCmdListsCount()];
		var idxCmdOffsets = new int[drawData.getCmdListsCount()];
		
		try(var vtxSes = resource.vtxBuf.update();
		    var idxSes = resource.idxBuf.update()){
			ByteBuffer vtxMem = vtxSes.getBuffer(), idxMem = idxSes.getBuffer();
			for(int n = 0; n<drawData.getCmdListsCount(); n++){
				vtxCmdOffsets[n] = vtxMem.position();
				idxCmdOffsets[n] = idxMem.position();
				vtxMem.put(drawData.getCmdListVtxBufferData(n));
				idxMem.put(drawData.getCmdListIdxBufferData(n));
			}
		}
		var idxType = switch(sizeOfIndex){
			case 2 -> VkIndexType.UINT16;
			case 4 -> VkIndexType.UINT32;
			default -> throw new IllegalStateException("Unexpected index size: " + sizeOfIndex);
		};
		var clipRect = new ImVec4();
		
		for(int n = 0; n<drawData.getCmdListsCount(); n++){
			
			for(int cmdIdx = 0; cmdIdx<drawData.getCmdListCmdBufferSize(n); cmdIdx++){
				drawData.getCmdListCmdBufferClipRect(clipRect, n, cmdIdx);
				
				var clipX = (int)(clipRect.x - winPos.x);
				var clipY = (int)(clipRect.y - winPos.y);
				var clipW = (int)(clipRect.z - clipRect.x);
				var clipH = (int)(clipRect.w - clipRect.y);
				buf.setScissor(new Rect2D(clipX, clipY, clipW, clipH));

//				long textureId = drawData.getCmdListCmdBufferTextureId(n, cmdIdx);
				int elemCount = drawData.getCmdListCmdBufferElemCount(n, cmdIdx);
				int idxOffset = drawData.getCmdListCmdBufferIdxOffset(n, cmdIdx);
				int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(n, cmdIdx);
				
				buf.bindVertexBuffer(resource.vtxBuf.buffer, 0, vtxCmdOffsets[n]);
				buf.bindIndexBuffer(resource.idxBuf.buffer, idxCmdOffsets[n], idxType);
				buf.pushConstants(pipeline.layout, VkShaderStageFlag.VERTEX, 0, PushConstant.make(
					-winPos.x,
					-winPos.y,
					1F/winSize.x,
					1F/winSize.y
				));
				buf.drawIndexed(elemCount, 1, idxOffset, vtxOffset, 0);
			}
		}
		
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		
		dsSetConst.destroy();
		dsLayoutConst.destroy();
		
		pipeline.destroy();
		emptyTexture.destroy();
		if(imGuiFontTexture != null) imGuiFontTexture.destroy();
		shader.destroy();
	}
}
