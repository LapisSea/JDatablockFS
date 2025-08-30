package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.carrotsearch.hppc.CharObjectHashMap;
import com.carrotsearch.hppc.CharObjectMap;
import com.lapissea.dfs.tools.DrawFont;
import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VkPipelineSet;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.IndirectDrawBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
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
import org.lwjgl.system.StructBuffer;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class MsdfFontRender implements Renderer<MsdfFontRender.RenderResource, MsdfFontRender.RenderToken>{
	
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
	
	private static class Quad extends Struct<Quad>{
		private static final int SIZEOF;
		private static final int X_OFF;
		private static final int Y_OFF;
		private static final int LETTER_ID;
		
		static{
			var layout = __struct(
				__member(Float.BYTES),
				__member(Float.BYTES),
				__member(Integer.BYTES)
			);
			SIZEOF = layout.getSize();
			X_OFF = layout.offsetof(0);
			Y_OFF = layout.offsetof(1);
			LETTER_ID = layout.offsetof(2);
		}
		
		void set(float xOff, float yOff, int letterId){
			var address = this.address;
			MemoryUtil.memPutFloat(address + X_OFF, xOff);
			MemoryUtil.memPutFloat(address + Y_OFF, yOff);
			MemoryUtil.memPutInt(address + LETTER_ID, letterId);
		}
		
		int letterId(){
			return MemoryUtil.memGetInt(address + LETTER_ID);
		}
		
		protected Quad(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), buff); }
		protected Quad(long address)   { super(address, null); }
		@Override
		protected Quad create(long address, ByteBuffer container){ return new Quad(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
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
	
	private final VulkanCore core;
	
	private Glyphs.Table  table;
	private VulkanTexture atlasTexture;
	
	private final CompletableFuture<Void> doneTask;
	
	private final ShaderModuleSet shader;
	private       BackedVkBuffer  tableGpu;
	
	private VkDescriptorSetLayout dsLayout;
	
	private VkDescriptorSetLayout dsLayoutConst;
	private VkDescriptorSet       dsSetConst;
	
	private VkPipelineSet pipelines;
	
	public MsdfFontRender(VulkanCore core){
		this.core = Objects.requireNonNull(core);
		shader = new ShaderModuleSet(core, "msdfFont", ShaderType.VERTEX, ShaderType.FRAGMENT);
		
		var tableTask = CompletableFuture.supplyAsync(() -> Glyphs.loadTable(LAYOUT_PATH)).whenComplete((table, e) -> {
			if(e != null) throw UtilL.uncheckedThrow(e);
			this.table = table;
		});
		var atlasTask = VulkanTexture.loadTexture(TEXTURE_PATH, false, () -> core).whenComplete((texture, e) -> {
			if(e != null) throw UtilL.uncheckedThrow(e);
			atlasTexture = texture;
		});
		
		var tableUploadTask = tableTask.whenComplete((table, e) -> {
			if(e != null) throw UtilL.uncheckedThrow(e);
			this.table = table;
			try{
				uploadTable();
			}catch(Throwable ex){
				throw new RuntimeException("Failed to create font table", ex);
			}
		});
		
		doneTask = CompletableFuture.allOf(atlasTask, tableUploadTask).whenComplete((v, e) -> {
			if(e != null) throw UtilL.uncheckedThrow(e);
			try{
				createPipeline();
			}catch(Throwable ex){
				throw new RuntimeException(ex);
			}
		});
	}
	
	private void uploadTable() throws VulkanCodeException{
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
	
	private void createPipeline() throws VulkanCodeException{
		
		var desc = new Descriptor.LayoutDescription()
			           .bind(0, VkShaderStageFlag.VERTEX, tableGpu.buffer, VkDescriptorType.STORAGE_BUFFER)
			           .bind(1, VkShaderStageFlag.FRAGMENT, atlasTexture, VkImageLayout.SHADER_READ_ONLY_OPTIMAL);
		
		dsLayoutConst = core.globalDescriptorPool.createDescriptorSetLayout(desc.bindings());
		dsSetConst = dsLayoutConst.createDescriptorSet();
		dsSetConst.update(desc.bindData(), -1);
		
		
		
		dsLayout = core.globalDescriptorPool.createDescriptorSetLayout(List.of(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER),
			new Descriptor.LayoutBinding(1, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER)
		));
		
		var table = this.table;
		
		pipelines = new VkPipelineSet(rp -> {
			return VkPipeline.builder(rp, shader)
			                 .blending(VkPipeline.Blending.STANDARD)
			                 .multisampling(rp.samples, true)
			                 .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
			                 .addDesriptorSetLayout(dsLayoutConst)
			                 .addDesriptorSetLayout(dsLayout)
			                 .specializationValue(VkShaderStageFlag.FRAGMENT, 0, (float)table.distanceRange)
			                 .specializationValue(VkShaderStageFlag.FRAGMENT, 1, (float)table.size)
			                 .specializationValue(VkShaderStageFlag.FRAGMENT, 2, rp.samples == VkSampleCountFlag.N1)
			                 .addPushConstantRange(VkShaderStageFlag.VERTEX, 0, Float.BYTES*3*2)
			                 .build();
		});
	}
	
	public record StringDraw(float pixelHeight, Color color, String string, float x, float y, float xScale, float outline){
		public StringDraw(float pixelHeight, Color color, String string, float x, float y){
			this(pixelHeight, color, string, x, y, 1, 0);
		}
		public StringDraw withOutline(Color color, float outline){
			return new StringDraw(pixelHeight, color, string, x, y, xScale, outline);
		}
	}
	
	public DrawFont.Bounds getStringBounds(String string, float fontScale){
		var   table = this.table;
		float width = calcUnitWidth(table, string);
		return new DrawFont.Bounds(width*fontScale, fontScale);
	}
	
	private float calcUnitWidth(Glyphs.Table table, String str){
		float width = 0;
		for(int i = 0, len = str.length(); i<len; i++){
			var ch     = str.charAt(i);
			var charID = table.index.getOrDefault(ch, table.missingCharId);
			width += table.advance[charID];
		}
		return width*table.fsScale;
	}
	
	public boolean canDisplay(char c){
		var i = table.index.getOrDefault(c, -1);
		return i != -1 && !table.empty.get(i);
	}
	
	private static final int outlineCutoff = 5;
	
	private static float mapToRange(float actual, float start, float end){
		return Math.max(0, Math.min(1, (actual - start)/(end - start)));
	}
	
	public static final class RenderResource implements Renderer.ResourceBuffer{
		private VkDescriptorSet dsSet;
		
		private BackedVkBuffer.Typed<Uniform> uniform;
		private BackedVkBuffer.Typed<Quad>    quads;
		private IndirectDrawBuffer            indirectInstances;
		
		private int drawStart, uniformPos, quadPos;
		
		private void ensureMemory(DeviceGC deviceGC, VkDescriptorSetLayout dsLayout, int quadCount, int uniformCount, int indirectInstanceCount) throws VulkanCodeException{
			var device = dsLayout.device;
			
			boolean update = dsSet == null;
			if(quads == null || quads.elementCount()<quadCount){
				var newBuff = device.allocateHostBuffer(quadCount*(long)Quad.SIZEOF, VkBufferUsageFlag.STORAGE_BUFFER).asTyped(Quad::new);
				VUtils.copyDestroy(deviceGC, quads, newBuff);
				quads = newBuff;
				update = true;
			}
			if(uniform == null || uniform.elementCount()<uniformCount){
				var newBuff = device.allocateHostBuffer(uniformCount*(long)Uniform.SIZEOF, VkBufferUsageFlag.STORAGE_BUFFER).asTyped(Uniform::new);
				VUtils.copyDestroy(deviceGC, uniform, newBuff);
				uniform = newBuff;
				update = true;
			}
			if(indirectInstances == null || indirectInstances.instanceCapacity()<indirectInstanceCount){
				var newIBuff = device.allocateIndirectBuffer(indirectInstanceCount);
				if(indirectInstances != null){
					indirectInstances.buffer.transferTo(newIBuff.buffer);
					deviceGC.destroyLater(indirectInstances);
				}
				indirectInstances = newIBuff;
			}
			
			if(update){
				if(dsSet != null) deviceGC.destroyLater(dsSet);
				dsSet = dsLayout.createDescriptorSet();
				dsSet.update(List.of(
					new Descriptor.LayoutDescription.TypeBuff(0, VkDescriptorType.STORAGE_BUFFER, uniform.buffer),
					new Descriptor.LayoutDescription.TypeBuff(1, VkDescriptorType.STORAGE_BUFFER, quads.buffer)
				));
			}
		}
		
		public void reset(){
			drawStart = uniformPos = quadPos = 0;
		}
		
		@Override
		public void destroy() throws VulkanCodeException{
			if(dsSet != null) dsSet.destroy();
			dsSet = null;
			if(uniform != null) uniform.destroy();
			uniform = null;
			if(quads != null) quads.destroy();
			quads = null;
			if(indirectInstances != null) indirectInstances.destroy();
			indirectInstances = null;
		}
	}
	
	public static final class RenderToken implements Renderer.RenderToken{
		
		private final RenderResource resource;
		public final  int            drawOffset;
		public final  int            drawCount;
		
		public RenderToken(RenderResource resource, int drawOffset, int drawCount){
			this.resource = Objects.requireNonNull(resource);
			this.drawOffset = drawOffset;
			this.drawCount = drawCount;
		}
		
		@Override
		public String toString(){
			return "{off: " + drawOffset + ", count: " + drawCount + "}";
		}
	}
	
	public RenderToken record(DeviceGC deviceGC, RenderResource resource, List<StringDraw> strs) throws VulkanCodeException{
		if(strs.isEmpty()) return null;
		waitFullyCreated();
		
		ensureRequiredMemory(deviceGC, resource, strs);
		
		try(var quadMemWrap = resource.quads.updateMulti();
		    var uniformMemWrap = resource.uniform.updateMulti();
		    var drawCmdWrap = resource.indirectInstances.update()
		){
			var quadMem  = quadMemWrap.val.position(resource.quadPos);
			var uniforms = uniformMemWrap.val.position(resource.uniformPos);
			var drawCmd  = drawCmdWrap.buffer.position(resource.drawStart);
			
			record InstancePos(int quadPos, int quadCount){
				int vertPos()  { return quadPos*6; }
				int vertCount(){ return quadCount*6; }
			}
			CharObjectMap<InstancePos> charInstanceMap = new CharObjectHashMap<>(128);
			var instanceMap = new HashMap<String, InstancePos>((int)Math.ceil(strs.size()/0.75)){
				@Override
				public InstancePos put(String key, InstancePos value){
					if(key.length() == 1){
						var c = key.charAt(0);
						return charInstanceMap.put(c, value);
					}
					return super.put(key, value);
				}
				public InstancePos get(String key){
					if(key.length() == 1){
						var c = key.charAt(0);
						return charInstanceMap.get(c);
					}
					return super.get(key);
				}
				@Override
				public InstancePos get(Object key){
					return get((String)key);
				}
			};
			int quadCounter = resource.quadPos;
			int instance    = resource.uniformPos;
			
			VkDrawIndirectCommand drawCall = null;
			
			for(StringDraw str : strs){
				if(str.outline>0 && str.pixelHeight<outlineCutoff) continue;
				
				InstancePos instPos = instanceMap.get(str.string);
				if(instPos == null){
					var start = quadCounter;
					var count = putStr(quadMem, table, str.string);
					instanceMap.put(str.string, instPos = new InstancePos(start, count));
					quadCounter += count;
				}
				
				var outline = str.outline;
				if(str.outline>0){
					var aMul = mapToRange(str.pixelHeight, outlineCutoff, 50);
					outline *= (float)Math.pow(aMul, 0.7F);
				}
				
				uniforms.get().set(str.x, str.y, str.pixelHeight, str.color, outline, str.xScale);
				
				if(drawCall == null){
					drawCall = drawCmd.get();
					drawCall.set(instPos.vertCount(), 1, instPos.vertPos(), instance);
				}else if(drawCall.firstVertex() == instPos.vertPos()){
					drawCall.instanceCount(drawCall.instanceCount() + 1);
				}else{
					drawCall = drawCmd.get();
					drawCall.set(instPos.vertCount(), 1, instPos.vertPos(), instance);
				}
				instance++;
			}
			
			var drawOffset = resource.drawStart;
			var drawCount  = drawCmd.position() - resource.drawStart;
			
			resource.quadPos = quadMem.position();
			resource.uniformPos = uniforms.position();
			resource.drawStart = drawCmd.position();
			
			return new RenderToken(resource, drawOffset, drawCount);
		}
	}
	
	public void submit(Extent2D viewSize, CommandBuffer buf, List<RenderToken> tokens) throws VulkanCodeException{
		if(tokens.isEmpty()) return;
		waitFullyCreated();
		
		
		var pipeline = pipelines.get(buf.getCurrentRenderPass());
		buf.bindPipeline(pipeline);
		buf.setViewportScissor(new Rect2D(viewSize));
		
		var projectionMatrix2D = new Matrix3x2f()
			                         .translate(-1, -1)
			                         .scale(2F/viewSize.width, 2F/viewSize.height);
		buf.pushConstants(pipeline.layout, VkShaderStageFlag.VERTEX, 0, projectionMatrix2D.get(new float[6]));
		
		RenderResource lastResource = null;
		for(var token : tokens){
			var resource = token.resource;
			if(resource != lastResource){
				lastResource = resource;
				buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, 0, dsSetConst, resource.dsSet);
			}
			
			buf.drawIndirect(resource.indirectInstances, token.drawOffset, token.drawCount);
		}
	}
	
	private void ensureRequiredMemory(DeviceGC deviceGC, RenderResource resource, List<StringDraw> strs) throws VulkanCodeException{
		int instanceCount = resource.uniformPos;
		int quadCount     = resource.quadPos;
		
		BitSet uniqueChars = new BitSet(255);
		var uniqueStrings = new HashSet<String>((int)Math.ceil(strs.size()/0.75)){
			@Override
			public boolean add(String s){
				if(s.length() == 1){
					var c     = s.charAt(0);
					var empty = !uniqueChars.get(c);
					if(empty) uniqueChars.set(c);
					return empty;
				}
				return super.add(s);
			}
		};
		int    drawCallCount = resource.drawStart;
		String lastStr       = null;
		for(StringDraw str : strs){
			if(str.outline>0 && str.pixelHeight<outlineCutoff) continue;
			var s = str.string;
			if(uniqueStrings.add(s)){
				for(int i = 0, l = s.length(); i<l; i++){
					var id = table.index.getOrDefault(s.charAt(i), -1);
					if(id != -1 && !table.empty.get(id)){
						quadCount++;
					}
				}
			}
			instanceCount++;
			if(!str.string.equals(lastStr)){
				lastStr = str.string;
				drawCallCount++;
			}
		}
		
		resource.ensureMemory(deviceGC, dsLayout, quadCount, instanceCount, drawCallCount);
	}
	
	private static int putStr(StructBuffer<Quad, ?> b, Glyphs.Table table, String str){
		float fsScale = table.fsScale;
		
		float x     = 0, y = (float)table.metrics.descender();
		int   count = 0;
		var   len   = str.length();
		for(int i = 0; i<len; i++){
			var ch     = str.charAt(i);
			var charID = table.index.getOrDefault(ch, table.missingCharId);
			
			if(!table.empty.get(charID)){
				b.get().set((table.x0[charID] + x)*fsScale, ((-table.y0[charID]) + y)*fsScale, charID);
				count++;
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
		waitFullyCreated();
		
		pipelines.destroy();
		
		dsLayout.destroy();
		
		dsSetConst.destroy();
		dsLayoutConst.destroy();
		
		tableGpu.destroy();
		
		shader.destroy();
		atlasTexture.destroy();
	}
}
