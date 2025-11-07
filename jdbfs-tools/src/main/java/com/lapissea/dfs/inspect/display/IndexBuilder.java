package com.lapissea.dfs.inspect.display;

import com.lapissea.dfs.inspect.display.renderers.Geometry;
import com.lapissea.dfs.inspect.display.vk.enums.VkIndexType;
import com.lapissea.dfs.utils.iterableplus.IntIterator;
import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public class IndexBuilder implements IterableIntPP.SizedPP{
	
	public interface IndexAdder{
		void add(int index);
		void add(int[] indices);
		void add(IterableIntPP indices);
	}
	
	private final List<byte[]> buffers = new ArrayList<>();
	
	private byte[] buffer;
	private int    bufferPos;
	
	private final int initialCapacity;
	private       int elementSize;
	
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
		if(initialCapacity<2){
			throw new IllegalArgumentException("initial capacity should be greater than 1");
		}
		setType(type);
	}
	
	public boolean hasNoResize(){
		return noResize;
	}
	public IndexBuilder noResize(){
		noResize = true;
		if(buffer == null){
			buffer = new byte[initialCapacity*type.byteSize];
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
		
		if(buffer == null || bufferPos == buffer.length){
			grow(type.byteSize);
		}
		type.write(buffer, bufferPos, actualIndex);
		bufferPos += type.byteSize;
		elementSize++;
	}
	
	public void addOffset(int[] indices, int indexOffset){
		var max = 0;
		for(int index : indices) max = Math.max(max, index);
		ensureTypeFits(max + indexOffset);
		
		var siz         = type.byteSize;
		var neededMem   = indices.length*siz;
		int transferred = noResize? 0 : growCheckPartialTransfer(indices, indexOffset, neededMem);
		
		var count = indices.length - transferred;
		for(int i = 0; i<count; i++){
			type.write(buffer, bufferPos + i*siz, indices[i + transferred] + indexOffset);
		}
		bufferPos += count*siz;
		elementSize += indices.length;
	}
	private int growCheckPartialTransfer(int[] indices, int indexOffset, int neededMem){
		if(buffer == null){
			grow(neededMem);
			return 0;
		}
		
		int transferred = 0;
		var remaining   = bufferRemaining();
		if(remaining<neededMem){
			var siz = type.byteSize;
			transferred = remaining/siz;
			for(int i = 0; i<transferred; i++){
				type.write(buffer, bufferPos + i*siz, indices[i] + indexOffset);
			}
			bufferPos += transferred*siz;
			grow(neededMem - transferred*type.byteSize);
		}
		return transferred;
	}
	private int bufferRemaining(){
		return buffer.length - bufferPos;
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
		if(buffer == null || bufferRemaining()<neededMem){
			if(buffer != null){
				neededMem -= bufferRemaining();
				while(bufferRemaining() != 0){
					var index = iter.nextInt();
					type.write(buffer, bufferPos, index + indexOffset);
					bufferPos += type.byteSize;
				}
			}
			grow(neededMem);
		}
		
		while(iter.hasNext()){
			var index = iter.nextInt();
			type.write(buffer, bufferPos, index + indexOffset);
			bufferPos += type.byteSize;
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
			var cap = buffer.length;
			growCapacity = Math.toIntExact(Math.ceilDiv(cap + cap/2L, type.byteSize)*type.byteSize);
			buffers.add(buffer);
		}
		setBuffer(new byte[Math.max(growCapacity, bytesToAdd)]);
	}
	
	private void ensureTypeFits(int maxIndex){
		if(maxIndex<=maxValue){
			return;
		}
		maxValue = maxIndex;
		if(maxIndex>maxTypeValue){
			growType(maxIndex);
		}
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
		
		var oldByteSize = oldType.byteSize;
		var newByteSize = newType.byteSize;
		
		var totalElementCapacity = Iters.concatN1(buffers, buffer).mapToInt(e -> e.length).sum()/oldByteSize;
		
		var newBuffer  = new byte[totalElementCapacity*newByteSize];
		var oldBuff    = buffer;
		var oldBuffPos = bufferPos;
		bufferPos = 0;
		setBuffer(newBuffer);
		for(var bb : Iters.concatN1(buffers, oldBuff)){
			var byteCount = bb == oldBuff? oldBuffPos : bb.length;
			for(int i = 0; i<byteCount; i += oldByteSize){
				newType.write(newBuffer, bufferPos + i*newByteSize, oldType.read(bb, i*oldByteSize));
			}
			bufferPos += (byteCount/oldByteSize)*newByteSize;
		}
		buffers.clear();
	}
	
	private void setBuffer(byte[] newBuffer){
		buffer = newBuffer;
		bufferPos = 0;
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
		
		return Iters.concatN1(buffers, buffer)
		            .flatMapToInt(b -> Iters.range(0, b == buffer? bufferPos : b.length, type.byteSize).map(off -> {
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
			}
			iboBuff.put(buffer, 0, bufferPos);
		}else{
			var byteSize = type.byteSize;
			for(var bb : buffers){
				for(int i = 0; i<bb.length; i += byteSize){
					destType.write(iboBuff, off + type.read(bb, i*byteSize));
				}
			}
			for(int i = 0; i<bufferPos; i += byteSize){
				destType.write(iboBuff, off + type.read(buffer, i*byteSize));
			}
		}
	}
	
	public void clear(){
		buffers.clear();
		buffer = null;
		
		bufferPos = 0;
		
		elementSize = 0;
		
		setType(VkIndexType.UINT8);
		maxValue = -1;
	}
	
}
