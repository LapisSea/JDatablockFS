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
	
	record RenderResource(UniformBuffer uniform, VkDescriptorSet.PerFrame dsSets, BackedVkBuffer buffer) implements VulkanResource{
		
		@Override
		public void destroy() throws VulkanCodeException{
			uniform.destroy();
			dsSets.destroy();
			buffer.destroy();
		}
	}
	
	private VulkanCore     core;
	private MeshInfo[]     meshInfos;
	private BackedVkBuffer verts;
	
	private VkDescriptorSetLayout dsLayoutConst;
	private VkDescriptorSet       dsSetConst;
	
	private VkDescriptorSetLayout dsLayout;
	private VkPipeline            pipeline;
	
	private RenderResource r1;
	private RenderResource r2;
	
	public void init(VulkanCore core) throws VulkanCodeException{
		this.core = core;
		shader.init(core);
		
		computeByteVerts(core);
		
		try(var dsDesc = new Descriptor.LayoutDescription()
			                 .bind(0, VkShaderStageFlag.VERTEX, verts.buffer, VkDescriptorType.STORAGE_BUFFER)){
			
			dsLayoutConst = core.globalDescriptorPool.createDescriptorSetLayout(dsDesc.bindings());
			dsSetConst = dsLayoutConst.createDescriptorSet();
			
			dsSetConst.update(dsDesc.bindData(), -1);
		}
		
		
		try(var dsDesc = new Descriptor.LayoutDescription()){
			
			var uniform = core.allocateUniformBuffer(Uniform.SIZEOF, false);
			var bb      = core.allocateHostBuffer((long)(8*8)*GByte.SIZEOF, VkBufferUsageFlag.STORAGE_BUFFER);
			
			dsDesc.bind(0, VkShaderStageFlag.VERTEX, uniform);
			dsDesc.bind(1, VkShaderStageFlag.VERTEX, bb.buffer, VkDescriptorType.STORAGE_BUFFER);
			
			dsLayout = core.globalDescriptorPool.createDescriptorSetLayout(dsDesc.bindings());
			
			{
				var descs = dsLayout.createDescriptorSetsPerFrame();
				dsDesc.bind(0, VkShaderStageFlag.VERTEX, uniform);
				dsDesc.bind(1, VkShaderStageFlag.VERTEX, bb.buffer, VkDescriptorType.STORAGE_BUFFER);
				descs.updateAll(dsDesc.bindData());
				
				r1 = new RenderResource(uniform, descs, bb);
			}
			{
				var unif  = core.allocateUniformBuffer(Uniform.SIZEOF, false);
				var bb2   = core.allocateHostBuffer((long)(8*8)*GByte.SIZEOF, VkBufferUsageFlag.STORAGE_BUFFER);
				var descs = dsLayout.createDescriptorSetsPerFrame();
				dsDesc.bind(0, VkShaderStageFlag.VERTEX, unif);
				dsDesc.bind(1, VkShaderStageFlag.VERTEX, bb2.buffer, VkDescriptorType.STORAGE_BUFFER);
				descs.updateAll(dsDesc.bindData());
				
				r2 = new RenderResource(unif, descs, bb2);
			}
		}
		
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
	
	private int a = 0, c;
	private long t, t2;
	
	public void render(CommandBuffer buf, int frameID) throws VulkanCodeException{
		
		buf.bindPipeline(pipeline, true);
		
		buf.setViewportScissor(new Rect2D(core.swapchain.extent));
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 0, core.globalUniformSets.get(frameID), dsSetConst);
		
		r1.buffer.updateAs(GByte.Buf::new, b -> {
			b.put(0, Color.CYAN);
			b.put(1, Color.YELLOW);
			b.put(3, Color.PINK);
		});
		r2.buffer.updateAs(GByte.Buf::new, b -> {
			b.put(2, Color.RED);
			b.put(4, Color.blue);
		});
		
		r1.uniform.updateAs(frameID, Uniform::new, u -> u.set(new Matrix4f().translate(20, 40, 0).scale(100), 8));
		r2.uniform.updateAs(frameID, Uniform::new, u -> u.set(new Matrix4f().translate(20, 40, 0).scale(100), 8));
		
		if(System.currentTimeMillis() - t>50){
			a++;
			if(a == 256) a = 0;
			t = System.currentTimeMillis();
		}
		if(System.currentTimeMillis() - t2>150){
			c++;
			if(c == 256) c = 0;
			t2 = System.currentTimeMillis();
		}
		
		int b = a;
		
		
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 2, r1.dsSets.get(frameID));
		var info = meshInfos[b];
		buf.draw(info.vertCount, 3, info.vertPos, 0);
		
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 2, r2.dsSets.get(frameID));
		info = meshInfos[c];
		buf.draw(info.vertCount, 2, info.vertPos, 0);
		
	}
	
	
	@Override
	public void destroy() throws VulkanCodeException{
		pipeline.destroy();
		
		r1.destroy();
		r2.destroy();
		dsLayout.destroy();
		
		dsSetConst.destroy();
		dsLayoutConst.destroy();
		
		verts.destroy();
		
		shader.destroy();
	}
}
