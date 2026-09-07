package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import com.lapissea.util.MathUtil;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * BUG-46 (freada-intcast).
 *
 * What the code is supposed to do:
 *   {@link FileRandomAccessData#readInto(File, IOInterface)} streams an entire file into an
 *   {@link IOInterface} destination using a batch buffer sized between 16 bytes and
 *   {@link GlobalConfig#BATCH_BYTES} (8192): a large file should be copied in BATCH_BYTES-sized
 *   reads/writes.
 *
 * What it actually does:
 *   FileRandomAccessData.java:32 builds the buffer as
 *       new byte[MathUtil.snap((int)file.length(), 16, BATCH_BYTES)]
 *   The long file length is cast to int before sizing the buffer. For any file whose length is
 *   >= 2^31 (2 GiB) the cast overflows: for a 2^31+1 byte file (int)file.length() is
 *   -2147483647, and MathUtil.snap clamps that negative value to the 16-byte minimum. The copy
 *   therefore proceeds in 16-byte batches instead of BATCH_BYTES (other overflowing lengths can
 *   even yield NegativeArraySizeException / arbitrarily tiny buffers, per the bug report).
 *
 * Why the test fails:
 *   The test creates a 2^31+1 byte sparse file (RandomAccessFile.setLength - costs ~0 disk),
 *   streams it through readInto() into an instrumented destination that records the size of
 *   every write() call, and asserts the first delivered chunk is BATCH_BYTES (a regular-file
 *   read() fills the whole buffer). With the bug present the first chunk is 16 bytes, so the
 *   assertion fails. The destination aborts the copy after a few chunks so the test never
 *   actually reads the 2 GiB file (runtime stays well under a second).
 */
public class ReproFReadaIntCastTests{
	
	private static final long SPARSE_LEN = 2L*1024*1024*1024 + 1; // 2^31 + 1: just past the int boundary
	private static final int  STOP_AFTER = 4;                    // abort readInto after this many write() calls
	
	/** Raised by the instrumented destination to abort the copy early. */
	private static final class StopReadingException extends IOException{
		private StopReadingException(){
			super("readInto aborted by test after " + STOP_AFTER + " write() calls");
		}
	}
	
	/**
	 * Destination that records the size of every write(byte[], int, int) call made by
	 * readInto() and aborts the copy after STOP_AFTER calls, so the 2 GiB file is never
	 * read in full.
	 */
	private static final class CountingDest implements IOInterface{
		
		final List<Integer> writeSizes = new ArrayList<>();
		
		@Override
		public boolean isReadOnly(){
			return false;
		}
		
		@Override
		public IOTransaction openIOTransaction(){
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
					if(writes >= STOP_AFTER){
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
	
	@Test
	public void readIntoSizesBatchBufferFromOverflowedIntCast() throws IOException{
		var dir  = Files.createTempDirectory("freada-intcast");
		var path = dir.resolve("sparse.bin");
		
		// Sparse file of 2^31+1 bytes (setLength costs ~0 disk; the copy is aborted after a few KB).
		try(var raf = new RandomAccessFile(path.toFile(), "rw")){
			raf.setLength(SPARSE_LEN);
		}
		
		try{
			var file = path.toFile();
			assertThat(file.length()).as("sparse file length").isEqualTo(SPARSE_LEN);
			
			// The production expression at FileRandomAccessData.java:32:
			//     MathUtil.snap((int)file.length(), 16, BATCH_BYTES)
			// For this file length the (int) cast overflows to a negative value ...
			int castLen = (int)file.length();
			assertThat(castLen)
			    .as("(int)file.length() overflows for a >2 GiB file")
			    .isNegative();
			// ... and snap() clamps it to the 16-byte minimum instead of BATCH_BYTES:
			assertThat(MathUtil.snap(castLen, 16, GlobalConfig.BATCH_BYTES))
			    .as("snap() of the overflowed cast yields the 16-byte minimum, not BATCH_BYTES")
			    .isEqualTo(16);
			
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
			
			// THE BUG: readInto() sized its batch buffer from the overflowed (int) cast, which
			// snap() clamped to 16 bytes (FileRandomAccessData.java:32), so every chunk it
			// delivers is 16 bytes. With a correctly sized buffer the first read() of a regular
			// file fills the whole BATCH_BYTES buffer, so this assertion fails only when the
			// int overflow at FileRandomAccessData.java:32 is present.
			int first = dest.writeSizes.get(0);
			assertThat(first)
			    .as("first batch delivered by readInto() for a >2 GiB file must be BATCH_BYTES "
			        + "(bug: (int)file.length() overflows and snap() shrinks the buffer to 16 bytes)")
			    .isEqualTo(GlobalConfig.BATCH_BYTES);
		}finally{
			Files.deleteIfExists(path);
			Files.deleteIfExists(dir);
		}
	}
	
}
