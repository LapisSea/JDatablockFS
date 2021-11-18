package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.DesyncedCacheException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.IOTypeDB;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public interface DataProvider{
	
	interface Holder{
		DataProvider getChunkProvider();
	}
	
	class VerySimple implements DataProvider{
		private final IOTypeDB      typeDB       =new IOTypeDB.MemoryOnlyDB();
		private final ChunkCache    cache        =ChunkCache.strong();
		private final MemoryManager memoryManager=new VerySimpleMemoryManager(this);
		private final IOInterface   data;
		
		public VerySimple(IOInterface data){
			this.data=data;
		}
		
		@Override
		public IOTypeDB getTypeDb(){
			return typeDB;
		}
		@Override
		public IOInterface getSource(){
			return data;
		}
		@Override
		public MemoryManager getMemoryManager(){
			return memoryManager;
		}
		@Override
		public ChunkCache getChunkCache(){
			return cache;
		}
		@Override
		public Chunk getFirstChunk() throws IOException{
			return getChunk(ChunkPointer.of(Cluster.getMagicId().limit()));
		}
		@Override
		public String toString(){
			return "Very simple provider";
		}
	}
	
	static DataProvider newVerySimpleProvider() throws IOException{
		return newVerySimpleProvider((MemoryData.EventLogger)null);
	}
	
	static DataProvider newVerySimpleProvider(MemoryData.EventLogger onWrite) throws IOException{
		var data=new MemoryData.Builder()
			.withOnWrite(onWrite)
			.withInitial(dest->dest.write(Cluster.getMagicId()))
			.build();
		return newVerySimpleProvider(data);
	}
	
	static DataProvider newVerySimpleProvider(IOInterface data){
		return new VerySimple(data);
	}
	
	IOTypeDB getTypeDb();
	
	IOInterface getSource();
	MemoryManager getMemoryManager();
	ChunkCache getChunkCache();
	
	Chunk getFirstChunk() throws IOException;
	
	default Chunk getChunkCached(ChunkPointer ptr){
		Objects.requireNonNull(ptr);
		return getChunkCache().get(ptr);
	}
	default Chunk getChunk(ChunkPointer ptr) throws IOException{
		Objects.requireNonNull(ptr);
		
		if(DEBUG_VALIDATION){
			getChunkCache().ifCached(ptr, cached->{
				var read=readChunk(ptr);
				if(!read.equals(cached)){
					throw new DesyncedCacheException(read, cached);
				}
			});
		}
		
		return getChunkCache().getOr(ptr, this::readChunk);
	}
	
	private Chunk readChunk(ChunkPointer ptr) throws IOException{
		return Chunk.readChunk(this, ptr);
	}
	
	default boolean isReadOnly(){
		return getSource().isReadOnly();
	}
	
	default boolean isLastPhysical(Chunk chunk) throws IOException{
		return chunk.dataEnd()>=getSource().getIOSize();
	}
	default void validate(){}
	
	default void checkCached(Chunk chunk){
		if(DEBUG_VALIDATION) return;
		Chunk cached=getChunkCached(chunk.getPtr());
		assert cached==chunk:"Fake "+chunk;
	}
}
