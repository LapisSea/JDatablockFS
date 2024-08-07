package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.CacheOutOfSync;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.WeakValueHashMap;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public final class ChunkCache{
	
	public static ChunkCache none(){
		return new ChunkCache(() -> new AbstractMap<>(){
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
		return new ChunkCache(HashMap::new);
	}
	public static ChunkCache weak(){
		return new ChunkCache(WeakValueHashMap::new);
//		return new ChunkCache(()->Collections.synchronizedMap(new WeakValueHashMap<ChunkPointer, Chunk>().defineStayAlivePolicy(1)));
	}
	
	private final Map<ChunkPointer, Chunk> data;
	
	public ChunkCache(Supplier<Map<ChunkPointer, Chunk>> dataInitializer){
		data = Objects.requireNonNull(dataInitializer.get());
	}
	
	public synchronized void add(Chunk chunk){
		if(DEBUG_VALIDATION) addCheck(chunk);
		data.put(chunk.getPtr(), chunk);
	}
	private void addCheck(Chunk chunk){
		if(data.containsKey(chunk.getPtr())){
			throw new IllegalStateException(chunk.getPtr() + " already exists");
		}
	}
	
	@Nullable
	public synchronized Chunk get(ChunkPointer pointer){
		return data.get(pointer);
	}
	
	public synchronized void notifyDestroyed(Iterable<Chunk> chunks){
		for(Chunk chunk : chunks){
			if(DEBUG_VALIDATION) validateDestroyed(chunk);
			data.remove(chunk.getPtr());
		}
	}
	public synchronized void notifyDestroyed(Chunk chunk){
		if(DEBUG_VALIDATION) validateDestroyed(chunk);
		data.remove(chunk.getPtr());
	}
	
	private void validateDestroyed(Chunk chunk){
		if(Chunk.isChunkValidAt(chunk.getDataProvider(), chunk.getPtr())){
			throw new IllegalStateException("Chunk at " + chunk.getPtr() + " is still valid!");
		}
		
		if(!data.containsKey(chunk.getPtr())){
			throw new IllegalStateException(chunk.getPtr() + " is not cached");
		}
	}
	
	@NotNull
	public synchronized <T extends Throwable> Chunk getOr(ChunkPointer pointer, UnsafeFunction<ChunkPointer, Chunk, T> orGet) throws T{
		var chunk = data.get(pointer);
		if(chunk != null){
			return chunk;
		}
		
		var generated = orGet.apply(pointer);
		Objects.requireNonNull(generated);
		data.put(generated.getPtr(), generated);
		return generated;
	}
	
	public synchronized void requireReal(Chunk chunk) throws CacheOutOfSync{
		var cached = data.get(chunk.getPtr());
		if(chunk != cached){
			throw new CacheOutOfSync(chunk, cached);
		}
	}
	
	public synchronized void validate(DataProvider provider) throws IOException{
		Chunk f;
		try{
			f = provider.getFirstChunk();
		}catch(Throwable e){
			return;
		}
		var vals = List.copyOf(data.values());
		
		for(Chunk cached : vals){
			try{
				var read = Chunk.readChunk(provider, cached.getPtr());
				if(!read.equals(cached)){
					throw new CacheOutOfSync(read, cached);
				}
			}catch(Throwable e){
				throw new RuntimeException(cached + "", e);
			}
			
		}
		
		outer:
		for(Chunk value : vals){
			for(Chunk chunk : new PhysicalChunkWalker(f)){
				if(chunk.getPtr().getValue()>value.getPtr().getValue()) break;
				if(chunk == value){
					continue outer;
				}
			}
			throw new CacheOutOfSync("Unreachable chunk: " + value);
		}
	}
}
