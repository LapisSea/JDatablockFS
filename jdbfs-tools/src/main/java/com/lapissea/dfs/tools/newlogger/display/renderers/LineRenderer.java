package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.Std140;
import com.lapissea.dfs.tools.newlogger.display.vk.Std430;
import com.lapissea.dfs.tools.newlogger.display.vk.UniformBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkIndexType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.List;

public class LineRenderer implements VulkanResource{
	
	private static class Uniform extends Struct<Uniform>{
		private static final int SIZEOF;
		private static final int MAT;
		
		static{
			var layout = __struct(
				Std140.__mat4()
			);
			
			SIZEOF = layout.getSize();
			MAT = layout.offsetof(0);
		}
		
		Matrix4f mat(){ return new Matrix4f().setFromAddress(address + MAT); }
		
		Uniform mat(Matrix4f mat){
			mat.getToAddress(address + MAT);
			return this;
		}
		
		void set(Matrix4f mat){
			mat(mat);
		}
		
		protected Uniform(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), buff); }
		protected Uniform(long address)   { super(address, null); }
		@Override
		protected Uniform create(long address, ByteBuffer container){ return new Uniform(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
	}
	
	private static class Vert extends Struct<Vert>{
		private static final int SIZEOF;
		private static final int XY;
		private static final int COLOR;
		
		static{
			var layout = __struct(
				Std430.__vec2(),
				Std430.__u8vec4()
			);
			
			SIZEOF = layout.getSize();
			XY = layout.offsetof(0);
			COLOR = layout.offsetof(1);
		}
		
		void set(float x, float y, Color color){
			var address = this.address;
			MemoryUtil.memPutFloat(address + XY, x);
			MemoryUtil.memPutFloat(address + XY + Float.BYTES, y);
			MemoryUtil.memPutInt(address + COLOR, VUtils.toRGBAi4(color));
		}
		
		protected Vert(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), buff); }
		protected Vert(long address)   { super(address, null); }
		@Override
		protected Vert create(long address, ByteBuffer container){ return new Vert(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
		
		
		static class Buf extends StructBuffer<Vert, Buf>{
			private static final Vert FAC = new Vert(-1);
			protected Buf(ByteBuffer container){ super(container, container.remaining()/SIZEOF); }
			@Override
			protected Vert getElementFactory(){ return FAC; }
			@Override
			protected Buf self(){ return this; }
			@Override
			protected Buf create(long address, ByteBuffer container, int mark, int position, int limit, int capacity){
				throw NotImplementedException.infer();//TODO: implement Buf.create()
			}
			void put(Vector2f pos, Color color){
				get().set(pos.x, pos.y, color);
			}
		}
	}
	
	public static class RenderResource implements VulkanResource{
		
		private UniformBuffer<Uniform> uniform;
		
		private BackedVkBuffer           vbos;
		private BackedVkBuffer           ibos;
		private VkDescriptorSet.PerFrame desc;
		private int                      indexCount;
		private VkIndexType              indexType;
		
		void updateDescriptor(){
			desc.updateAll(List.of(
				new Descriptor.LayoutDescription.UniformBuff(0, uniform),
				new Descriptor.LayoutDescription.TypeBuff(1, VkDescriptorType.STORAGE_BUFFER, vbos.buffer)
			));
		}
		
		@Override
		public void destroy() throws VulkanCodeException{
			if(vbos == null) return;
			desc.destroy();
			uniform.destroy();
			vbos.destroy();
			ibos.destroy();
		}
	}
	
	private final ShaderModuleSet shader = new ShaderModuleSet("Line", ShaderType.VERTEX, ShaderType.FRAGMENT);
	
	private VulkanCore core;
	
	private VkPipeline            pipeline;
	private VkDescriptorSetLayout dsLayout;
	
	public void init(VulkanCore core) throws VulkanCodeException{
		this.core = core;
		shader.init(core);
		
		dsLayout = core.globalDescriptorPool.createDescriptorSetLayout(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.UNIFORM_BUFFER),
			new Descriptor.LayoutBinding(1, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER)
		);
		
		pipeline = VkPipeline.builder(core.renderPass, shader)
		                     .blending(VkPipeline.Blending.STANDARD)
		                     .multisampling(core.physicalDevice.samples, false)
		                     .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
		                     .addDesriptorSetLayout(core.globalUniformLayout)
		                     .addDesriptorSetLayout(dsLayout)
		                     .build();
	}
	
	public void record(RenderResource resource, Matrix4f mat, Iterable<Geometry.Path> paths) throws VulkanCodeException{
		boolean updateDescriptor = false;
		
		if(resource.desc == null){
			resource.desc = dsLayout.createDescriptorSetsPerFrame();
			resource.uniform = core.allocateUniformBuffer(Uniform.SIZEOF, false, Uniform::new);
			updateDescriptor = true;
		}
		
		var lines = Iters.from(paths).map(Geometry.Path::toPoints).toList();
		
		var size = Geometry.calculateMeshSize(lines);
		
		resource.indexType = size.indexCount()<=Character.MAX_VALUE? VkIndexType.UINT16 : VkIndexType.UINT32;
		resource.indexCount = size.indexCount();
		
		if(resource.vbos == null || resource.vbos.size()/Vert.SIZEOF<size.vertCount()){
			core.device.waitIdle();
			if(resource.vbos != null) resource.vbos.destroy();
			resource.vbos = core.allocateHostBuffer(size.vertCount()*(long)Vert.SIZEOF, VkBufferUsageFlag.STORAGE_BUFFER);
			updateDescriptor = true;
		}
		var indexSize = switch(resource.indexType){
			case UINT16 -> 2;
			case UINT32 -> 4;
			case UINT8 -> 1;
		};
		if(resource.ibos == null || resource.ibos.size()/indexSize<size.indexCount()){
			core.device.waitIdle();
			if(resource.ibos != null) resource.ibos.destroy();
			resource.ibos = core.allocateHostBuffer(size.indexCount()*(long)indexSize, VkBufferUsageFlag.INDEX_BUFFER);
			updateDescriptor = true;
		}
		
		if(updateDescriptor){
			resource.updateDescriptor();
		}
		
		resource.uniform.updateAll(e -> e.set(mat));
		
		core.device.waitIdle();
		try(var vertsSes = resource.vbos.updateAs(Vert.Buf::new);
		    var indeciesSes = resource.ibos.update()){
			var verts    = vertsSes.val;
			var indecies = indeciesSes.getBuffer();
			
			for(var mesh : Iters.from(lines).map(Geometry::generateThickLineMesh)){
				var off = verts.position();
				for(Geometry.Vertex vert : mesh.verts()){
					verts.put(vert.pos(), vert.color());
				}
				for(var i : mesh.indices()){
					var v = off + i;
					switch(resource.indexType){
						case UINT16 -> indecies.putChar((char)v);
						case UINT32 -> indecies.putInt(v);
						case UINT8 -> indecies.put((byte)v);
					}
				}
			}
		}
		
	}
	
	public void submit(CommandBuffer buf, int frameID, RenderResource resource) throws VulkanCodeException{
		buf.bindPipeline(pipeline);
		buf.setViewportScissor(new Rect2D(core.swapchain.extent));
		
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 0,
		                       core.globalUniformSets.get(frameID),
		                       resource.desc.get(frameID));
		
		buf.bindIndexBuffer(resource.ibos.buffer, 0, resource.indexType);
		buf.drawIndexed(resource.indexCount, 1, 0, 0, 0);
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		pipeline.destroy();
		dsLayout.destroy();
		shader.destroy();
	}
}
