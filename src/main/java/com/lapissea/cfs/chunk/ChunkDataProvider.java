package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.DesyncedCacheException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.lapissea.cfs.GlobalConfig.*;

public interface ChunkDataProvider{
	
	static ChunkDataProvider newVerySimpleProvider(){
		return newVerySimpleProvider((UnsafeBiConsumer<IOInterface, long[], IOException>)null);
	}
	static ChunkDataProvider newVerySimpleProvider(UnsafeBiConsumer<IOInterface, long[], IOException> onWrite){
		var data=new MemoryData();
		
		try(var io=data.io()){
			Cluster.writeMagic(io);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		
		if(onWrite!=null) data.onWrite=ids->onWrite.accept(data, ids);
		return newVerySimpleProvider(data);
	}
	
	static ChunkDataProvider newVerySimpleProvider(IOInterface data){
		
		return new ChunkDataProvider(){
			private final Map<ChunkPointer, Chunk> cache=Collections.synchronizedMap(new HashMap<>());
			private final MemoryManager memoryManager=new VerySimpleMemoryManager(this);
			
			@Override
			public IOInterface getSource(){
				return data;
			}
			@Override
			public MemoryManager getMemoryManager(){
				return memoryManager;
			}
			@Override
			public Map<ChunkPointer, Chunk> getChunkCache(){
				return cache;
			}
			@Override
			public String toString(){
				return "DummyCache";
			}
		};
	}
	
	IOInterface getSource();
	MemoryManager getMemoryManager();
	
	Map<ChunkPointer, Chunk> getChunkCache();
	
	default Chunk getChunkCached(ChunkPointer ptr){
		Objects.requireNonNull(ptr);
		return getChunkCache().get(ptr);
	}
	default Chunk getChunk(ChunkPointer ptr) throws IOException{
		Objects.requireNonNull(ptr);
		
		var cached=getChunkCached(ptr);
		if(cached!=null){
			var read=Chunk.readChunk(this, ptr);
			if(!read.equals(cached)) throw new DesyncedCacheException(read, cached);
		}
		
		var read=Chunk.readChunk(this, ptr);
		getChunkCache().put(ptr, read);
		return read;
	}
	
	default boolean isReadOnly(){
		return getSource().isReadOnly();
	}
	
	default boolean isLastPhysical(Chunk chunk) throws IOException{
		return chunk.dataEnd()>=getSource().getSize();
	}
	default void validate(){}
	
	default void checkCached(Chunk chunk){
		if(DEBUG_VALIDATION) return;
		Chunk cached=getChunkCached(chunk.getPtr());
		assert cached==chunk:"Fake "+chunk;
	}
}
