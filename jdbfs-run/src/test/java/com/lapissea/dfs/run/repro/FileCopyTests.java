package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class FileCopyTests{
	
	private static final int STOP_AFTER = 4;                    // abort readInto after this many write() calls
	
	
	private static final class StopReadingException extends IOException{
		private StopReadingException(){
			super("readInto aborted by test after " + STOP_AFTER + " write() calls");
		}
	}
	
	
	private static final class CountingDest implements IOInterface{
		
		final List<Integer> writeSizes = new ArrayList<>();
		
		@Override
		public boolean isReadOnly(){
			return false;
		}
		
		@Override
		public IOTransaction startTransaction(){
			return IOTransaction.NOOP;
		}
		
		@Override
		public RandomIO io(){
			return new RandomIO(){
				private long pos;
				private long size;
				private long capacity;
				private int  writes;
				
				@Override
				public void write(int b){
					throw new UnsupportedOperationException();
				}
				
				@Override
				public void write(byte[] b, int off, int len) throws IOException{
					writes++;
					writeSizes.add(len);
					pos += len;
					size = Math.max(size, pos);
					capacity = Math.max(capacity, pos);
					if(writes>=STOP_AFTER){
						// intended early abort: a full 2 GiB copy would take far too long
						throw new StopReadingException();
					}
				}
				
				@Override
				public int read(){
					return -1;
				}
				
				@Override
				public int read(byte[] b, int off, int len){
					return -1;
				}
				
				@Override
				public long skip(long toSkip){
					pos = Math.min(size, pos + toSkip);
					return toSkip;
				}
				
				@Override
				public void setSize(long requestedSize){
					size = requestedSize;
				}
				
				@Override
				public long getSize(){
					return size;
				}
				
				@Override
				public long getPos(){
					return pos;
				}
				
				@Override
				public RandomIO setPos(long p){
					pos = p;
					return this;
				}
				
				@Override
				public long getCapacity(){
					return capacity;
				}
				
				@Override
				public RandomIO setCapacity(long newCapacity){
					capacity = newCapacity;
					return this;
				}
				
				@Override
				public void writeAtOffsets(Collection<WriteChunk> data){
					throw new UnsupportedOperationException();
				}
				
				@Override
				public void fillZero(long requestedMemory){
					throw new UnsupportedOperationException();
				}
				
				@Override
				public boolean isReadOnly(){
					return false;
				}
				
				@Override
				public void flush(){
				}
				
				@Override
				public void close(){
				}
			};
		}
	}
	
	@DataProvider
	public Object[][] largeLengths(){
		return new Object[][]{{(long)Integer.MAX_VALUE}, {1L<<31}, {(1L<<31) + 1}, {(1L<<32) + 1}};
	}
	
	@Test(dataProvider = "largeLengths")
	public void largeFileUsesFullBatches(long length) throws IOException{
		var dir  = Files.createTempDirectory("file-copy");
		var path = dir.resolve("sparse.bin");
		
		try{
			// A sparse file exercises long lengths while the destination stops after a few batches.
			try(var raf = new RandomAccessFile(path.toFile(), "rw")){
				raf.setLength(length);
			}
			var file = path.toFile();
			assertThat(file.length()).as("sparse file length").isEqualTo(length);
			
			var dest = new CountingDest();
			try{
				FileRandomAccessData.readInto(file, dest);
				fail("expected StopReadingException to abort the copy early");
			}catch(StopReadingException expectedAbort){
				// intended: the destination refuses to absorb the whole 2 GiB
			}
			
			assertThat(dest.writeSizes)
				.as("readInto should have delivered chunks before the abort")
				.isNotEmpty();
			
			assertThat(dest.writeSizes).as("copy batches before early abort")
			                           .hasSize(STOP_AFTER).containsOnly(GlobalConfig.BATCH_BYTES);
		}finally{
			Files.deleteIfExists(path);
			Files.deleteIfExists(dir);
		}
	}
	
}
