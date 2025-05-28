package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
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
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.List;

public class ImGUIRenderer implements VulkanResource{
	
	
	public static class RenderResource implements VulkanResource{
		
		private BackedVkBuffer vbos;
		private BackedVkBuffer ibos;
		private int            indexCount;
		
		@Override
		public void destroy() throws VulkanCodeException{
			if(vbos == null) return;
			vbos.destroy();
			ibos.destroy();
		}
	}
	
	private final ShaderModuleSet shader;
	
	private final VulkanCore core;
	
	private final VkPipeline pipeline;
	
	private BackedVkBuffer vtxBuf;
	private BackedVkBuffer idxBuf;
	
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
	
	public void submit(VulkanWindow window, CommandBuffer buf, int frameID, ImDrawData drawData) throws VulkanCodeException{
		var sizeOfVertex = ImDrawData.sizeOfImDrawVert();
		var sizeOfIndex  = ImDrawData.sizeOfImDrawIdx();
		
		var vtxSize = Iters.range(0, drawData.getCmdListsCount()).map(drawData::getCmdListVtxBufferSize).sum()*sizeOfVertex;
		var idxSize = Iters.range(0, drawData.getCmdListsCount()).map(drawData::getCmdListIdxBufferSize).sum()*sizeOfIndex;
		if(vtxSize == 0 || idxSize == 0) return;
		
		var textures = Iters.range(0, drawData.getCmdListsCount()).box()
		                    .flatMap(n -> Iters.range(0, drawData.getCmdListCmdBufferSize(n))
		                                       .mapToObj(cmdIdx -> drawData.getCmdListCmdBufferTextureId(n, cmdIdx))
		                    ).toModSequencedSet();
		
		var e                = window.swapchain.extent;
		var pos              = window.getGlfwWindow().pos;
		var projectionMatrix = new Matrix4f().ortho(pos.x(), pos.x() + e.width, pos.y(), pos.y() + e.height, -10, 10, true);
		window.globalUniforms.update(frameID, b -> b.mat(projectionMatrix));
		
		buf.bindPipeline(pipeline);
		buf.setViewport(new Rect2D(window.swapchain.extent));
		switch(textures.size()){
			case 0 -> dsSetConst.update(List.of(
				new Descriptor.LayoutDescription.UniformBuff(0, window.globalUniforms),
				new Descriptor.LayoutDescription.TextureBuff(1, emptyTexture, VkImageLayout.SHADER_READ_ONLY_OPTIMAL)
			), -1);
			case 1 -> {
				long textureID = textures.getFirst();
				if(textureID != 0){
					throw new NotImplementedException("More imgui textures");
				}
				if(imGuiFontTexture == null){
					imGuiFontTexture = createFontsTexture();
				}
				dsSetConst.update(List.of(
					new Descriptor.LayoutDescription.UniformBuff(0, window.globalUniforms),
					new Descriptor.LayoutDescription.TextureBuff(1, imGuiFontTexture, VkImageLayout.SHADER_READ_ONLY_OPTIMAL)
				), frameID);
			}
			default -> throw new IllegalStateException("Unsupported texture count: " + textures.size());
		}
		buf.bindDescriptorSet(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 0, dsSetConst);
		
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
		
		var vtxCmdOffsets = new int[drawData.getCmdListsCount()];
		var idxCmdOffsets = new int[drawData.getCmdListsCount()];
		
		try(var vtxSes = vtxBuf.update();
		    var idxSes = idxBuf.update()){
			ByteBuffer vtxMem = vtxSes.getBuffer(), idxMem = idxSes.getBuffer();
			for(int n = 0; n<drawData.getCmdListsCount(); n++){
				vtxCmdOffsets[n] = vtxMem.position();
				idxCmdOffsets[n] = idxMem.position();
				vtxMem.put(drawData.getCmdListVtxBufferData(n));
				idxMem.put(drawData.getCmdListIdxBufferData(n));
			}
//			LogUtil.println(vtxMem.flip().asFloatBuffer());
//			LogUtil.println(idxMem.flip().asShortBuffer());
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
				clipRect.x -= pos.x();
				clipRect.z -= pos.x();
				clipRect.y -= pos.y();
				clipRect.w -= pos.y();
				buf.setScissor(new Rect2D(Math.max(0, (int)clipRect.x), Math.max(0, (int)clipRect.y), (int)(clipRect.z - clipRect.x), (int)(clipRect.w - clipRect.y)));
//				long textureId = drawData.getCmdListCmdBufferTextureId(n, cmdIdx);
				int elemCount = drawData.getCmdListCmdBufferElemCount(n, cmdIdx);
				int idxOffset = drawData.getCmdListCmdBufferIdxOffset(n, cmdIdx);
				int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(n, cmdIdx);
				
				buf.bindVertexBuffer(vtxBuf.buffer, 0, vtxCmdOffsets[n]);
				buf.bindIndexBuffer(idxBuf.buffer, idxCmdOffsets[n], idxType);
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
		imGuiFontTexture.destroy();
		if(idxBuf != null){
			idxBuf.destroy();
			vtxBuf.destroy();
		}
		shader.destroy();
	}
}
