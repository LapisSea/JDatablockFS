package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.DesyncedCacheException;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.WeakValueHashMap;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.util.*;
import java.util.function.Supplier;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class ChunkCache{
	
	public static ChunkCache none(){
		return new ChunkCache(()->new AbstractMap<>(){
			@Override
			public Chunk put(ChunkPointer key, Chunk value){
				return null;
			}
			@Override
			public Set<Entry<ChunkPointer, Chunk>> entrySet(){
				return Set.of();
			}
		});
	}
	
	public static ChunkCache strong(){
		return new ChunkCache(()->Collections.synchronizedMap(new HashMap<>()));
	}
	public static ChunkCache weak(){
		return new ChunkCache(()->Collections.synchronizedMap(new WeakValueHashMap<>()));
//		return new ChunkCache(()->Collections.synchronizedMap(new WeakValueHashMap<ChunkPointer, Chunk>().defineStayAlivePolicy(1))); TODO: fix define policy
	}
	
	private final Map<ChunkPointer, Chunk> data;
	
	public ChunkCache(Supplier<Map<ChunkPointer, Chunk>> dataInitializer){
		data=Objects.requireNonNull(dataInitializer.get());
	}
	
	public void add(Chunk chunk){
		data.put(chunk.getPtr(), chunk);
	}
	
	@Nullable
	public Chunk get(ChunkPointer pointer){
		return data.get(pointer);
	}
	
	public void notifyDestroyed(Chunk chunk){
		if(DEBUG_VALIDATION){
			var fail=false;
			try{
				Chunk.readChunk(chunk.getDataProvider(), chunk.getPtr());
			}catch(Throwable e){
				fail=true;
			}
			if(!fail){
				throw new IllegalStateException("Chunk at "+chunk.getPtr()+" is still valid!");
			}
		}
		data.remove(chunk.getPtr());
	}
	
	public <T extends Throwable> void ifCached(ChunkPointer pointer, UnsafeConsumer<Chunk, T> onPresent) throws T{
		var chunk=get(pointer);
		if(chunk!=null){
			onPresent.accept(chunk);
		}
	}
	
	@NotNull
	public <T extends Throwable> Chunk getOr(ChunkPointer pointer, UnsafeFunction<ChunkPointer, Chunk, T> orGet) throws T{
		var chunk=get(pointer);
		if(chunk!=null){
			return chunk;
		}
		
		var generated=orGet.apply(pointer);
		Objects.requireNonNull(generated);
		add(generated);
		return generated;
	}
	
	public boolean isReal(Chunk chunk){
		var cached=get(chunk.getPtr());
		return chunk==cached;
	}
	
	public void requireReal(Chunk chunk) throws DesyncedCacheException{
		var cached=get(chunk.getPtr());
		if(chunk!=cached) throw new DesyncedCacheException(this, cached);
	}
}
