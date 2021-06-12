package com.lapissea.cfs.chunk;

import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;

import java.util.Objects;

public final class ChunkBuilder{
	
	private ChunkDataProvider provider;
	private ChunkPointer      ptr;
	
	private NumberSize bodyNumSize;
	private long       capacity;
	private long       size;
	
	private NumberSize   nextSize;
	private ChunkPointer nextPtr=ChunkPointer.NULL;
	
	public ChunkBuilder(ChunkDataProvider provider, ChunkPointer ptr){
		this.provider=provider;
		this.ptr=ptr;
	}
	
	public ChunkBuilder withProvider(ChunkDataProvider provider){
		this.provider=provider;
		return this;
	}
	public ChunkBuilder withPtr(ChunkPointer ptr){
		this.ptr=ptr;
		return this;
	}
	public ChunkBuilder withCapacity(long capacity){
		this.capacity=capacity;
		return this;
	}
	public ChunkBuilder withSize(long size){
		this.size=size;
		return this;
	}
	public ChunkBuilder withExplicitBodyNumSize(NumberSize bodyNumSize){
		this.bodyNumSize=bodyNumSize;
		return this;
	}
	
	public ChunkBuilder withExplicitNextSize(NumberSize nextSize){
		this.nextSize=nextSize;
		return this;
	}
	public ChunkBuilder withNext(ChunkPointer next){
		this.nextPtr=Objects.requireNonNull(next);
		return this;
	}
	public ChunkBuilder withNext(Chunk next){
		withNext(next==null?null:next.getPtr());
		return this;
	}
	
	public Chunk create(){
		var bodyNumSize=this.bodyNumSize;
		if(bodyNumSize==null) bodyNumSize=NumberSize.bySize(capacity);
		
		var nextSize=this.nextSize;
		if(nextSize==null) nextSize=NumberSize.bySize(nextPtr).max(NumberSize.BYTE);
		
		return new Chunk(provider, ptr, bodyNumSize, capacity, size, nextSize, nextPtr);
	}
}
