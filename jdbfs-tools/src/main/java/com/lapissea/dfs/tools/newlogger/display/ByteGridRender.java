package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.UniformBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.util.UtilL;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ByteGridRender implements VulkanResource{
	
	private final ShaderModuleSet shader = new ShaderModuleSet("ByteGrid", ShaderType.VERTEX, ShaderType.FRAGMENT);
	
	private static class Vert{
		static final int SIZE = (2)*4 + 4;
		
		static final int T_BACK  = 0;
		static final int T_SET   = 1;
		static final int T_WRITE = 1;
		
		private static void putQuad(ByteBuffer bb, int x, int y, int w, int h, int type){
			put(bb, x/3F, y/3F, type);
			put(bb, x/3F, (y + h)/3F, type);
			put(bb, (x + w)/3F, (y + h)/3F, type);
			put(bb, x/3F, y/3F, type);
			put(bb, (x + w)/3F, (y + h)/3F, type);
			put(bb, (x + w)/3F, y/3F, type);
		}
		
		static void put(ByteBuffer buffer, float x, float y, int type){
			buffer.putFloat(x).putFloat(y).putInt(type);
		}
	}
	
	private static class Uniform{
		private static final int SIZE = (4*4 + 1)*4;
		
		private static void put(ByteBuffer dest, Matrix4f mat, int tileWidth){
			mat.get(dest);
			dest.position(dest.position() + (4*4)*4);
			dest.putInt(tileWidth);
		}
	}
	
	private record MeshInfo(int vertPos, int vertCount){ }
	
	private VulkanCore     core;
	private MeshInfo[]     meshInfos;
	private UniformBuffer  uniform;
	private BackedVkBuffer verts;
	
	private VkDescriptorSetLayout dsLayout;
	private List<VkDescriptorSet> dsSets;
	private VkPipeline            pipeline;
	
	public void init(VulkanCore core) throws VulkanCodeException{
		this.core = core;
		shader.init(core);
		
		computeByteVerts(core);
		
		uniform = core.allocateUniformBuffer(Uniform.SIZE, false);
		
		var dsDesc = new Descriptor.LayoutDescription()
			             .bind(0, Flags.of(VkShaderStageFlag.VERTEX), core.globalUniforms)
			             .bind(1, Flags.of(VkShaderStageFlag.VERTEX), uniform)
			             .bind(2, Flags.of(VkShaderStageFlag.VERTEX), verts.buffer, VkDescriptorType.STORAGE_BUFFER);
		
		dsLayout = core.globalDescriptorPool.createDescriptorSetLayout(dsDesc.bindings());
		dsSets = dsLayout.createDescriptorSets(VulkanCore.MAX_IN_FLIGHT_FRAMES);
		
		for(int i = 0; i<dsSets.size(); i++){
			dsSets.get(i).update(dsDesc.bindData(), i);
		}
		
		pipeline = VkPipeline.builder(core.renderPass, shader)
		                     .blending(VkPipeline.Blending.STANDARD)
		                     .multisampling(core.physicalDevice.samples, false)
		                     .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
		                     .addDesriptorSetLayout(dsLayout)
		                     .build();
		
		
		dsDesc.close();
	}
	
	private void computeByteVerts(VulkanCore core) throws VulkanCodeException{
		record SetBitsQuad(int x, int y, int w, int h){ }
		
		List<List<SetBitsQuad>> setQuadsSet = new ArrayList<>(256);
		for(int val = 0; val<256; val++){
			List<SetBitsQuad> quads = new ArrayList<>(8);
			setQuadsSet.add(quads);
			for(int bit = 0; bit<8; bit++){
				if(UtilL.checkFlag(val, 1<<bit)){
					quads.add(new SetBitsQuad(bit%3, bit/3, 1, 1));
				}
			}
			boolean change;
			//right merge
			find:
			do{
				change = false;
				for(int i = 0; i<quads.size(); i++){
					SetBitsQuad quad = quads.get(i);
					for(int j = 0; j<quads.size(); j++){
						SetBitsQuad candidate = quads.get(j);
						if(candidate.h == quad.h && candidate.y == quad.y && candidate.x == (quad.x + quad.w)){
							quads.set(i, new SetBitsQuad(quad.x, quad.y, quad.w + candidate.w, quad.h));
							quads.remove(j);
							change = true;
							continue find;
						}
					}
				}
			}while(change);
			//bottom
			find:
			do{
				change = false;
				for(int i = 0; i<quads.size(); i++){
					SetBitsQuad quad = quads.get(i);
					for(int j = 0; j<quads.size(); j++){
						SetBitsQuad candidate = quads.get(j);
						if(candidate.w == quad.w && candidate.x == quad.x && candidate.y == (quad.y + quad.h)){
							quads.set(i, new SetBitsQuad(quad.x, quad.y, quad.w, quad.h + candidate.h));
							quads.remove(j);
							change = true;
							continue find;
						}
					}
				}
			}while(change);
		}
		
		meshInfos = new MeshInfo[256];
		int vertsTotal = 0;
		for(int i = 0; i<meshInfos.length; i++){
			var quads = setQuadsSet.get(i);
			
			int verts   = (quads.size() + 1)*6;
			int vertPos = vertsTotal;
			vertsTotal += verts;
			meshInfos[i] = new MeshInfo(vertPos, verts);
		}
		
		verts = core.allocateLocalStorageBuffer((long)vertsTotal*Vert.SIZE, bb -> {
			for(List<SetBitsQuad> quads : setQuadsSet){
				Vert.putQuad(bb, 0, 0, 3, 3, Vert.T_BACK);
				for(var quad : quads){
					Vert.putQuad(bb, quad.x, quad.y, quad.w, quad.h, Vert.T_SET);
				}
			}
		});
	}
	
	private int  a = 0;
	private long t;
	public void render(CommandBuffer buf, int frameID) throws VulkanCodeException{
		
		buf.bindPipeline(pipeline, true);
		buf.bindDescriptorSet(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 0, dsSets.get(frameID));
		
		buf.setViewportScissor(new Rect2D(core.swapchain.extent));
		
		uniform.update(frameID, b -> Uniform.put(b, new Matrix4f().translate(20, 40, 0).scale(100), 32));
		
		if(System.currentTimeMillis() - t>50){
			a++;
			if(a == 256) a = 0;
			t = System.currentTimeMillis();
		}
		
		int b = a;
		
		var info = meshInfos[b];
		buf.draw(info.vertCount, 1, info.vertPos, 0);
	}
	
	
	@Override
	public void destroy() throws VulkanCodeException{
		pipeline.destroy();
		dsLayout.destroy();
		for(VkDescriptorSet dsSet : dsSets){
			dsSet.destroy();
		}
		
		uniform.destroy();
		verts.destroy();
		
		shader.destroy();
	}
}
