package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.IndirectDrawBuffer;
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
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;
import org.joml.Matrix4f;
import org.lwjgl.system.CustomBuffer;
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
			var layout = __struct(
				__member(4*4*Float.BYTES),
				__member(Integer.BYTES)
			);
			
			SIZEOF = layout.getSize();
			MAT = layout.offsetof(0);
			TILE_WIDTH = layout.offsetof(1);
		}
		
		void mat(Matrix4f mat)       { mat.getToAddress(address + MAT); }
		void tileWidth(int tileWidth){ MemoryUtil.memPutInt(address + TILE_WIDTH, tileWidth); }
		void set(Matrix4f mat, int tileWidth){
			mat(mat);
			tileWidth(tileWidth);
		}
		
		protected Uniform(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), buff); }
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
		
		int index(){ return MemoryUtil.memGetInt(address() + INDEX); }
		
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
		
		private UniformBuffer            uniform;
		private VkDescriptorSet.PerFrame dsSets;
		private BackedVkBuffer           bytesInfo;
		
		private IndirectDrawBuffer indirectDrawBuff;
		
		private int instanceCount;
		
		private void updateDescriptor(){
			dsSets.updateAll(List.of(
				new Descriptor.LayoutDescription.UniformBuff(0, uniform),
				new Descriptor.LayoutDescription.TypeBuff(1, VkDescriptorType.STORAGE_BUFFER, bytesInfo.buffer)
			));
		}
		@Override
		public void destroy() throws VulkanCodeException{
			if(uniform != null){
				uniform.destroy();
				dsSets.destroy();
				bytesInfo.destroy();
				indirectDrawBuff.destroy();
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
	
	public record DrawRange(int from, int to, Color color){
		public DrawRange{
			if(from>to){
				throw new IllegalArgumentException();
			}
		}
	}
	
	public void record(RenderResource resource, int frameId, Matrix4f transform) throws VulkanCodeException{
		resource.uniform.updateAs(frameId, Uniform::new, u -> u.mat(transform));
	}
	public void record(RenderResource resource, Matrix4f transform, int tileWidth, byte[] data, Iterable<DrawRange> ranges) throws VulkanCodeException{
		if(resource.uniform == null){
			resource.uniform = core.allocateUniformBuffer(Uniform.SIZEOF, false);
			resource.dsSets = dsLayout.createDescriptorSetsPerFrame();
			resource.indirectDrawBuff = core.allocateIndirectBuffer(256);
		}
		
		GByte.Buf[] byteBuffers = new GByte.Buf[256];
		
		for(DrawRange range : ranges){
			for(int i = range.from; i<range.to; i++){
				var b   = Byte.toUnsignedInt(data[i]);
				var buf = byteBuffers[b];
				if(buf == null) buf = byteBuffers[b] = new GByte.Buf(MemoryUtil.memAlloc(GByte.SIZEOF*8));
				else if(!buf.hasRemaining()){
					var nb = new GByte.Buf(MemoryUtil.memAlloc(GByte.SIZEOF*buf.capacity()*2));
					nb.put(buf.flip());
					buf.free();
					buf = byteBuffers[b] = nb;
				}
				buf.put(i, range.color);
			}
		}
		
		var cap = Iters.from(byteBuffers).nonNulls().map(CustomBuffer::flip).mapToInt(CustomBuffer::remaining).sum();
		if(resource.bytesInfo == null || resource.bytesInfo.size()/GByte.SIZEOF<cap){
			if(resource.bytesInfo != null){
				core.device.waitIdle();
				resource.bytesInfo.destroy();
			}
			resource.bytesInfo = core.allocateHostBuffer(GByte.SIZEOF*(long)cap, VkBufferUsageFlag.STORAGE_BUFFER);
			resource.updateDescriptor();
		}
		
		try(var draws = resource.indirectDrawBuff.update();
		    var ses = resource.bytesInfo.updateAs(GByte.Buf::new)){
			var instancesBuff = ses.val;
			draws.clear();
			
			for(int i = 0; i<byteBuffers.length; i++){
				var buf = byteBuffers[i];
				if(buf == null) continue;
				var info = meshInfos[i];
				draws.draw(info.vertCount, buf.remaining(), info.vertPos, instancesBuff.position());
				instancesBuff.put(buf);
				buf.free();
			}
			
			resource.instanceCount = draws.buffer.position();
		}
		
		for(int i = 0; i<VulkanCore.MAX_IN_FLIGHT_FRAMES; i++){
			resource.uniform.updateAs(i, Uniform::new, u -> u.set(transform, tileWidth));
		}
	}
	
	public void submit(CommandBuffer buf, int frameID, RenderResource resource) throws VulkanCodeException{
		buf.bindPipeline(pipeline);
		buf.setViewportScissor(new Rect2D(core.swapchain.extent));
		
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 0, core.globalUniformSets.get(frameID), dsSetConst);
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 2, resource.dsSets.get(frameID));
		
		buf.drawIndirect(resource.indirectDrawBuff, 0, resource.instanceCount);
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
