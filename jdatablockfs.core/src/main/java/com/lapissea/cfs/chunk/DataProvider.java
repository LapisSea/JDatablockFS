package com.lapissea.cfs.chunk;

import com.lapissea.cfs.MagicID;
import com.lapissea.cfs.exceptions.DesyncedCacheException;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.IOHook;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOTypeDB;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public interface DataProvider{
	
	interface Holder{
		DataProvider getDataProvider();
	}
	
	class VerySimple implements DataProvider{
		private final IOTypeDB      typeDB        = new IOTypeDB.MemoryOnlyDB();
		private final ChunkCache    cache         = ChunkCache.strong();
		private final MemoryManager memoryManager = new VerySimpleMemoryManager(this);
		private final IOInterface   data;
		
		public VerySimple(IOInterface data){
			this.data = data;
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
			return getChunk(ChunkPointer.of(MagicID.size()));
		}
		@Override
		public String toString(){
			return "Very simple provider";
		}
	}
	
	static DataProvider newVerySimpleProvider(){
		return newVerySimpleProvider((IOHook)null);
	}
	
	static DataProvider newVerySimpleProvider(IOHook onWrite){
		var data = new MemoryData.Builder()
			           .withOnWrite(onWrite)
			           .withInitial(MagicID::write)
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
			ensureChunkValid(ptr);
		}
		return getChunkCache().getOr(ptr, this::readChunk);
	}
	
	private void ensureChunkValid(ChunkPointer ptr) throws IOException{
		var siz = getSource().getIOSize();
		if(ptr.getValue()>=siz){
			throw new MalformedPointerException(ptr.getValue() + " is pointing outside file (file size: " + siz + ")");
		}
		
		var cached = getChunkCache().get(ptr);
		if(cached == null) return;
		var read = readChunk(ptr);
		if(!read.equals(cached)){
			throw new DesyncedCacheException(read, cached);
		}
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
	default void validate(){ }
	
	default void checkCached(Chunk chunk){
		Chunk cached = getChunkCached(chunk.getPtr());
		if(cached != chunk){
			throw new IllegalStateException("Fake " + chunk);
		}
	}
	
	default DataProvider withRouter(Function<AllocateTicket, AllocateTicket> router){
		class Routed implements DataProvider, MemoryManager{
			private MemoryManager src(){
				return DataProvider.this.getMemoryManager();
			}
			
			@Override
			public IOTypeDB getTypeDb(){ return DataProvider.this.getTypeDb(); }
			@Override
			public IOInterface getSource(){ return DataProvider.this.getSource(); }
			@Override
			public MemoryManager getMemoryManager(){ return this; }
			@Override
			public ChunkCache getChunkCache(){ return DataProvider.this.getChunkCache(); }
			@Override
			public Chunk getFirstChunk() throws IOException{ return DataProvider.this.getFirstChunk(); }
			
			@Override
			public DefragSes openDefragmentMode(){ return src().openDefragmentMode(); }
			@Override
			public IOList<ChunkPointer> getFreeChunks(){ return src().getFreeChunks(); }
			@Override
			public DataProvider getDataProvider(){ return this; }
			@Override
			public void free(Collection<Chunk> toFree) throws IOException{
				src().free(toFree);
			}
			@Override
			public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
				src().allocTo(firstChunk, target, toAllocate);
			}
			
			@Override
			public Chunk alloc(AllocateTicket ticket) throws IOException{
				return src().alloc(router.apply(ticket));
			}
			@Override
			public String toString(){
				return super.toString();
			}
		}
		
		return new Routed();
	}
}
