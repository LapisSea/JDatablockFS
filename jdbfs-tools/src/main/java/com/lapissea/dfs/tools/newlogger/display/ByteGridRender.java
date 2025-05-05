package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.UniformBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

import java.awt.Color;
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
	
	private static class Uniform extends Struct<Uniform>{
		private static final int SIZEOF;
		private static final int MAT;
		private static final int TILE_WIDTH;
		
		static{
			Layout layout = __struct(
				__member(4*4*Float.BYTES),
				__member(Integer.BYTES)
			);
			
			SIZEOF = layout.getSize();
			
			MAT = layout.offsetof(0);
			TILE_WIDTH = layout.offsetof(1);
		}
		
		void set(Matrix4f mat, int tileWidth){
			mat.get(MemoryUtil.memFloatBuffer(address() + MAT, SIZEOF));
			MemoryUtil.memPutInt(address() + TILE_WIDTH, tileWidth);
		}
		
		protected Uniform(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), null); }
		protected Uniform(long address)   { super(address, null); }
		@Override
		protected Uniform create(long address, ByteBuffer container){ return new Uniform(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
	}
	
	private static class GByte extends Struct<GByte>{
		private static final int SIZEOF;
		private static final int INDEX;
		private static final int COLOR;
		
		static{
			Layout layout = __struct(
				__member(4),
				__member(4)
			);
			
			SIZEOF = layout.getSize();
			
			INDEX = layout.offsetof(0);
			COLOR = layout.offsetof(1);
		}
		
		static class Buf extends StructBuffer<GByte, Buf>{
			private static final GByte FAC = new GByte(-1);
			protected Buf(ByteBuffer container){ super(container, container.remaining()/SIZEOF); }
			@Override
			protected GByte getElementFactory(){ return FAC; }
			@Override
			protected Buf self(){ return this; }
			@Override
			protected Buf create(long address, ByteBuffer container, int mark, int position, int limit, int capacity){
				throw NotImplementedException.infer();//TODO: implement Buf.create()
			}
			void put(int index, Color color){ get().set(index, color); }
		}
		
		void set(int index, Color color){
			MemoryUtil.memPutInt(address() + INDEX, index);
			MemoryUtil.memPutInt(address() + COLOR, VUtils.toRGBAi4(color));
		}
		
		protected GByte(long address){ super(address, null); }
		@Override
		protected GByte create(long address, ByteBuffer container){ return new GByte(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
	}
	
	private record MeshInfo(int vertPos, int vertCount){ }
	
	public static final class RenderResource implements VulkanResource{
		private UniformBuffer   uniform;
		private VkDescriptorSet dsSets;
		private BackedVkBuffer  buffer;
		
		@Override
		public void destroy() throws VulkanCodeException{
			if(uniform != null){
				uniform.destroy();
				dsSets.destroy();
				buffer.destroy();
			}
		}
	}
	
	private VulkanCore     core;
	private MeshInfo[]     meshInfos;
	private BackedVkBuffer verts;
	
	private VkDescriptorSetLayout dsLayoutConst;
	private VkDescriptorSet       dsSetConst;
	
	private VkDescriptorSetLayout dsLayout;
	private VkPipeline            pipeline;
	
	public void init(VulkanCore core) throws VulkanCodeException{
		this.core = core;
		shader.init(core);
		
		computeByteVerts(core);
		
		dsLayoutConst = core.globalDescriptorPool.createDescriptorSetLayout(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER)
		);
		dsSetConst = dsLayoutConst.createDescriptorSet();
		
		dsSetConst.update(List.of(
			new Descriptor.LayoutDescription.TypeBuff(0, VkDescriptorType.STORAGE_BUFFER, verts.buffer)
		), -1);
		
		
		dsLayout = core.globalDescriptorPool.createDescriptorSetLayout(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.UNIFORM_BUFFER),
			new Descriptor.LayoutBinding(1, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER)
		);
		
		pipeline = VkPipeline.builder(core.renderPass, shader)
		                     .blending(VkPipeline.Blending.STANDARD)
		                     .multisampling(core.physicalDevice.samples, false)
		                     .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
		                     .addDesriptorSetLayout(core.globalUniformLayout)
		                     .addDesriptorSetLayout(dsLayoutConst)
		                     .addDesriptorSetLayout(dsLayout)
		                     .build();
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
	
	public void record(RenderResource resource) throws VulkanCodeException{
		if(resource.buffer == null){
			resource.buffer = core.allocateHostBuffer(GByte.SIZEOF*3L, VkBufferUsageFlag.STORAGE_BUFFER);
			
			resource.uniform = core.allocateUniformBuffer(Uniform.SIZEOF, false);
			resource.dsSets = dsLayout.createDescriptorSet();
			resource.dsSets.update(List.of(
				new Descriptor.LayoutDescription.UniformBuff(0, resource.uniform),
				new Descriptor.LayoutDescription.TypeBuff(1, VkDescriptorType.STORAGE_BUFFER, resource.buffer.buffer)
			), 0);
		}
		
		resource.buffer.updateAs(GByte.Buf::new, b -> {
			b.put(0, Color.CYAN);
			b.put(1, Color.YELLOW);
			b.put(3, Color.PINK);
		});
		for(int i = 0; i<VulkanCore.MAX_IN_FLIGHT_FRAMES; i++){
			resource.uniform.updateAs(i, Uniform::new, u -> u.set(new Matrix4f().translate(20, 40, 0).scale(100), 8));
		}
	}
	
	private int  a = 0;
	private long t;
	
	public void render(CommandBuffer buf, int frameID, RenderResource resource) throws VulkanCodeException{
		
		buf.bindPipeline(pipeline, true);
		
		buf.setViewportScissor(new Rect2D(core.swapchain.extent));
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 0, core.globalUniformSets.get(frameID), dsSetConst);
		
		if(System.currentTimeMillis() - t>50){
			a++;
			if(a == 256) a = 0;
			t = System.currentTimeMillis();
		}
		
		int b = a;
		
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 2, resource.dsSets);
		var info = meshInfos[b];
		buf.draw(info.vertCount, 3, info.vertPos, 0);
	}
	
	
	@Override
	public void destroy() throws VulkanCodeException{
		pipeline.destroy();
		
		dsLayout.destroy();
		
		dsSetConst.destroy();
		dsLayoutConst.destroy();
		
		verts.destroy();
		
		shader.destroy();
	}
}
