package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.ReadWriteClosableLock;
import com.lapissea.util.UtilL;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.internal.MyUnsafe.UNSAFE;

@SuppressWarnings("unchecked")
final class FileMappings implements Closeable{
	
	public static final int MAX_CHUNK_SIZE    = 128*1024*1024;
	public static final int MAX_SMALL_MAPPING = 32;
	
	private final FileChannel fileChannel;
	private       boolean     closed;
	
	private final boolean readOnly;
	private       long    size;
	
	private       Reference<MappedByteBuffer>[]          mappings = new Reference[1];
	private       Map<Long, Reference<MappedByteBuffer>> largeMappings;//null by default as most of the time it will never be used
	private final ReadWriteClosableLock                  lock     = ReadWriteClosableLock.reentrant();
	
	FileMappings(FileChannel fileChannel, boolean readOnly) throws IOException{
		this.fileChannel = fileChannel;
		this.readOnly = readOnly;
		size = fileChannel.size();
	}
	
	private void checkClosed() throws IOException{
		if(closed) throw new ClosedChannelException();
	}
	
	private Reference<MappedByteBuffer> removeLargeMapping(long index){
		var lm = largeMappings;
		return lm == null? null : lm.remove(index);
	}
	private Reference<MappedByteBuffer> getLargeMapping(long index){
		var lm = largeMappings;
		return lm == null? null : lm.get(index);
	}
	private void putLargeMapping(long index, Reference<MappedByteBuffer> ref){
		var lm = largeMappings;
		if(lm == null) lm = largeMappings = new HashMap<>();
		lm.put(index, ref);
	}
	
	public long fileSize(){
		return size;
	}
	
	@Override
	public void close() throws IOException{
		try(var ignore = lock.write()){
			checkClosed();
			closed = true;
			clearMappings();
			fileChannel.close();
		}
	}
	
	private final ByteBuffer zero = ByteBuffer.wrap(new byte[]{0});
	
	public void resize(long newSize) throws IOException{
		try(var ignore = lock.write()){
			checkClosed();
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
		}
	}
	
	
	private void removeMapping(long index){
		if(index<mappings.length){
			runIfSome(mappings[(int)index], FileMappings::unmap);
			mappings[(int)index] = null;
		}else{
			runIfSome(removeLargeMapping(index), FileMappings::unmap);
		}
	}
	
	private MappedByteBuffer makeMapping(long index) throws IOException{
		try(var ignore = lock.write()){
			checkClosed();
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
			
			var mapping = unwrap(getLargeMapping(index));
			if(mapping == null){
				var ref = wrap(mapping = mapIdx(index));
				putLargeMapping(index, ref);
			}
			return mapping;
		}
	}
	private MappedByteBuffer getMapping(long index){
		try(var ignore = lock.read()){
			if(index<MAX_SMALL_MAPPING){
				var iIndex = (int)index;
				if(mappings.length<=iIndex){
					return null;
				}
				return unwrap(mappings[iIndex]);
			}
			
			return unwrap(getLargeMapping(index));
		}
	}
	
	public <T> T onMapping(long index, Function<MappedByteBuffer, T> consumer) throws IOException{
		checkClosed();
		var mapping = getMapping(index);
		if(mapping == null){
			mapping = makeMapping(index);
		}
		var res = consumer.apply(mapping);
		if(DEBUG_VALIDATION && res == mapping) throw new IllegalStateException("Do not leak the MappedByteBuffer");
		return res;
	}
	
	/// /////////////////////
	
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
		try{
			value.force();
		}catch(UncheckedIOException e){
			if(!tryFlush(value)){
				throw e;
			}
		}
		UNSAFE.invokeCleaner(value);
	}
	
	private static boolean tryFlush(MappedByteBuffer value){
		for(int attempts = 0; attempts<15; attempts++){
			try{
				value.force();
				return true;
			}catch(UncheckedIOException e){
				if(e.getMessage().contains("Insufficient system resources")){
					if(attempts>1) Log.warn("Failing mapped file flushing! Attempt {}/15, Reason: {}", attempts, e.getCause().getMessage());
					UtilL.sleep(attempts*attempts*10L);
				}else{
					throw e;
				}
			}
		}
		return false;
	}
	
	private void clearMappings(){
		iterateMappings(FileMappings::unmap);
		mappings = new Reference[1];
		largeMappings = null;
	}
	
	private void iterateMappings(Consumer<MappedByteBuffer> consumer){
		for(var ref : mappings){
			runIfSome(ref, consumer);
		}
		var lm = largeMappings;
		if(lm != null){
			for(var ref : lm.values()){
				runIfSome(ref, consumer);
			}
		}
	}
	
	private static void runIfSome(Reference<MappedByteBuffer> wrapped, Consumer<MappedByteBuffer> consumer){
		if(wrapped == null) return;
		var val = wrapped.get();
		if(val == null) return;
		consumer.accept(val);
	}
	private static MappedByteBuffer unwrap(Reference<MappedByteBuffer> wrapped){
		return wrapped == null? null : wrapped.get();
	}
	private static Reference<MappedByteBuffer> wrap(MappedByteBuffer raw){
		return new WeakReference<>(raw);
	}
}
