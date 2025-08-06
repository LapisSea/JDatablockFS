package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VkPipelineSet;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.Std430;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkIndexType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkVertexInputRate;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

import java.awt.Color;
import java.nio.ByteBuffer;

public class LineRenderer implements VulkanResource{
	
	private static final class PushConstant{
		private static final int SIZE = (2*3)*Float.BYTES;
		public static float[] make(Matrix3x2f matrix){
			return matrix.get(new float[6]);
		}
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
	
	public record RToken(Renderer.IndexedMeshBuffer resource, VkIndexType indexType, long vboOffset, long iboOffset, int indexCount){
		public void bind(CommandBuffer buf, int binding){
			buf.bindVertexBuffer(resource.vbos().buffer, binding, vboOffset);
			buf.bindIndexBuffer(resource.ibos().buffer, iboOffset, indexType);
		}
	}
	
	private final ShaderModuleSet shader;
	
	private final Device device;
	
	private final VkPipelineSet pipelines;
	
	public LineRenderer(VulkanCore core) throws VulkanCodeException{
		this.device = core.device;
		shader = new ShaderModuleSet(core, "Line", ShaderType.VERTEX, ShaderType.FRAGMENT);
		
		pipelines = new VkPipelineSet(
			rp -> VkPipeline.builder(rp, shader)
			                .blending(VkPipeline.Blending.STANDARD)
			                .multisampling(rp.samples, false)
			                .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
			                .addVertexInput(0, 0, VkFormat.R32G32_SFLOAT, Vert.XY)
			                .addVertexInput(1, 0, device.color8bitFormat, Vert.COLOR)
			                .addVertexInputBinding(0, Vert.SIZEOF, VkVertexInputRate.VERTEX)
			                .addPushConstantRange(VkShaderStageFlag.VERTEX, 0, PushConstant.SIZE)
			                .build()
		);
	}
	
	public RToken record(DeviceGC deviceGC, Renderer.IndexedMeshBuffer resource, Iterable<? extends Geometry.Path> paths) throws VulkanCodeException{
		
		var lines = Iters.from(paths).map(Geometry.Path::toPoints).toList();
		
		var size = Geometry.calculateMeshSize(lines);
		if(size.vertCount() == 0) return null;
		
		var indexCount = size.indexCount();
		var indexType  = indexCount<=Character.MAX_VALUE? VkIndexType.UINT16 : VkIndexType.UINT32;
		
		try(var mem = resource.requestMemory(deviceGC, device, size.vertCount()*(long)Vert.SIZEOF, indexCount*(long)indexType.byteSize)){
			var verts    = new Vert.Buf(mem.vboMem().getBuffer());
			var indecies = mem.iboMem().getBuffer();
			
			for(var mesh : Iters.from(lines).map(Geometry::generateThickLineMesh)){
				var off = verts.position();
				for(Geometry.Vertex vert : mesh.verts()){
					verts.put(vert.pos(), vert.color());
				}
				for(var i : mesh.indices()){
					var v = off + i;
					switch(indexType){
						case UINT16 -> indecies.putChar((char)v);
						case UINT32 -> indecies.putInt(v);
						case UINT8 -> indecies.put((byte)v);
					}
				}
			}
			return new RToken(resource, indexType, mem.vboMem().getMapOffset(), mem.iboMem().getMapOffset(), indexCount);
		}
	}
	
	public void submit(Extent2D viewSize, CommandBuffer buf, Matrix3x2f pvm, Iterable<RToken> tokens) throws VulkanCodeException{
		var pipeline = pipelines.get(buf.getCurrentRenderPass());
		buf.bindPipeline(pipeline);
		
		buf.setViewportScissor(new Rect2D(viewSize));
		buf.pushConstants(pipeline.layout, VkShaderStageFlag.VERTEX, 0, PushConstant.make(pvm));
		
		for(var resource : tokens){
			resource.bind(buf, 0);
			buf.drawIndexed(resource.indexCount, 1, 0, 0, 0);
		}
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		pipelines.destroy();
		shader.destroy();
	}
}
