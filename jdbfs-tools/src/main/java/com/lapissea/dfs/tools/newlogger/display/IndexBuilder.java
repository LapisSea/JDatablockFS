package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkIndexType;
import com.lapissea.dfs.utils.iterableplus.IntIterator;
import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public class IndexBuilder implements IterableIntPP.SizedPP{
	
	public interface IndexAdder{
		void add(int index);
		void add(int[] indices);
		void add(IterableIntPP indices);
	}
	
	private final List<ByteBuffer> buffers = new ArrayList<>();
	
	private       ByteBuffer buffer;
	private final int        initialCapacity;
	private       int        elementSize;
	
	private VkIndexType type;
	private int         maxTypeValue;
	
	private int     maxValue = -1;
	private boolean noResize;
	
	public IndexBuilder(){ this(32); }
	public IndexBuilder(int initialCapacity){
		this(initialCapacity, VkIndexType.UINT8);
	}
	public IndexBuilder(Geometry.MeshSize meshSize){
		this(meshSize.indexCount(), findType(meshSize.vertCount()));
	}
	public IndexBuilder(int initialCapacity, VkIndexType type){
		this.initialCapacity = initialCapacity;
		if(initialCapacity<2) throw new IllegalArgumentException("initial capacity should be greater than 1");
		setType(type);
	}
	public IndexBuilder(ByteBuffer data, VkIndexType type){
		assert data.order() == ByteOrder.nativeOrder();
		this.initialCapacity = -1;
		setType(type);
		buffer = Objects.requireNonNull(data);
		elementSize = data.limit()/type.byteSize;
	}
	
	public IndexBuilder noResize(){
		noResize = true;
		if(buffer == null){
			buffer = allocate(initialCapacity*type.byteSize);
		}
		return this;
	}
	
	public IndexAdder adder(int indexOffset){
		return new IndexAdder(){
			@Override
			public void add(int index){ addOffset(index, indexOffset); }
			@Override
			public void add(int[] indices){ addOffset(indices, indexOffset); }
			@Override
			public void add(IterableIntPP indices){ addOffset(indices, indexOffset); }
		};
	}
	
	public void addOffset(int index, int indexOffset){
		var actualIndex = indexOffset + index;
		ensureTypeFits(actualIndex);
		
		if(buffer == null || !buffer.hasRemaining()){
			grow(1);
		}
		type.write(buffer, actualIndex);
		elementSize++;
	}
	
	public void addOffset(int[] indices, int indexOffset){
		var max = 0;
		for(int index : indices) max = Math.max(max, index);
		ensureTypeFits(max + indexOffset);
		
		var neededMem   = indices.length*type.byteSize;
		int transferred = noResize? 0 : growCheckPartialTransfer(indices, indexOffset, neededMem);
		
		for(int i = transferred; i<indices.length; i++){
			type.write(buffer, indices[i] + indexOffset);
		}
		elementSize += indices.length;
	}
	private int growCheckPartialTransfer(int[] indices, int indexOffset, int neededMem){
		int transferred = 0;
		if(buffer == null || buffer.remaining()<neededMem){
			if(buffer != null){
				transferred = buffer.remaining()/type.byteSize;
				for(int i = 0; i<transferred; i++){
					type.write(buffer, indices[i] + indexOffset);
				}
			}
			grow(neededMem - transferred*type.byteSize);
		}
		return transferred;
	}
	
	public void addOffset(IterableIntPP indices, int indexOffset){
		int max = 0, count = 0;
		if(indices instanceof IndexBuilder ib){
			max = ib.max(0);
			count = ib.elementSize();
		}else{
			var iter = indices.iterator();
			while(iter.hasNext()){
				var index = iter.nextInt();
				max = Math.max(max, index);
				count++;
			}
		}
		ensureTypeFits(max + indexOffset);
		
		var iter = indices.iterator();
		
		var neededMem = count*type.byteSize;
		if(buffer == null || buffer.remaining()<neededMem){
			if(buffer != null){
				neededMem -= buffer.remaining();
				while(buffer.hasRemaining()){
					var index = iter.nextInt();
					type.write(buffer, index + indexOffset);
				}
			}
			grow(neededMem);
		}
		
		while(iter.hasNext()){
			var index = iter.nextInt();
			type.write(buffer, index + indexOffset);
		}
		elementSize += count;
	}
	
	public int elementSize(){
		return elementSize;
	}
	public long byteSize(){
		return elementSize*(long)type.byteSize;
	}
	
	private void grow(int bytesToAdd){
		if(noResize){
			throw new UnsupportedOperationException("Should not grow, initial capacity was insufficient");
		}
		
		int growCapacity;
		if(buffer == null){
			growCapacity = initialCapacity*type.byteSize;
		}else{
			var cap = buffer.capacity();
			growCapacity = Math.toIntExact(Math.ceilDiv(cap + cap/2L, type.byteSize)*type.byteSize);
			buffers.add(buffer.flip());
		}
		buffer = allocate(Math.max(growCapacity, bytesToAdd));
	}
	
	private void ensureTypeFits(int maxIndex){
		if(maxIndex>maxTypeValue){
			growType(maxIndex);
		}
		maxValue = Math.max(maxValue, maxIndex);
	}
	
	private void growType(int index){
		VkIndexType oldType = type;
		VkIndexType newType = findNewType(index, type);
		
		setType(newType);
		
		if(buffer == null || oldType == newType){
			return;
		}
		if(noResize){
			throw new UnsupportedOperationException("Should not resize, initial type was insufficient");
		}
		
		
		buffer.flip();
		
		var oldByteSize = oldType.byteSize;
		
		var totalElementCapacity = Iters.concatN1(buffers, buffer).mapToInt(ByteBuffer::capacity).sum()/oldByteSize;
		
		var newBuffer = allocate(totalElementCapacity*newType.byteSize);
		for(var bb : Iters.concatN1(buffers, buffer)){
			for(int i = 0, lim = bb.limit(); i<lim; i += oldByteSize){
				newType.write(newBuffer, oldType.read(bb, i));
			}
		}
		buffers.clear();
		buffer = newBuffer;
		setType(newType);
	}
	
	public static VkIndexType findType(int index){
		return findNewType(index, VkIndexType.UINT8);
	}
	private static VkIndexType findNewType(int index, VkIndexType newType){
		while(index>newType.getMaxSize()){
			newType = switch(newType){
				case UINT8 -> VkIndexType.UINT16;
				case UINT16 -> VkIndexType.UINT32;
				case UINT32 -> throw new OutOfMemoryError("Index too large");
			};
		}
		return newType;
	}
	
	private void setType(VkIndexType type){
		this.type = type;
		maxTypeValue = type.getMaxSize();
	}
	public VkIndexType getType(){
		return type;
	}
	
	@Override
	public IntIterator iterator(){
		if(buffer == null){
			return Iters.ofInts().iterator();
		}
		
		return Iters.concatN1(buffers, flipNewCursor(buffer))
		            .flatMapToInt(b -> Iters.range(0, b.limit(), type.byteSize).map(off -> {
			            return type.read(b, off);
		            }))
		            .iterator();
	}
	
	@Override
	public OptionalInt getSize(){
		return OptionalInt.of(elementSize);
	}
	
	@Override
	public OptionalInt max(){
		if(buffer == null){
			return OptionalInt.empty();
		}
		if(maxValue == -1){
			maxValue = SizedPP.super.max().orElseThrow();
		}
		return OptionalInt.of(maxValue);
	}
	@Override
	public String toString(){
		return "IndexBuilder{count=" + elementSize + ", type=" + type + "}";
	}
	
	public void transferTo(ByteBuffer iboBuff, VkIndexType destType, int off){
		assert iboBuff.order() == ByteOrder.nativeOrder();
		
		var size = destType.getMaxSize();
		if(maxValue + off>size){
			throw new IllegalStateException("Can't fit " + size + " in to " + (off + maxValue));
		}
		
		if(buffer == null){
			return;
		}
		
		if(destType == type && off == 0){
			for(var bb : buffers){
				iboBuff.put(bb);
				bb.position(0);
			}
			iboBuff.put(flipNewCursor(buffer));
		}else{
			var byteSize = type.byteSize;
			for(var bb : buffers){
				for(int i = 0; i<bb.limit(); i += byteSize){
					destType.write(iboBuff, off + type.read(bb, i));
				}
			}
			for(int i = 0; i<buffer.position(); i += byteSize){
				destType.write(iboBuff, off + type.read(buffer, i));
			}
		}
	}
	
	private static ByteBuffer flipNewCursor(ByteBuffer buffer){
		return buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).flip();
	}
	private static ByteBuffer allocate(int siz){
		return ByteBuffer.allocate(siz).order(ByteOrder.nativeOrder());
	}
}
