package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.DesyncedCacheException;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeFunction;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class ChunkCache{
	
	private final Map<ChunkPointer, Chunk> data;
	
	public ChunkCache(){
		this(()->Collections.synchronizedMap(new HashMap<>()));
	}
	public ChunkCache(Supplier<Map<ChunkPointer, Chunk>> newData){
		data=Objects.requireNonNull(newData.get());
	}
	
	public void add(Chunk chunk){
		data.put(chunk.getPtr(), chunk);
	}
	
	@Nullable
	public Chunk get(ChunkPointer pointer){
		return data.get(pointer);
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
	
	public boolean isReal(Chunk chunk) throws DesyncedCacheException{
		var cached=get(chunk.getPtr());
		return chunk==cached;
	}
	
	public void requireReal(Chunk chunk) throws DesyncedCacheException{
		var cached=get(chunk.getPtr());
		if(chunk!=cached) throw new DesyncedCacheException(this, cached);
	}
}
