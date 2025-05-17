package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.ReadWriteClosableLock;
import com.lapissea.util.UtilL;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

@SuppressWarnings("unchecked")
final class FileMappings implements Closeable{
	
	private static final Cleaner         CLEANER      = Cleaner.create();
	private static final ExecutorService CLEAN_WORKER = Executors.newVirtualThreadPerTaskExecutor();
	
	public static final int MAX_CHUNK_SIZE    = 128*1024*1024;
	public static final int MAX_SMALL_MAPPING = 32;
	
	private static void gcRegister(Object object, Runnable onClean){
		CLEANER.register(object, () -> CLEAN_WORKER.execute(onClean));
	}
	
	private final FileChannel fileChannel;
	private       boolean     closed;
	
	private final boolean readOnly;
	private       long    size;
	
	private static final class AllocatedMapping implements AutoCloseable{
		private final MemorySegment segment;
		private final ByteBuffer    bb;
		
		
		private final Runnable closer;
		
		private AllocatedMapping(Arena arena, MemorySegment segment){
			this.segment = segment;
			bb = segment.asByteBuffer();
			
			gcRegister(this, closer = new Runnable(){
				private boolean closed;
				@Override
				public synchronized void run(){
					if(closed) return;
					try{
						segment.force();
						segment.unload();
					}catch(UncheckedIOException e){
						if(!tryFlush(segment)){
							throw e;
						}
					}
					arena.close();
					closed = true;
				}
			});
		}
		
		@Override
		public String toString(){
			return segment.toString();
		}
		@Override
		public void close(){
			closer.run();
		}
		
		private static boolean tryFlush(MemorySegment segment){
			for(int attempts = 0; attempts<15; attempts++){
				try{
					segment.force();
					segment.unload();
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
	}
	
	private       Reference<AllocatedMapping>[]          mappings = new Reference[1];
	private       Map<Long, Reference<AllocatedMapping>> largeMappings;//null by default as most of the time it will never be used
	private final ReadWriteClosableLock                  lock     = ReadWriteClosableLock.reentrant();
	
	FileMappings(FileChannel fileChannel, boolean readOnly) throws IOException{
		this.fileChannel = fileChannel;
		this.readOnly = readOnly;
		size = fileChannel.size();
		
		gcRegister(this, () -> {
			try{
				fileChannel.close();
			}catch(IOException e){
				//Nothing to do
			}
		});
	}
	
	private void checkClosed() throws IOException{
		if(closed) throw new ClosedChannelException();
	}
	
	private Reference<AllocatedMapping> removeLargeMapping(long index){
		var lm = largeMappings;
		return lm == null? null : lm.remove(index);
	}
	private Reference<AllocatedMapping> getLargeMapping(long index){
		var lm = largeMappings;
		return lm == null? null : lm.get(index);
	}
	private void putLargeMapping(long index, Reference<AllocatedMapping> ref){
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
			closeSync();
		}
	}
	private void closeSync() throws IOException{
		checkClosed();
		closed = true;
		clearMappings();
		fileChannel.close();
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
			runIfSome(mappings[(int)index], AllocatedMapping::close);
			mappings[(int)index] = null;
		}else{
			runIfSome(removeLargeMapping(index), AllocatedMapping::close);
		}
	}
	
	private AllocatedMapping makeMapping(long index) throws IOException{
		try(var ignore = lock.write()){
			checkClosed();
			if(index<MAX_SMALL_MAPPING) return makeSmallMapping((int)index);
			return makeLargeMapping(index);
		}
	}
	private AllocatedMapping makeSmallMapping(int index) throws IOException{
		if(mappings.length>index){
			var mapping = unwrap(mappings[index]);
			if(mapping != null){
				return mapping;
			}
		}else{
			mappings = Arrays.copyOf(mappings, index + 1);
		}
		var val = mapIdx(index);
		mappings[index] = wrap(val);
		return val;
	}
	private AllocatedMapping makeLargeMapping(long index) throws IOException{
		var mapping = unwrap(getLargeMapping(index));
		if(mapping == null){
			var ref = wrap(mapping = mapIdx(index));
			putLargeMapping(index, ref);
		}
		return mapping;
	}
	
	private AllocatedMapping getMapping(long index){
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
	
	public <T> T onMapping(long index, Function<ByteBuffer, T> consumer) throws IOException{
		checkClosed();
		var mapping = getMapping(index);
		if(mapping == null){
			mapping = makeMapping(index);
		}
		var res = consumer.apply(mapping.bb);
		if(DEBUG_VALIDATION && res == mapping) throw new IllegalStateException("Do not leak the MappedByteBuffer");
		return res;
	}
	
	/// /////////////////////
	
	private AllocatedMapping mapIdx(long index) throws IOException{
		var chunkStart = index*MAX_CHUNK_SIZE;
		var remaining  = (int)Math.min(size - chunkStart, MAX_CHUNK_SIZE);
		
		return map(chunkStart, remaining);
	}
	private AllocatedMapping map(long pos, int len) throws IOException{
		var mode   = readOnly? FileChannel.MapMode.READ_ONLY : FileChannel.MapMode.READ_WRITE;
		var arena  = Arena.ofShared();
		var mapped = fileChannel.map(mode, pos, len, arena);
		return new AllocatedMapping(arena, mapped);
	}
	
	private void clearMappings(){
		iterateMappings(AllocatedMapping::close);
		mappings = new Reference[1];
		largeMappings = null;
	}
	
	private void iterateMappings(Consumer<AllocatedMapping> consumer){
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
	
	private static void runIfSome(Reference<AllocatedMapping> wrapped, Consumer<AllocatedMapping> consumer){
		if(wrapped == null) return;
		var val = wrapped.get();
		if(val == null) return;
		consumer.accept(val);
	}
	private static AllocatedMapping unwrap(Reference<AllocatedMapping> wrapped){
		return wrapped == null? null : wrapped.get();
	}
	private static Reference<AllocatedMapping> wrap(AllocatedMapping raw){
		return new WeakReference<>(raw);
	}
}
