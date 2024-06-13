package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;

import java.util.Objects;
import java.util.Optional;

public final class ChunkBuilder{
	
	private DataProvider provider;
	private ChunkPointer pos;
	
	private NumberSize bodyNumSize;
	private long       capacity;
	private long       size;
	
	private NumberSize   nextSize;
	private ChunkPointer nextPtr = ChunkPointer.NULL;
	
	public ChunkBuilder(DataProvider provider, ChunkPointer pos, AllocateTicket ticket){
		this.provider = provider;
		this.pos = pos;
		withCapacity(ticket.bytes());
		withExplicitNextSize(ticket.calcNextSize());
		withNext(ticket.next());
	}
	public ChunkBuilder(DataProvider provider, ChunkPointer pos){
		this.provider = provider;
		this.pos = pos;
	}
	
	public ChunkBuilder move(ChunkPointer newPos){
		this.pos = newPos;
		return this;
	}
	public ChunkBuilder withCapacity(long capacity){
		this.capacity = capacity;
		return this;
	}
	public ChunkBuilder withSize(long size){
		this.size = size;
		return this;
	}
	public ChunkBuilder withExplicitBodyNumSize(NumberSize bodyNumSize){
		this.bodyNumSize = bodyNumSize;
		return this;
	}
	
	public ChunkBuilder withExplicitNextSize(NumberSize nextSize){
		this.nextSize = nextSize;
		return this;
	}
	public ChunkBuilder withNext(ChunkPointer next){
		this.nextPtr = Objects.requireNonNull(next);
		return this;
	}
	
	public Optional<NumberSize> getExplicitBodyNumSize(){
		return Optional.ofNullable(bodyNumSize);
	}
	
	public Chunk create(){
		var bodyNumSize = this.bodyNumSize;
		if(bodyNumSize == null) bodyNumSize = NumberSize.bySize(capacity);
		
		var nextSize = this.nextSize;
		if(nextSize == null) nextSize = NumberSize.bySize(nextPtr);
		
		return new Chunk(provider, pos, bodyNumSize, capacity, size, nextSize, nextPtr);
	}
	
	public ChunkBuilder ensureMinSize(){
		if(create().totalSize()>=Chunk.minSafeSize()) return this;
		if(bodyNumSize != null) bodyNumSize = bodyNumSize.max(NumberSize.BYTE);
		var headSize = create().getHeaderSize();
		withCapacity(Chunk.minSafeSize() - headSize);
		return this;
	}
}
