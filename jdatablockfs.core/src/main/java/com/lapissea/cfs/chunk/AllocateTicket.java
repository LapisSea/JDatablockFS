package com.lapissea.cfs.chunk;

import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;

public record AllocateTicket(
	long bytes, ChunkPointer next,
	OptionalLong positionMagnet,
	Optional<Predicate<Chunk>> approve, @Nullable UnsafeConsumer<Chunk, IOException> dataPopulator
){
	
	public static final AllocateTicket DEFAULT=new AllocateTicket(0, ChunkPointer.NULL, OptionalLong.empty(), Optional.empty(), null);
	
	public AllocateTicket{
		assert bytes>=0;
		Objects.requireNonNull(next);
		Objects.requireNonNull(approve);
	}
	
	public static <IO extends IOInstance<IO>> AllocateTicket withData(Class<? extends StructPipe> pipeType, DataProvider provider, IO data){
		return withData(StructPipe.of(pipeType, data.getThisStruct()), provider, data);
	}
	
	public static <IO extends IOInstance<IO>> AllocateTicket withData(StructPipe<IO> pipe, DataProvider provider, IO data){
		SizeDescriptor<IO> size=pipe.getSizeDescriptor();
		return bytes(switch(size){
			case SizeDescriptor.Fixed<IO> f -> f.get(WordSpace.BYTE);
			case SizeDescriptor.Unknown<IO> u -> Math.max(8, u.calcUnknown(pipe.makeIOPool(), provider, data, WordSpace.BYTE));
		}).withDataPopulated((prov, io)->pipe.write(prov, io, data));
	}
	
	public static AllocateTicket bytes(long requestedBytes){
		return DEFAULT.withBytes(requestedBytes);
	}
	
	public static AllocateTicket approved(Predicate<Chunk> approve){
		return DEFAULT.withApproval(approve);
	}
	
	
	public AllocateTicket withApproval(Predicate<Chunk> approve){
		return new AllocateTicket(bytes, next, positionMagnet, Optional.of(this.approve.map(p->p.and(approve)).orElse(approve)), dataPopulator);
	}
	
	public <IO extends IOInstance<IO>> AllocateTicket withDataPopulated(Class<? extends StructPipe> pipeType, IO data){
		return withDataPopulated(StructPipe.of(pipeType, data.getThisStruct()), data);
	}
	
	public <IO extends IOInstance<IO>> AllocateTicket withDataPopulated(StructPipe<IO> pipe, IO data){
		Objects.requireNonNull(pipe);
		Objects.requireNonNull(data);
		return withDataPopulated((provider, io)->pipe.write(provider, io, data));
	}
	
	public AllocateTicket withDataPopulated(UnsafeBiConsumer<DataProvider, RandomIO, IOException> dataPopulator){
		return withDataPopulated(c->{
			try(var io=c.io()){
				dataPopulator.accept(c.getDataProvider(), io);
			}
		});
	}
	
	public AllocateTicket withDataPopulated(UnsafeConsumer<Chunk, IOException> dataPopulator){
		return new AllocateTicket(bytes, next, positionMagnet, approve, dataPopulator);
	}
	
	public AllocateTicket withBytes(long bytes){
		if(this.bytes==bytes) return this;
		return new AllocateTicket(bytes, next, positionMagnet, approve, dataPopulator);
	}
	public AllocateTicket withNext(Chunk next){
		return withNext(Chunk.getPtrNullable(next));
	}
	public AllocateTicket withNext(ChunkPointer next){
		if(Objects.equals(this.next, next)) return this;
		return new AllocateTicket(bytes, next, positionMagnet, approve, dataPopulator);
	}
	
	public AllocateTicket withPositionMagnet(Chunk magnetChunk){
		return withPositionMagnet(magnetChunk.getPtr().getValue());
	}
	public AllocateTicket withPositionMagnet(long positionMagnet){
		return withPositionMagnet(OptionalLong.of(positionMagnet));
	}
	public AllocateTicket withPositionMagnet(OptionalLong positionMagnet){
		return new AllocateTicket(bytes, next, positionMagnet, approve, dataPopulator);
	}
	
	public Chunk submit(DataProvider provider) throws IOException{
		return provider.getMemoryManager().alloc(this);
	}
	public Chunk submit(DataProvider.Holder provider) throws IOException{
		return provider.getDataProvider().getMemoryManager().alloc(this);
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