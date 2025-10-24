package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.carrotsearch.hppc.IntIntHashMap;
import com.lapissea.dfs.tools.newlogger.IOFrame;
import com.lapissea.dfs.tools.newlogger.display.ColorU8;
import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VkPipelineSet;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.IndirectDrawBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.Std430;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.CustomBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;
import org.roaringbitmap.IntConsumer;
import org.roaringbitmap.RoaringBitmap;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class ByteGridRender implements Renderer<ByteGridRender.RenderResource, ByteGridRender.RenderToken>{
	
	private static final class Vert{
		static final int SIZE = (2)*4;
		
		private static void putQuad(ByteBuffer bb, int x, int y, int w, int h){
			put(bb, x/3F, y/3F);
			put(bb, x/3F, (y + h)/3F);
			put(bb, (x + w)/3F, (y + h)/3F);
			
			put(bb, x/3F, y/3F);
			put(bb, (x + w)/3F, (y + h)/3F);
			put(bb, (x + w)/3F, y/3F);
		}
		
		static void put(ByteBuffer buffer, float x, float y){
			buffer.putFloat(x).putFloat(y);
		}
	}
	
	private static class PushConstant extends Struct<PushConstant>{
		private static final int SIZEOF;
		private static final int MAT;
		private static final int TILE_WIDTH;
		
		static{
			var layout = __struct(
				Std430.__mat4(),
				Std430.__int()
			);
			
			SIZEOF = layout.getSize();
			MAT = layout.offsetof(0);
			TILE_WIDTH = layout.offsetof(1);
		}
		
		Matrix4f mat(){ return new Matrix4f().setFromAddress(address + MAT); }
		
		PushConstant mat(Matrix4f mat){
			mat.getToAddress(address + MAT);
			return this;
		}
		PushConstant tileWidth(int tileWidth){
			MemoryUtil.memPutInt(address + TILE_WIDTH, tileWidth);
			return this;
		}
		
		PushConstant set(Matrix4f mat, int tileWidth){
			mat(mat);
			tileWidth(tileWidth);
			return this;
		}
		
		protected PushConstant(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), buff); }
		protected PushConstant(long address)   { super(address, null); }
		@Override
		protected PushConstant create(long address, ByteBuffer container){ return new PushConstant(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
	}
	
	private static class GByte extends Struct<GByte>{
		private static final int SIZEOF;
		private static final int INDEX;
		private static final int VALUE;
		private static final int COLOR_INDEX;
		
		static{
			Layout layout = __struct(
				Std430.__uint16_t(),
				Std430.__uint8_t(),
				Std430.__uint8_t()
			);
			
			SIZEOF = layout.getSize();
			
			INDEX = layout.offsetof(0);
			COLOR_INDEX = layout.offsetof(1);
			VALUE = layout.offsetof(2);
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
			void put(char index, byte value, int byteIndex){
				if(byteIndex>255) throw new IllegalArgumentException("byteIndex can't be larger than 255");
				get().set(index, value, byteIndex);
			}
		}
		
		char index()    { return (char)MemoryUtil.memGetShort(address() + INDEX); }
		
		byte value()    { return MemoryUtil.memGetByte(address() + VALUE); }
		
		int colorIndex(){ return Byte.toUnsignedInt(MemoryUtil.memGetByte(address() + COLOR_INDEX)); }
		
		void set(char index, byte value, int byteIndex){
			var address = address();
			MemoryUtil.memPutShort(address + INDEX, (short)index);
			MemoryUtil.memPutByte(address + COLOR_INDEX, (byte)byteIndex);
			MemoryUtil.memPutByte(address + VALUE, value);
		}
		
		protected GByte(long address){ super(address, null); }
		@Override
		protected GByte create(long address, ByteBuffer container){ return new GByte(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
		
		@Override
		public String toString(){
			return "GByte{index=" + (int)index() + ", value=0x" + Integer.toHexString(Byte.toUnsignedInt(value())) + ",  colorIndex=" + colorIndex() + "}";
		}
	}
	
	private record MeshInfo(int vertPos, int vertCount){ }
	
	private record IndexedColor(int rgba, int index){ }
	
	public static final class RenderResource implements ResourceBuffer{
		
		private VkDescriptorSet dsSet;
		private BackedVkBuffer  bytesInfo;
		private BackedVkBuffer  colors;
		
		private IndirectDrawBuffer indirectDrawBuff;
		
		private final IntIntHashMap colorIndex = new IntIntHashMap();
		
		private int instanceCount;
		private int byteCount;
		
		public void reset(){
			instanceCount = 0;
			byteCount = 0;
			colorIndex.clear();
		}
		
		private int colorToIndex(Color color, List<IndexedColor> toUpdate){
			var ic    = VUtils.toRGBAi4(color);
			var map   = colorIndex;
			var index = map.indexOf(ic);
			if(index>=0) return map.indexGet(index);
			var i = map.size();
			map.put(ic, i);
			toUpdate.add(new IndexedColor(ic, i));
			return i;
		}
		
		private void updateDescriptor(){
			dsSet.update(List.of(
				new Descriptor.LayoutDescription.TypeBuff(0, VkDescriptorType.STORAGE_BUFFER, bytesInfo.buffer),
				new Descriptor.LayoutDescription.TypeBuff(1, VkDescriptorType.STORAGE_BUFFER, colors.buffer)
			), -1);
		}
		@Override
		public void destroy() throws VulkanCodeException{
			if(bytesInfo != null){
				dsSet.destroy();
				dsSet = null;
				bytesInfo.destroy();
				bytesInfo = null;
				indirectDrawBuff.destroy();
				indirectDrawBuff = null;
				colors.destroy();
				colors = null;
			}
		}
	}
	
	public static final class RenderToken implements Renderer.RenderToken{
		
		private final RenderResource resource;
		
		private final int firstInstance;
		private final int instanceCount;
		
		public RenderToken(RenderResource resource, int firstInstance, int instanceCount){
			this.resource = resource;
			this.firstInstance = firstInstance;
			this.instanceCount = instanceCount;
		}
	}
	
	private final ShaderModuleSet shader;
	
	private final VulkanCore     core;
	private final MeshInfo[]     meshInfos;
	private final BackedVkBuffer verts;
	
	private final VkDescriptorSetLayout dsLayoutConst;
	private final VkDescriptorSet       dsSetConst;
	
	private final VkDescriptorSetLayout dsLayout;
	private final VkPipelineSet         pipelines;
	private final VkPipelineSet         pipelinesSimple;
	
	public ByteGridRender(VulkanCore core) throws VulkanCodeException{
		this.core = core;
		shader = new ShaderModuleSet(core, "ByteGrid", ShaderType.VERTEX, ShaderType.FRAGMENT);
		
		var vertsI = computeByteVerts(core);
		meshInfos = vertsI.infos;
		verts = vertsI.verts;
		
		dsLayoutConst = core.globalDescriptorPool.createDescriptorSetLayout(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER)
		);
		dsSetConst = dsLayoutConst.createDescriptorSet();
		
		dsSetConst.update(List.of(
			new Descriptor.LayoutDescription.TypeBuff(0, VkDescriptorType.STORAGE_BUFFER, verts.buffer)
		), -1);
		
		
		dsLayout = core.globalDescriptorPool.createDescriptorSetLayout(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER),
			new Descriptor.LayoutBinding(1, VkShaderStageFlag.VERTEX, VkDescriptorType.STORAGE_BUFFER)
		);
		pipelines = new VkPipelineSet(rp -> basePipeline(rp).build());
		pipelinesSimple = new VkPipelineSet(rp -> {
			return basePipeline(rp)
				       .specializationValue(VkShaderStageFlag.VERTEX, 0, true)
				       .build();
		});
	}
	private VkPipeline.Builder basePipeline(RenderPass rp){
		return VkPipeline.builder(rp, shader)
		                 .blending(VkPipeline.Blending.STANDARD)
		                 .multisampling(rp.samples, false)
		                 .dynamicState(VkDynamicState.VIEWPORT, VkDynamicState.SCISSOR)
		                 .addDesriptorSetLayout(dsLayoutConst)
		                 .addDesriptorSetLayout(dsLayout)
		                 .addPushConstantRange(VkShaderStageFlag.VERTEX, 0, PushConstant.SIZEOF);
	}
	
	private record ByteVerts(MeshInfo[] infos, BackedVkBuffer verts){ }
	private ByteVerts computeByteVerts(VulkanCore core) throws VulkanCodeException{
		record SetBitsQuad(int x, int y, int w, int h){ }
		
		List<List<SetBitsQuad>> setQuadsSet = new ArrayList<>(257);
		for(int val = 0; val<257; val++){
			List<SetBitsQuad> quads = new ArrayList<>(8);
			setQuadsSet.add(quads);
			for(int gridIndex = 0; gridIndex<9; gridIndex++){
				if(UtilL.checkFlag(val, 1<<gridIndex)){
					quads.add(new SetBitsQuad(gridIndex%3, gridIndex/3, 1, 1));
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
		
		var meshInfos  = new MeshInfo[257];
		int vertsTotal = 0;
		for(int i = 0; i<meshInfos.length; i++){
			var quads = setQuadsSet.get(i);
			
			int verts   = Math.max(quads.size(), 1)*6;
			int vertPos = vertsTotal;
			vertsTotal += verts;
			meshInfos[i] = new MeshInfo(vertPos, verts);
		}
		
		var verts = core.allocateDeviceLocalBuffer((long)vertsTotal*Vert.SIZE, bb -> {
			for(List<SetBitsQuad> quads : setQuadsSet){
				if(quads.isEmpty()){
					Vert.putQuad(bb, 0, 0, 0, 0);
				}else{
					for(var quad : quads){
						Vert.putQuad(bb, quad.x, quad.y, quad.w, quad.h);
					}
				}
			}
		});
		return new ByteVerts(meshInfos, verts);
	}
	
	public record IOEvent(char from, char to, Type type){
		public IOEvent(IOFrame.Range range, Type type){
			this(toCharExact((int)range.start, "from"), toCharExact((int)(range.start + range.size), "to"), type);
		}
		public IOEvent(int from, int to, Type type){
			this(toCharExact(from, "from"), toCharExact(to, "to"), type);
		}
		public IOEvent{
			if(from>to){
				throw new IllegalArgumentException("From can not be larger than to");
			}
			Objects.requireNonNull(type);
		}
		public enum Type{
			NONE(0), READ(1), WRITE(2);
			final byte flag;
			Type(int flag){ this.flag = (byte)flag; }
		}
	}
	
	public record DrawRange(char from, char to, Color color){
		public DrawRange(int from, int to, Color color){
			this(toCharExact(from, "from"), toCharExact(to, "to"), color);
		}
		public DrawRange{
			if(from>to){
				throw new IllegalArgumentException("From can not be larger than to");
			}
			Objects.requireNonNull(color);
		}
	}
	
	private static char toCharExact(int value, String name){
		if(value<0) throw new IllegalArgumentException(name + " can not be negative");
		if(value>Character.MAX_VALUE)
			throw new IllegalArgumentException(name + " can not be greater than unsigned 16 bit integer (" + Character.MAX_VALUE + ")");
		return (char)value;
	}
	
	private interface EventType{
		boolean hasRead(char off);
		boolean hasWrite(char off);
		
		static EventType of(Iterable<IOEvent> ioEvents){
			var types = Iters.from(ioEvents).map(IOEvent::type).toModSet();
			if(types.size()>1){
				var reads  = new RoaringBitmap();
				var writes = new RoaringBitmap();
				for(var e : ioEvents){
					for(var i = e.from; i<e.to; i++){
						switch(e.type){
							case READ -> reads.add(i);
							case WRITE -> writes.add(i);
						}
					}
				}
				return new EventType(){
					@Override
					public boolean hasRead(char off){ return reads.contains(off); }
					@Override
					public boolean hasWrite(char off){ return writes.contains(off); }
				};
			}else{
				var read  = types.contains(IOEvent.Type.READ);
				var write = types.contains(IOEvent.Type.WRITE);
				return new EventType(){
					@Override
					public boolean hasRead(char off){ return read; }
					@Override
					public boolean hasWrite(char off){ return write; }
				};
			}
		}
		
	}
	
	public RenderToken record(DeviceGC deviceGC, RenderResource resource, List<PrimitiveBuffer.ByteToken> tokens) throws VulkanCodeException{
		
		GByte.Buf[]        byteBuffers = new GByte.Buf[257];
		List<IndexedColor> toUpdate    = new ArrayList<>();
		
		for(PrimitiveBuffer.ByteToken token : tokens){
			long   dataOffset = token.dataOffset();
			byte[] data       = token.data();
			for(DrawRange range : token.ranges()){
				var colorIndex = resource.colorToIndex(range.color, toUpdate);
				for(char i = range.from; i<range.to; i++){
					var dataI = (int)(i - dataOffset);
					var b     = Byte.toUnsignedInt(data[dataI]);
					if(b == 0) continue;
					
					var buf = byteBuffers[b];
					if(buf == null) buf = byteBuffers[b] = new GByte.Buf(MemoryUtil.memAlloc(GByte.SIZEOF*8));
					else if(!buf.hasRemaining()){
						var nb = new GByte.Buf(MemoryUtil.memAlloc(GByte.SIZEOF*buf.capacity()*2));
						nb.put(buf.flip());
						buf.free();
						buf = byteBuffers[b] = nb;
					}
					buf.put(i, data[dataI], colorIndex);
				}
			}
		}
		
		var ioEvents = Iters.from(tokens).flatMap(PrimitiveBuffer.ByteToken::ioEvents).toModList();
		if(ioEvents.iterator().hasNext()){
			var allEvents = new RoaringBitmap();
			int count     = 0;
			for(var e : ioEvents){
				for(var i = e.from; i<e.to; i++){
					allEvents.add(i);
					count++;
				}
			}
			var ioBuff    = byteBuffers[256] = new GByte.Buf(MemoryUtil.memAlloc(GByte.SIZEOF*count));
			var eventType = EventType.of(ioEvents);
			
			int[] colorIds = new int[4];
			Arrays.fill(colorIds, -1);
			
			allEvents.forEach((IntConsumer)i -> {
				var offset  = (char)i;
				var read    = eventType.hasRead(offset);
				var write   = eventType.hasWrite(offset);
				var pos     = (read? 0b01 : 0)|(write? 0b10 : 0);
				var colorID = colorIds[pos];
				if(colorID == -1) colorID = colorIds[pos] = resource.colorToIndex(new Color(write? 255 : 0, 255, read? 255 : 0), toUpdate);
				ioBuff.put(offset, (byte)0, colorID);
			});
		}
		
		var device = core.device;
		
		var nonNullBuffers = Iters.from(byteBuffers).nonNulls();
		nonNullBuffers.forEach(CustomBuffer::flip);
		
		var neededCommandCapacity = resource.instanceCount + nonNullBuffers.count();
		if(resource.indirectDrawBuff == null || resource.indirectDrawBuff.instanceCapacity()<neededCommandCapacity){
			var newBuff = device.allocateIndirectBuffer(neededCommandCapacity);
			if(resource.indirectDrawBuff != null) VUtils.copyDestroy(deviceGC, resource.indirectDrawBuff.buffer, newBuff.buffer);
			resource.indirectDrawBuff = newBuff;
		}
		
		boolean updateSet = false;
		
		{
			int cap = resource.byteCount;
			for(var buff : nonNullBuffers){
				cap += buff.remaining();
			}
			var oldCap = BackedVkBuffer.size(resource.bytesInfo, GByte.SIZEOF);
			if(resource.bytesInfo == null || oldCap<cap){
				var newBuff = device.allocateHostBuffer(GByte.SIZEOF*Math.max(cap, (long)(oldCap*1.5)), VkBufferUsageFlag.STORAGE_BUFFER);
				VUtils.copyDestroy(deviceGC, resource.bytesInfo, newBuff);
				resource.bytesInfo = newBuff;
				updateSet = true;
			}
		}
		{
			var cap    = resource.colorIndex.size();
			var oldCap = BackedVkBuffer.size(resource.colors, ColorU8.SIZEOF);
			if(resource.colors == null || oldCap<cap){
				var newBuff = device.allocateHostBuffer(ColorU8.SIZEOF*Math.max(cap, (long)(oldCap*1.5)), VkBufferUsageFlag.STORAGE_BUFFER);
				VUtils.copyDestroy(deviceGC, resource.colors, newBuff);
				resource.colors = newBuff;
				updateSet = true;
			}
		}
		
		if(updateSet){
			deviceGC.destroyLater(resource.dsSet);
			resource.dsSet = dsLayout.createDescriptorSet();
			resource.updateDescriptor();
		}
		
		var start = resource.instanceCount;
		
		try(var draws = resource.indirectDrawBuff.update();
		    var ses = resource.bytesInfo.updateAs(GByte.Buf::new);
		    var colSes = resource.colors.updateAs(ColorU8.Buf::new)){
			var instancesBuff = ses.val;
			var colorBuff     = colSes.val;
			
			instancesBuff.position(resource.byteCount);
			draws.setPos(start);
			
			for(int i = 0; i<byteBuffers.length; i++){
				var buf = byteBuffers[i];
				if(buf == null) continue;
				
				var info = meshInfos[i];
				draws.draw(info.vertCount, buf.remaining(), info.vertPos, instancesBuff.position());
				instancesBuff.put(buf);
				buf.free();
			}
			
			for(IndexedColor ic : toUpdate){
				colorBuff.position(ic.index).put(ic.rgba);
			}
			
			resource.byteCount = instancesBuff.position();
			var end = resource.instanceCount = draws.buffer.position();
			
			return new RenderToken(resource, start, end - start);
		}
	}
	
	public void submit(Extent2D viewSize, CommandBuffer buf, Matrix4f transform, int tileWidth, List<RenderToken> tokens) throws VulkanCodeException{
		if(tokens.isEmpty()) return;
		
		if(Iters.from(tokens).map(e -> e.resource).distinct().count() != 1){
			throw new NotImplementedException("No multi resources");//TODO
		}
		
		var resource = tokens.getFirst().resource;
		var scale3   = new Vector3f();
		transform.getScale(scale3);
		var scale = scale3.x;
		
		var small = scale<5;
		
		var pipeline = (small? pipelinesSimple : pipelines).get(buf.getCurrentRenderPass());
		buf.bindPipeline(pipeline);
		buf.setViewportScissor(new Rect2D(viewSize));
		
		buf.bindDescriptorSets(VkPipelineBindPoint.GRAPHICS, 0, dsSetConst, resource.dsSet);
		
		var mat = new Matrix4f().translate(-1, -1, 0)
		                        .scale(2F/viewSize.width, 2F/viewSize.height, 1)
		                        .mul(transform);
		
		var data = ByteBuffer.allocateDirect(PushConstant.SIZEOF);
		new PushConstant(data).set(mat, tileWidth);
		buf.pushConstants(pipeline.layout, VkShaderStageFlag.VERTEX, 0, data);
		for(RenderToken token : tokens){
			buf.drawIndirect(resource.indirectDrawBuff, token.firstInstance, token.instanceCount);
		}
	}
	
	
	@Override
	public void destroy() throws VulkanCodeException{
		pipelines.destroy();
		pipelinesSimple.destroy();
		
		dsLayout.destroy();
		
		dsSetConst.destroy();
		dsLayoutConst.destroy();
		
		verts.destroy();
		
		shader.destroy();
	}
}
