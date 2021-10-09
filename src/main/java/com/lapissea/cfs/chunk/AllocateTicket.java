package com.lapissea.cfs.chunk;

import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public record AllocateTicket(long bytes, boolean disableResizing, ChunkPointer next, Optional<Predicate<Chunk>> approve, @Nullable UnsafeConsumer<Chunk, IOException> dataPopulator){
	
	public static final AllocateTicket DEFAULT=new AllocateTicket(0, false, ChunkPointer.NULL, Optional.empty(), null);
	
	public AllocateTicket{
		assert bytes>=0;
		Objects.requireNonNull(next);
		Objects.requireNonNull(approve);
	}
	
	public static <IO extends IOInstance<IO>> AllocateTicket withData(Class<? extends StructPipe> pipeType, IO data){
		return withData(StructPipe.of(pipeType, data.getThisStruct()), data);
	}
	
	public static <IO extends IOInstance<IO>> AllocateTicket withData(StructPipe<IO> pipe, IO data){
		return bytes(pipe.getSizeDescriptor().calcUnknown(data)).withDataPopulated((provider, io)->pipe.write(provider, io, data));
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
		return new AllocateTicket(bytes, disableResizing, next, Optional.of(this.approve.map(p->p.and(approve)).orElse(approve)), dataPopulator);
	}
	
	public <IO extends IOInstance<IO>> AllocateTicket withDataPopulated(Class<? extends StructPipe> pipeType, IO data){
		return withDataPopulated(StructPipe.of(pipeType, data.getThisStruct()), data);
	}
	
	public <IO extends IOInstance<IO>> AllocateTicket withDataPopulated(StructPipe<IO> pipe, IO data){
		Objects.requireNonNull(pipe);
		Objects.requireNonNull(data);
		return withDataPopulated((provider, io)->pipe.write(provider, io, data));
	}
	
	public AllocateTicket withDataPopulated(UnsafeBiConsumer<ChunkDataProvider, RandomIO, IOException> dataPopulator){
		return withDataPopulated(c->{
			try(var io=c.io()){
				dataPopulator.accept(c.getChunkProvider(), io);
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
		return approve.isEmpty()||approve.get().test(chunk);
	}
	
	public void populate(Chunk chunk) throws IOException{
		if(dataPopulator==null) return;
		dataPopulator.accept(chunk);
	}
	
}
