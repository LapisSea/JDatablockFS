package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkIndexType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkVertexInputRate;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.dfs.utils.iterableplus.Iters;
import imgui.ImDrawData;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.type.ImInt;

import java.nio.ByteBuffer;

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
	
	private final VkDescriptorSetLayout dsLayoutTexture;
	
	public final TextureRegistry.Scope textureScope;
	
	public ImGUIRenderer(VulkanCore core) throws VulkanCodeException{
		this.core = core;
		textureScope = core.textureRegistry.new Scope();
		
		shader = new ShaderModuleSet(core, "ImGUI", ShaderType.VERTEX, ShaderType.FRAGMENT);
		
		dsLayoutTexture = core.globalDescriptorPool.createDescriptorSetLayout(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.FRAGMENT, VkDescriptorType.COMBINED_IMAGE_SAMPLER)
		);
		
		pipeline = VkPipeline.builder(core.renderPass, shader)
		                     .blending(VkPipeline.Blending.STANDARD)
		                     .multisampling(core.physicalDevice.samples, false)
		                     .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
		                     .cullMode(VkCullModeFlag.NONE)
		                     .addDesriptorSetLayout(dsLayoutTexture)
		                     .addPushConstantRange(VkShaderStageFlag.VERTEX, 0, PushConstant.SIZE)
		                     .addVertexInput(0, 0, VkFormat.R32G32_SFLOAT, 0)
		                     .addVertexInput(1, 0, VkFormat.R32G32_SFLOAT, 4*2)
		                     .addVertexInput(2, 0, VkFormat.R8G8B8A8_UNORM, 4*4)
		                     .addVertexInputBinding(0, ImDrawData.sizeOfImDrawVert(), VkVertexInputRate.VERTEX)
		                     .build();
		
	}
	
	public void submit(CommandBuffer buf, int frameID, RenderResource resource, ImDrawData drawData) throws VulkanCodeException{
		if(drawData.isNotValidPtr()) return;
		
		var sizeOfVertex = ImDrawData.sizeOfImDrawVert();
		var sizeOfIndex  = ImDrawData.sizeOfImDrawIdx();
		
		var vtxSize = Iters.range(0, drawData.getCmdListsCount()).map(drawData::getCmdListVtxBufferSize).sum()*sizeOfVertex;
		var idxSize = Iters.range(0, drawData.getCmdListsCount()).map(drawData::getCmdListIdxBufferSize).sum()*sizeOfIndex;
		if(vtxSize == 0 || idxSize == 0) return;
		
		var winSize = drawData.getDisplaySize();
		var winPos  = drawData.getDisplayPos();
		
		buf.bindPipeline(pipeline);
		buf.setViewport(new Rect2D((int)winSize.x, (int)winSize.y));
		
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
		
		long lastTextureID = -1;
		
		for(int n = 0; n<drawData.getCmdListsCount(); n++){
			
			for(int cmdIdx = 0; cmdIdx<drawData.getCmdListCmdBufferSize(n); cmdIdx++){
				drawData.getCmdListCmdBufferClipRect(clipRect, n, cmdIdx);
				
				var clipX = (int)(clipRect.x - winPos.x);
				var clipY = (int)(clipRect.y - winPos.y);
				var clipW = (int)(clipRect.z - clipRect.x);
				var clipH = (int)(clipRect.w - clipRect.y);
				buf.setScissor(new Rect2D(clipX, clipY, clipW, clipH));
				
				long textureId = drawData.getCmdListCmdBufferTextureId(n, cmdIdx);
				int  elemCount = drawData.getCmdListCmdBufferElemCount(n, cmdIdx);
				int  idxOffset = drawData.getCmdListCmdBufferIdxOffset(n, cmdIdx);
				int  vtxOffset = drawData.getCmdListCmdBufferVtxOffset(n, cmdIdx);
				
				if(lastTextureID != textureId){
					buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, 0, textureScope.getTextureBind(dsLayoutTexture, textureId));
				}
				
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
	
	public void checkFonts(){
		var fontAtlas = ImGui.getIO().getFonts();
		if(fontAtlas.isBuilt()) return;
		if(!fontAtlas.build()){
			throw new RuntimeException("Failed to build font atlas");
		}
		
		ImInt width  = new ImInt(), height = new ImInt();
		var   pixels = fontAtlas.getTexDataAsAlpha8(width, height);
		try{
			var id = core.textureRegistry.loadTextureAsID(width.get(), height.get(), pixels, VkFormat.R8_UNORM, 1);
			fontAtlas.setTexID(id);
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to load font atlas texture", e);
		}
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		
		dsLayoutTexture.destroy();
		
		pipeline.destroy();
		textureScope.destroy();
		shader.destroy();
	}
}
