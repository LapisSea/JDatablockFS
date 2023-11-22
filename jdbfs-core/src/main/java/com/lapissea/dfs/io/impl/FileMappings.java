package com.lapissea.dfs.io.impl;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.internal.MyUnsafe.UNSAFE;

final class FileMappings implements Closeable{
	
	public static final int MAX_CHUNK_SIZE    = 1024*1024*1024;
	public static final int MAX_SMALL_MAPPING = 32;
	
	private final FileChannel fileChannel;
	
	private final boolean readOnly;
	private       long    size;
	
	private       Reference<MappedByteBuffer>[]          mappings      = new Reference[1];
	private final Map<Long, Reference<MappedByteBuffer>> largeMappings = new HashMap<>();
	private final ReadWriteLock                          lock          = new ReentrantReadWriteLock();
	
	FileMappings(FileChannel fileChannel, boolean readOnly) throws IOException{
		this.fileChannel = fileChannel;
		this.readOnly = readOnly;
		size = fileChannel.size();
	}
	
	public long fileSize(){
		return size;
	}
	
	@Override
	public void close() throws IOException{
		var lock = this.lock.writeLock();
		lock.lock();
		try{
			clearMappings();
			fileChannel.close();
		}finally{ lock.unlock(); }
	}
	
	private final ByteBuffer zero = ByteBuffer.wrap(new byte[]{0});
	
	public void resize(long newSize) throws IOException{
		var lock = this.lock.writeLock();
		lock.lock();
		try{
			if(size == newSize) return;
			if(size<newSize){
				removeMapping(size/MAX_CHUNK_SIZE);
				var written = fileChannel.write(zero.position(0), newSize - 1);
				assert written == 1;
			}else{
				clearMappings();
				fileChannel.truncate(newSize);
			}
			
			var actualSize = fileChannel.size();
			if(actualSize != newSize){
				throw new IOException("Failed to set size " + actualSize + " to " + newSize);
			}
			size = actualSize;
		}finally{ lock.unlock(); }
	}
	
	
	public void removeMapping(long index){
		var lock = this.lock.writeLock();
		lock.lock();
		try{
			MappedByteBuffer mapping;
			if(index<mappings.length){
				mapping = unwrap(mappings[(int)index]);
				mappings[(int)index] = null;
			}else{
				mapping = unwrap(largeMappings.remove(index));
			}
			if(mapping != null){
				unmap(mapping);
			}
		}finally{ lock.unlock(); }
	}
	
	private MappedByteBuffer makeMapping(long index) throws IOException{
		var lock = this.lock.writeLock();
		lock.lock();
		try{
			if(index<MAX_SMALL_MAPPING){
				var iIndex = (int)index;
				if(mappings.length<=iIndex){
					mappings = Arrays.copyOf(mappings, iIndex + 1);
				}
				var mapping = unwrap(mappings[iIndex]);
				if(mapping != null){
					return mapping;
				}
				var val = mapIdx(index);
				mappings[iIndex] = wrap(val);
				return val;
			}
			
			var mapping = unwrap(largeMappings.get(index));
			if(mapping == null){
				largeMappings.put(index, wrap(mapping = mapIdx(index)));
			}
			return mapping;
		}finally{ lock.unlock(); }
	}
	private MappedByteBuffer getMapping(long index){
		var lock = this.lock.readLock();
		lock.lock();
		try{
			if(index<MAX_SMALL_MAPPING){
				var iIndex = (int)index;
				if(mappings.length<=iIndex){
					return null;
				}
				return unwrap(mappings[iIndex]);
			}
			
			return unwrap(largeMappings.get(index));
		}finally{ lock.unlock(); }
	}
	
	public <T> T onMapping(long index, Function<MappedByteBuffer, T> consumer) throws IOException{
		var mapping = getMapping(index);
		if(mapping == null){
			mapping = makeMapping(index);
		}
		var res = consumer.apply(mapping);
		if(DEBUG_VALIDATION && res == mapping) throw new IllegalStateException("Do not leak the MappedByteBuffer");
		return res;
	}
	
	////////////////////////
	
	private MappedByteBuffer mapIdx(long index) throws IOException{
		var chunkStart = index*MAX_CHUNK_SIZE;
		var remaining  = (int)Math.min(size - chunkStart, MAX_CHUNK_SIZE);
		
		return map(chunkStart, remaining);
	}
	private MappedByteBuffer map(long pos, int len) throws IOException{
		var mode = readOnly? FileChannel.MapMode.READ_ONLY : FileChannel.MapMode.READ_WRITE;
		return fileChannel.map(mode, pos, len);
	}
	private static void unmap(MappedByteBuffer value){
		value.force();
		UNSAFE.invokeCleaner(value);
	}
	
	private void clearMappings(){
		iterateMappings(FileMappings::unmap);
		mappings = new Reference[1];
		largeMappings.clear();
	}
	
	private void iterateMappings(Consumer<MappedByteBuffer> consumer){
		for(var ref : mappings){
			var mapping = unwrap(ref);
			if(mapping != null) consumer.accept(mapping);
		}
		for(var ref : largeMappings.values()){
			var mapping = unwrap(ref);
			if(mapping != null) consumer.accept(mapping);
		}
	}
	
	private static MappedByteBuffer unwrap(Reference<MappedByteBuffer> wrapped){
		return wrapped == null? null : wrapped.get();
	}
	private static Reference<MappedByteBuffer> wrap(MappedByteBuffer raw){
		return new WeakReference<>(raw);
	}
}
