package com.lapissea.dfs.inspect.display.vk;

import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class UniformBuffer<T extends Struct<T>> implements VulkanResource{
	
	private final Function<ByteBuffer, T> ctor;
	
	private final List<BackedVkBuffer> buffers;
	public final  boolean              ssbo;
	
	public UniformBuffer(List<BackedVkBuffer> buffers, boolean ssbo, Function<ByteBuffer, T> ctor){
		this.buffers = buffers;
		this.ssbo = ssbo;
		this.ctor = ctor;
	}
	
	public void updateAll(Consumer<T> uniformPopulator) throws VulkanCodeException{
		try(var ses = buffers.getFirst().update()){
			var b1    = ses.getBuffer();
			var bytes = b1.remaining();
			uniformPopulator.accept(ctor.apply(b1));
			
			for(var other : Iters.from(buffers).skip(1)){
				try(var otherSes = other.update()){
					assert bytes == otherSes.getBuffer().remaining();
					
					MemoryUtil.memCopy(ses.getAddress(), otherSes.getAddress(), bytes);
				}
			}
		}
	}
	
	public static class SimpleBuffer<T extends Struct<T>> extends StructBuffer<T, SimpleBuffer<T>>{
		
		private final T dummy;
		
		protected SimpleBuffer(ByteBuffer container, int remaining, T dummy){
			super(container, remaining);
			this.dummy = dummy;
		}
		protected SimpleBuffer(long address, ByteBuffer container, int mark, int position, int limit, int capacity, T dummy){
			super(address, container, mark, position, limit, capacity);
			this.dummy = dummy;
		}
		
		@Override
		protected T getElementFactory(){
			return dummy;
		}
		@Override
		protected SimpleBuffer<T> self(){ return this; }
		@Override
		protected SimpleBuffer<T> create(long address, ByteBuffer container, int mark, int position, int limit, int capacity){
			return new SimpleBuffer<>(address, container, mark, position, limit, capacity, dummy);
		}
	}
	
	public BackedVkBuffer.MemorySession<SimpleBuffer<T>> updateMulti(int frameID) throws VulkanCodeException{
		var buf = buffers.get(frameID);
		return buf.updateAsVal(bb -> {
			var dummy = ctor.apply(bb);
			return new SimpleBuffer<>(bb, bb.remaining()/dummy.sizeof(), dummy);
		});
	}
	public BackedVkBuffer.MemorySession<T> update(int frameID) throws VulkanCodeException{
		var buf = buffers.get(frameID);
		return buf.updateAsVal(ctor);
	}
	public void update(int frameID, Consumer<T> uniformPopulator) throws VulkanCodeException{
		var buf = buffers.get(frameID);
		buf.updateAsVal(ctor, uniformPopulator);
	}
	
	public VkBuffer getBuffer(int frame){
		return buffers.get(frame).buffer;
	}
	
	public long size(){
		return buffers.getFirst().buffer.size;
	}
	
	@Override
	public void destroy(){
		buffers.forEach(BackedVkBuffer::destroy);
	}
}
