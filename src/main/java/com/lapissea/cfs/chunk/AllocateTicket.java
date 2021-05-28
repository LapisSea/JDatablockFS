package com.lapissea.cfs.chunk;

import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Predicate;

public record AllocateTicket(long bytes, boolean disableResizing, ChunkPointer next, @Nullable Predicate<Chunk> approve, @Nullable UnsafeConsumer<Chunk, IOException> dataPopulator){
	
	public static final AllocateTicket DEFAULT=new AllocateTicket(0, false, null, null, null);
	
	public AllocateTicket{
		assert bytes>=0;
	}
	
	
	public static AllocateTicket bytes(long requestedBytes){
		return DEFAULT.withBytes(requestedBytes);
	}
	
	public static AllocateTicket approved(Predicate<Chunk> approve){
		return DEFAULT.withApproval(approve);
	}
	
	public AllocateTicket withDisabledResizing(){
		return shouldDisableResizing(true);
	}
	
	public AllocateTicket shouldDisableResizing(boolean disableResizing){
		if(this.disableResizing==disableResizing) return this;
		return new AllocateTicket(bytes, disableResizing, next, approve, dataPopulator);
	}
	
	public AllocateTicket withApproval(Predicate<Chunk> approve){
		return new AllocateTicket(bytes, disableResizing, next, approve, dataPopulator);
	}
	
	public AllocateTicket withDataPopulated(UnsafeBiConsumer<ChunkDataProvider, RandomIO, IOException> dataPopulator){
		return withDataPopulated(c->{
			try(var io=c.io()){
				dataPopulator.accept(c.getProvider(), io);
			}
		});
	}
	
	public AllocateTicket withDataPopulated(UnsafeConsumer<Chunk, IOException> dataPopulator){
		return new AllocateTicket(bytes, disableResizing, next, approve, dataPopulator);
	}
	
	public AllocateTicket withBytes(long bytes){
		if(this.bytes==bytes) return this;
		return new AllocateTicket(bytes, disableResizing, next, approve, dataPopulator);
	}
	public AllocateTicket withNext(Chunk next){
		return withNext(Chunk.getPtr(next));
	}
	public AllocateTicket withNext(ChunkPointer next){
		if(Objects.equals(this.next, next)) return this;
		return new AllocateTicket(bytes, disableResizing, next, approve, dataPopulator);
	}
	
	public Chunk submit(ChunkDataProvider provider) throws IOException{
		return provider.getMemoryManager().alloc(this);
	}
	public Chunk submit(MemoryManager manager) throws IOException{
		return manager.alloc(this);
	}
	
	public boolean approve(Chunk chunk){
		return approve==null||approve.test(chunk);
	}
	
	public void populate(Chunk chunk) throws IOException{
		if(dataPopulator==null) return;
		dataPopulator.accept(chunk);
	}
	
}
