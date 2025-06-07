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
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.util.UtilL;
import org.joml.Matrix3x2f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class MsdfFontRender implements VulkanResource{
	
	private static final String TEXTURE_PATH = "/roboto/regular/atlas.png";
	private static final String LAYOUT_PATH  = "/roboto/regular/atlas.json";
	
	private static class Letter{
		private static final int SIZE = (2 + 4)*2;
		
		private static void put(ByteBuffer dest,
		                        float w, float h,
		                        float u0, float v0, float u1, float v1){
			assert u0>=0 && u0<=1;
			assert u1>=0 && u1<=1;
			assert v0>=0 && v0<=1;
			assert v1>=0 && v1<=1;
			dest.putShort(VUtils.toHalfFloat(w))
			    .putShort(VUtils.toHalfFloat(h))
			    .putChar((char)(u0*Character.MAX_VALUE))
			    .putChar((char)(v0*Character.MAX_VALUE))
			    .putChar((char)(u1*Character.MAX_VALUE))
			    .putChar((char)(v1*Character.MAX_VALUE));
		}
	}
	
	private static class Quad{
		private static final int SIZE = (2 + 1)*4;
		
		private static void put(ByteBuffer dest, float xOff, float yOff, int letterId){
			dest.putFloat(xOff).putFloat(yOff)
			    .putInt(letterId);
		}
	}
	
	private static class Uniform extends Struct<Uniform>{
		private static final int SIZEOF;
		private static final int POS_X;
		private static final int POS_Y;
		private static final int SCALE;
		private static final int COLOR;
		private static final int OUTLINE;
		private static final int X_SCALE;
		
		static{
			var layout = __struct(
				__member(Float.BYTES),
				__member(Float.BYTES),
				__member(Float.BYTES),
				__member(4),
				__member(Float.BYTES),
				__member(Float.BYTES)
			);
			
			SIZEOF = layout.getSize();
			POS_X = layout.offsetof(0);
			POS_Y = layout.offsetof(1);
			SCALE = layout.offsetof(2);
			COLOR = layout.offsetof(3);
			OUTLINE = layout.offsetof(4);
			X_SCALE = layout.offsetof(5);
		}
		
		void set(float posX, float posY, float scale, Color color, float outline, float xScale){
			var address = this.address;
			MemoryUtil.memPutFloat(address + POS_X, posX);
			MemoryUtil.memPutFloat(address + POS_Y, posY);
			MemoryUtil.memPutFloat(address + SCALE, scale);
			MemoryUtil.memPutInt(address + COLOR, VUtils.toRGBAi4(color));
			MemoryUtil.memPutFloat(address + OUTLINE, outline);
			MemoryUtil.memPutFloat(address + X_SCALE, xScale);
		}
		
		protected Uniform(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), buff); }
		protected Uniform(long address)   { super(address, null); }
		@Override
		protected Uniform create(long address, ByteBuffer container){ return new Uniform(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
	}
	
	private VulkanCore core;
	
	private final CompletableFuture<Glyphs.Table>  tableRes = CompletableFuture.supplyAsync(() -> Glyphs.loadTable(LAYOUT_PATH));
	private final CompletableFuture<VulkanTexture> atlas    = VulkanTexture.loadTexture(TEXTURE_PATH, false, () -> {
		UtilL.sleepWhile(() -> core == null);
		return core;
	});
	private final CompletableFuture<Void>          doneTask;
	
	private final ShaderModuleSet shader;
	private       BackedVkBuffer  tableGpu;
	
	public MsdfFontRender(VulkanCore core){
		this.core = Objects.requireNonNull(core);
		shader = new ShaderModuleSet(core, "msdfFont", ShaderType.VERTEX, ShaderType.FRAGMENT);
		
		var tableUploadTask = tableRes.whenComplete((table, e) -> {
			if(e != null) throw UtilL.uncheckedThrow(e);
			try{
				uploadTable(table);
			}catch(Throwable ex){
				throw new RuntimeException("Failed to create font table", ex);
			}
		});
		
		doneTask = CompletableFuture.allOf(atlas, tableUploadTask).whenComplete((v, e) -> {
			if(e != null) throw UtilL.uncheckedThrow(e);
			try{
				createResources();
			}catch(Throwable ex){
				throw new RuntimeException(ex);
			}
		});
	}
	private void uploadTable(Glyphs.Table table) throws VulkanCodeException{
		var count = table.index.size();
		tableGpu = core.allocateDeviceLocalBuffer(Letter.SIZE*(long)count, b -> {
			var   metrics = table.metrics;
			float fsScale = (float)(1/(metrics.ascender() - metrics.descender()));
			for(int i = 0; i<count; i++){
				Letter.put(
					b,
					(table.x1[i] - table.x0[i])*fsScale, (table.y0[i] - table.y1[i])*fsScale,
					table.u0[i], table.v0[i],
					table.u1[i], table.v1[i]
				);
			}
		});
	}
	
	private VkDescriptorSetLayout dsLayout;
	
	private VkDescriptorSetLayout dsLayoutConst;
	private VkDescriptorSet       dsSetConst;
	
	private VkPipeline pipeline;
	
	private void createResources() throws VulkanCodeException{
		createPipeline();
	}
	
	
	private void createPipeline() throws VulkanCodeException{
		
		var desc = new Descriptor.LayoutDescription()
			           .bind(0, VkShaderStageFlag.VERTEX, tableGpu.buffer, VkDescriptorType.STORAGE_BUFFER)
			           .bind(1, VkShaderStageFlag.FRAGMENT, atlas.join(), VkImageLayout.SHADER_READ_ONLY_OPTIMAL);
		
		dsLayoutConst = core.globalDescriptorPool.createDescriptorSetLayout(desc.bindings());
		dsSetConst = dsLayoutConst.createDescriptorSet();
		dsSetConst.update(desc.bindData(), -1);
		
		
		
		dsLayout = core.globalDescriptorPool.createDescriptorSetLayout(List.of(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER),
			new Descriptor.LayoutBinding(1, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER)
		));
		
		var table = tableRes.join();
		
		pipeline = VkPipeline.builder(core.renderPass, shader)
		                     .blending(VkPipeline.Blending.STANDARD)
		                     .multisampling(core.physicalDevice.samples, true)
		                     .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
		                     .addDesriptorSetLayout(dsLayoutConst)
		                     .addDesriptorSetLayout(dsLayout)
		                     .specializationValue(VkShaderStageFlag.FRAGMENT, 0, (float)table.distanceRange)
		                     .specializationValue(VkShaderStageFlag.FRAGMENT, 1, (float)table.size)
		                     .addPushConstantRange(VkShaderStageFlag.VERTEX, 0, Float.BYTES*3*2)
		                     .build();
	}
	
	public record StringDraw(float pixelHeight, Color color, String string, float x, float y, float xScale, float outline){
		public StringDraw(float pixelHeight, Color color, String string, float x, float y){
			this(pixelHeight, color, string, x, y, 1, 0);
		}
	}
	
	private static final int outlineCutoff = 5;
	
	private static float mapToRange(float actual, float start, float end){
		return Math.max(0, Math.min(1, (actual - start)/(end - start)));
	}
	
	public static final class RenderResource implements VulkanResource{
		private VkDescriptorSet        dsSets;
		private UniformBuffer<Uniform> uniform;
		private BackedVkBuffer         verts;
		
		private IndirectDrawBuffer indirectInstances;
		
		@Override
		public void destroy() throws VulkanCodeException{
			if(dsSets != null) dsSets.destroy();
			if(uniform != null) uniform.destroy();
			if(verts != null) verts.destroy();
			if(indirectInstances != null) indirectInstances.destroy();
		}
	}
	public void render(Extent2D viewSize, CommandBuffer buf, RenderResource resource, List<StringDraw> strs) throws VulkanCodeException{
		if(strs.isEmpty()) return;
		waitFullyCreated();
		
		var table = tableRes.join();
		
		ensureRequiredMemory(table, resource, strs);
		
		buf.bindPipeline(pipeline);
		buf.setViewportScissor(new Rect2D(viewSize));
		
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, 0, dsSetConst, resource.dsSets);
		
		var projectionMatrix2D = new Matrix3x2f()
			                         .translate(-1, -1)
			                         .scale(2F/viewSize.width, 2F/viewSize.height);
		buf.pushConstants(pipeline.layout, VkShaderStageFlag.VERTEX, 0, projectionMatrix2D.get(new float[6]));
		
		int indirectInstanceCount = 0;
		
		try(var vertMemWrap = resource.verts.update();
		    var uniformMemWrap = resource.uniform.updateMulti(0);
		    var drawCmdWrap = resource.indirectInstances.update()
		){
			var vertMem  = vertMemWrap.getBuffer();
			var uniforms = uniformMemWrap.val;
			var drawCmd  = drawCmdWrap.buffer;
			
			
			record InstancePos(int vertPos, int vertCount){ }
			Map<String, InstancePos> instanceMap = HashMap.newHashMap(strs.size());
			int                      vertCounter = 0;
			int                      instance    = 0;
			
			VkDrawIndirectCommand drawCall = null;
			
			for(StringDraw str : strs){
				if(str.outline>0 && str.pixelHeight<outlineCutoff) continue;
				
				InstancePos instPos = instanceMap.get(str.string);
				if(instPos == null){
					var start = vertCounter;
					var count = putStr(vertMem, table, str.string);
					instanceMap.put(str.string, instPos = new InstancePos(start, count));
					vertCounter += count;
				}
				
				var outline = str.outline;
				if(str.outline>0){
					var aMul = mapToRange(str.pixelHeight, outlineCutoff, 50);
					outline *= (float)Math.pow(aMul, 0.7F);
				}
				
				uniforms.get().set(str.x, str.y, str.pixelHeight, str.color, outline, str.xScale);
				
				if(drawCall == null){
					indirectInstanceCount++;
					drawCall = drawCmd.get();
					drawCall.set(instPos.vertCount, 1, instPos.vertPos, 0);
				}else if(drawCall.firstVertex() == instPos.vertPos){
					drawCall.instanceCount(drawCall.instanceCount() + 1);
				}else{
					indirectInstanceCount++;
					drawCall = drawCmd.get();
					drawCall.set(instPos.vertCount, 1, instPos.vertPos, instance);
				}
				instance++;
			}
		}
		
		buf.drawIndirect(resource.indirectInstances, 0, indirectInstanceCount);
	}
	private void ensureRequiredMemory(Glyphs.Table table, RenderResource resource, List<StringDraw> strs) throws VulkanCodeException{
		boolean update = false;
		if(resource.dsSets == null){
			resource.dsSets = dsLayout.createDescriptorSet();
			resource.verts = core.allocateHostBuffer(Quad.SIZE, VkBufferUsageFlag.STORAGE_BUFFER);
			resource.uniform = core.allocateUniformBuffer(Uniform.SIZEOF, true, Uniform::new);
			resource.indirectInstances = core.allocateIndirectBuffer(4);
			update = true;
		}
		
		int instanceCount = 0;
		int vertCount     = 0;
		
		
		Set<String> uniqueStrings = HashSet.newHashSet(strs.size());
		int         drawCallCount = 0;
		String      lastStr       = null;
		for(StringDraw str : strs){
			if(str.outline>0 && str.pixelHeight<outlineCutoff) continue;
			var s = str.string;
			if(uniqueStrings.add(s)){
				for(int i = 0, l = s.length(); i<l; i++){
					var id = table.index.getOrDefault(s.charAt(i), -1);
					if(id != -1 && !table.empty.get(id)){
						vertCount++;
					}
				}
			}
			instanceCount++;
			if(!str.string.equals(lastStr)){
				lastStr = str.string;
				drawCallCount++;
			}
		}
		
		var neededVertMem     = vertCount*(long)Quad.SIZE;
		var neededInstanceMem = instanceCount*(long)Uniform.SIZEOF;
		
		if(resource.uniform.size()<neededInstanceMem){
			core.device.waitIdle();
			resource.uniform.destroy();
			resource.uniform = core.allocateUniformBuffer(Math.toIntExact(neededInstanceMem), true, Uniform::new);
			update = true;
		}
		if(resource.verts.size()<neededVertMem){
			core.device.waitIdle();
			resource.verts.destroy();
			resource.verts = core.allocateHostBuffer(neededVertMem, VkBufferUsageFlag.STORAGE_BUFFER);
			update = true;
		}
		if(update){
			resource.dsSets.update(List.of(
				new Descriptor.LayoutDescription.UniformBuff(0, resource.uniform),
				new Descriptor.LayoutDescription.TypeBuff(1, VkDescriptorType.STORAGE_BUFFER, resource.verts.buffer)
			), 0);
		}
		
		if(resource.indirectInstances.instanceCapacity()<drawCallCount){
			core.device.waitIdle();
			resource.indirectInstances.destroy();
			resource.indirectInstances = core.allocateIndirectBuffer(drawCallCount);
		}
	}
	
	private static int putStr(ByteBuffer b, Glyphs.Table table, String str){
		float fsScale = table.fsScale;
		
		float x     = 0, y = 0;// (float)metrics.descender;
		int   count = 0;
		var   len   = str.length();
		for(int i = 0; i<len; i++){
			var ch     = str.charAt(i);
			var charID = table.index.getOrDefault(ch, table.missingCharId);
			
			if(!table.empty.get(charID)){
				Quad.put(b, (table.x0[charID] + x)*fsScale, ((-table.y0[charID]) + y)*fsScale, charID);
				count += 6;
			}
			
			x += table.advance[charID];
		}
		return count;
	}
	
	private void waitFullyCreated(){
		doneTask.join();
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		pipeline.destroy();
		
		dsLayout.destroy();
		
		dsSetConst.destroy();
		dsLayoutConst.destroy();
		
		tableGpu.destroy();
		
		shader.destroy();
		atlas.join().destroy();
	}
}
