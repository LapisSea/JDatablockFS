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
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;
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
	
	private static class Uniform{
		private static final int SIZE = (2 + 1 + 2)*4 + 4;
		
		private static void put(ByteBuffer dest, float posX, float posY, float scale, Color color, float outline, float xScale){
			dest.putFloat(posX).putFloat(posY).putFloat(scale);
			dest.put((byte)color.getRed())
			    .put((byte)color.getGreen())
			    .put((byte)color.getBlue())
			    .put((byte)color.getAlpha());
			dest.putFloat(outline).putFloat(xScale);
		}
	}
	
	private VulkanCore core;
	
	private final CompletableFuture<Glyphs.Table>  tableRes = CompletableFuture.supplyAsync(() -> Glyphs.loadTable(LAYOUT_PATH));
	private final CompletableFuture<VulkanTexture> atlas    = VulkanTexture.loadTexture(TEXTURE_PATH, false, () -> {
		UtilL.sleepWhile(() -> core == null);
		return core;
	});
	private       CompletableFuture<Void>          doneTask;
	
	private final ShaderModuleSet shader = new ShaderModuleSet("msdfFont", ShaderType.VERTEX, ShaderType.FRAGMENT);
	private       BackedVkBuffer  tableGpu;
	
	public void init(VulkanCore core){
		this.core = Objects.requireNonNull(core);
		shader.init(core);
		
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
		tableGpu = core.allocateLocalStorageBuffer(Letter.SIZE*(long)count, b -> {
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
	
	private UniformBuffer               uniform;
	private BackedVkBuffer              verts;
	private IndirectDrawBuffer.PerFrame indirectInstances;
	
	private Descriptor.LayoutDescription description;
	private VkDescriptorSetLayout        dsLayout;
	private VkDescriptorSet.PerFrame     dsSets;
	
	private VkDescriptorSetLayout dsLayoutConst;
	private VkDescriptorSet       dsSetConst;
	
	private VkPipeline pipeline;
	
	private void createResources() throws VulkanCodeException{
		verts = core.allocateHostBuffer(Quad.SIZE, VkBufferUsageFlag.STORAGE_BUFFER);
		uniform = core.allocateUniformBuffer(Uniform.SIZE, true);
		indirectInstances = core.allocateIndirectBufferPerFrame(1);
		
		var frag  = Iters.from(shader).filter(e -> e.stage == VkShaderStageFlag.FRAGMENT).getFirst();
		var table = tableRes.join();
		frag.specializationValues.put(0, (float)table.distanceRange);
		frag.specializationValues.put(1, (float)table.size);
		
		createPipeline();
	}
	
	
	private void createPipeline() throws VulkanCodeException{
		
		var desc = new Descriptor.LayoutDescription()
			           .bind(0, VkShaderStageFlag.VERTEX, tableGpu.buffer, VkDescriptorType.STORAGE_BUFFER)
			           .bind(1, VkShaderStageFlag.FRAGMENT, atlas.join(), VkImageLayout.SHADER_READ_ONLY_OPTIMAL);
		
		dsLayoutConst = core.globalDescriptorPool.createDescriptorSetLayout(desc.bindings());
		dsSetConst = dsLayoutConst.createDescriptorSet();
		dsSetConst.update(desc.bindData(), -1);
		
		
		description =
			new Descriptor.LayoutDescription()
				.bind(0, VkShaderStageFlag.VERTEX, uniform)
				.bind(1, VkShaderStageFlag.VERTEX, verts.buffer, VkDescriptorType.STORAGE_BUFFER);
		
		dsLayout = core.globalDescriptorPool.createDescriptorSetLayout(description.bindings());
		dsSets = dsLayout.createDescriptorSetsPerFrame();
		
		dsSets.updateAll(description.bindData());
		
		pipeline = VkPipeline.builder(core.renderPass, shader)
		                     .blending(VkPipeline.Blending.STANDARD)
		                     .multisampling(core.physicalDevice.samples, true)
		                     .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
		                     .addDesriptorSetLayout(core.globalUniformLayout)
		                     .addDesriptorSetLayout(dsLayoutConst)
		                     .addDesriptorSetLayout(dsLayout)
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
	public void render(CommandBuffer buf, int frameID, List<StringDraw> strs) throws VulkanCodeException{
		if(strs.isEmpty()) return;
		waitFullyCreated();
		
		var table = tableRes.join();
		
		ensureRequiredMemory(table, strs);
		
		buf.bindPipeline(pipeline, true);
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, pipeline.layout, 0,
		                       core.globalUniformSets.get(frameID), dsSetConst, dsSets.get(frameID));
		buf.setViewportScissor(new Rect2D(core.swapchain.extent));
		
		
		int indirectInstanceCount = 0;
		
		try(var vertMemWrap = verts.update();
		    var uniformMemWrap = uniform.update(frameID);
		    var drawCmdWrap = indirectInstances.update(frameID)
		){
			var vertMem    = vertMemWrap.getBuffer();
			var uniformMem = uniformMemWrap.getBuffer();
			var drawCmd    = drawCmdWrap.buffer;
			
			
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
				
				Uniform.put(uniformMem, str.x, str.y, str.pixelHeight, str.color, outline, str.xScale);
				
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
		
		buf.drawIndirect(indirectInstances, frameID, 0, indirectInstanceCount);
	}
	private void ensureRequiredMemory(Glyphs.Table table, List<StringDraw> strs) throws VulkanCodeException{
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
		var neededInstanceMem = instanceCount*(long)Uniform.SIZE;
		
		if(verts.size()<neededVertMem){
			core.device.waitIdle();
			verts.destroy();
			verts = core.allocateHostBuffer(neededVertMem, VkBufferUsageFlag.STORAGE_BUFFER);
			description.bind(1, VkShaderStageFlag.VERTEX, verts.buffer, VkDescriptorType.STORAGE_BUFFER);
			dsSets.updateAll(description.bindData());
		}
		if(uniform.size()<neededInstanceMem){
			core.device.waitIdle();
			uniform.destroy();
			uniform = core.allocateUniformBuffer(Math.toIntExact(neededInstanceMem), true);
			description.bind(0, VkShaderStageFlag.VERTEX, uniform);
			dsSets.updateAll(description.bindData());
		}
		if(indirectInstances.instanceCapacity()<drawCallCount){
			core.device.waitIdle();
			indirectInstances.destroy();
			indirectInstances = core.allocateIndirectBufferPerFrame(drawCallCount);
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
		
		dsSets.destroy();
		dsLayout.destroy();
		
		dsSetConst.destroy();
		dsLayoutConst.destroy();
		
		uniform.destroy();
		verts.destroy();
		indirectInstances.destroy();
		tableGpu.destroy();
		
		shader.destroy();
		atlas.join().destroy();
	}
}
