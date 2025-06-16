package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.util.function.UnsafeConsumer;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public class BackedVkBuffer implements VulkanResource{
	
	public static class Typed<T extends Struct<T>> extends BackedVkBuffer{
		
		private final Function<ByteBuffer, T> ctor;
		private       T                       dummy;
		private       int                     sizeOf = -1;
		
		public Typed(VkBuffer buffer, VkDeviceMemory memory, Function<ByteBuffer, T> ctor){
			super(buffer, memory);
			this.ctor = ctor;
		}
		
		private T getDummy(){
			if(dummy == null){
				makeDummy();
			}
			return dummy;
		}
		private void makeDummy(){
			dummy = Objects.requireNonNull(ctor.apply(ByteBuffer.allocateDirect(1)));
		}
		
		public BackedVkBuffer.MemorySession<UniformBuffer.SimpleBuffer<T>> updateMulti() throws VulkanCodeException{
			return updateAsVal(bb -> {
				var dummy = getDummy();
				return new UniformBuffer.SimpleBuffer<>(bb, bb.remaining()/dummy.sizeof(), dummy);
			});
		}
		
		public long elementCount(){
			var s = sizeOf;
			if(s == -1) s = sizeOf = getDummy().sizeof();
			return size()/s;
		}
		
	}
	
	public final  VkBuffer       buffer;
	private final VkDeviceMemory memory;
	
	public BackedVkBuffer(VkBuffer buffer, VkDeviceMemory memory){
		this.buffer = buffer;
		this.memory = memory;
	}
	
	
	@Override
	public void destroy(){
		memory.destroy();
		buffer.destroy();
	}
	
	public void copyTo(TransferBuffers transferBuffers, BackedVkBuffer vb) throws VulkanCodeException{
		transferBuffers.syncAction(copyBuffer -> {
			copyBuffer.copyBuffer(buffer, vb.buffer, buffer.size);
		});
	}
	
	public <E extends Throwable> void update(UnsafeConsumer<ByteBuffer, E> populator) throws E, VulkanCodeException{
		update(0, VK10.VK_WHOLE_SIZE, populator);
	}
	public <E extends Throwable> void update(long offset, long size, UnsafeConsumer<ByteBuffer, E> populator) throws E, VulkanCodeException{
		try(var mem = update(offset, size)){
			mem.populate(populator);
		}
	}
	
	public static final class MemorySession<T> implements AutoCloseable{
		private final MappedVkMemory mem;
		public final  T              val;
		public MemorySession(MappedVkMemory mem, T val){
			this.mem = mem;
			this.val = val;
		}
		
		@Override
		public void close() throws VulkanCodeException{
			mem.close();
		}
	}
	
	public <T extends Struct<T>> void updateAsVal(Function<ByteBuffer, T> ctor, Consumer<T> fn) throws VulkanCodeException{
		try(var mem = updateAsVal(ctor)){
			fn.accept(mem.val);
		}
	}
	public <T extends Pointer> MemorySession<T> updateAsVal(Function<ByteBuffer, T> ctor) throws VulkanCodeException{
		return updateAsVal(0, VK10.VK_WHOLE_SIZE, ctor);
	}
	public <T extends Pointer> MemorySession<T> updateAsVal(long offset, long size, Function<ByteBuffer, T> ctor) throws VulkanCodeException{
		var mem = update(offset, size);
		try{
			var buf = ctor.apply(mem.getBuffer());
			return new MemorySession<>(mem, buf);
		}catch(Throwable e){
			mem.close();
			throw e;
		}
	}
	public <T extends Struct<T>, B extends StructBuffer<T, B>> void updateAs(Function<ByteBuffer, B> ctor, Consumer<B> fn) throws VulkanCodeException{
		try(var mem = updateAs(0, VK10.VK_WHOLE_SIZE, ctor)){
			fn.accept(mem.val);
		}
	}
	public <T extends Struct<T>, B extends StructBuffer<T, B>> MemorySession<B> updateAs(Function<ByteBuffer, B> ctor) throws VulkanCodeException{
		return updateAs(0, VK10.VK_WHOLE_SIZE, ctor);
	}
	public <T extends Struct<T>, B extends StructBuffer<T, B>> MemorySession<B> updateAs(long offset, long size, Function<ByteBuffer, B> ctor) throws VulkanCodeException{
		var mem = update(offset, size);
		try{
			var buf = ctor.apply(mem.getBuffer());
			return new MemorySession<>(mem, buf);
		}catch(Throwable e){
			mem.close();
			throw e;
		}
	}
	
	public <E extends Throwable> MappedVkMemory update() throws E, VulkanCodeException{
		return update(0, VK10.VK_WHOLE_SIZE);
	}
	public <E extends Throwable> MappedVkMemory update(long offset, long size) throws E, VulkanCodeException{
		return memory.map(offset, size);
	}
	
	public long size(){ return buffer.size; }
	
	@Override
	public String toString(){
		return "BackedVkBuffer{" + size() + " bytes, usage: " + buffer.usageFlags + " memoryProps: " + memory.propertyFlags + "}";
	}
	
	public <T extends Struct<T>> Typed<T> asTyped(Function<ByteBuffer, T> ctor){
		return new Typed<>(buffer, memory, ctor);
	}
}
