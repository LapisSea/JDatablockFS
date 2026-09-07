package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.io.impl.MemoryData;
import org.assertj.core.api.Assertions;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * BUG-52 (chainio-fillzero): {@link com.lapissea.dfs.core.chunk.ChunkChainIO#fillZero(long)}
 * measures every chunk by its SIZE instead of its CAPACITY.
 *
 * Contract of {@code RandomIO.fillZero} (RandomIO.java:393-398): "Similar to the write methods
 * except it writes some number of 0 bytes but does not modify things such as the size of the data
 * (useful for clearing garbage data after some data has been shrunk)". The sibling implementations
 * zero the requested bytes at the current position regardless of size
 * (CursorIOData.java:270-274, Blob.java:188-192, RandomIO.java:593-596) and {@code RangeIO.fillZero}
 * (RangeIO.java:88-92) bounds the request with {@code maxRemaining()} — i.e. CAPACITY, not size.
 * So zero-filling allocated-but-unused capacity (the region exposed by a shrink) is legitimate.
 *
 * ChunkChainIO.fillZero (ChunkChainIO.java:527-552) instead uses {@code getSize()}:
 * <ul>
 *   <li>(a) :532 {@code cRem = chunk.getSize() - offset} is NEGATIVE when the cursor sits past the
 *       (shrunk) chunk size — exactly the documented "clear garbage after shrink" state.
 *       {@code toWrite = Math.min(remaining, cRem)} is then negative and passed to
 *       {@code source.fillZero} → IOUtils.zeroFill (IOUtils.java:51-67) allocates
 *       {@code new byte[negative]} → {@link NegativeArraySizeException}.</li>
 *   <li>(b) :536-550: when the cursor chunk is the last chunk, any {@code remaining} that exceeds
 *       the chain's remaining SIZE is silently discarded (loop exits at {@code chunk == null},
 *       or early return at :541-543) — no exception, no error — so the requested zero-fill is
 *       silently partial and stale (non-zero) bytes remain in allocated capacity.</li>
 * </ul>
 * Both tests below assert the contractual behavior and therefore FAIL on the current code:
 * (a) with NegativeArraySizeException, (b) with stale 0xAB bytes where zeros were requested.
 */
public class ReproChainIoFillZeroTests{
	
	private static final int  DATA = 20;
	private static final byte MARK = (byte)0xAB;
	
	private static byte[] marked(int len){
		var b = new byte[len];
		Arrays.fill(b, MARK);
		return b;
	}
	
	/**
	 * Allocates a single 20-byte chunk filled with 0xAB and verifies the assumed layout.
	 */
	private static Chunk allocate20(Cluster cl) throws IOException{
		var chunk = AllocateTicket.bytes(DATA)
		           .withDataPopulated(c -> c.write(false, marked(DATA)))
		           .submit(cl);
		//Sanity: the scenarios below need one non-chained chunk of exactly 20 bytes with cap >= 20
		Assertions.assertThat(chunk.hasNextPtr()).as("layout: must be a single chunk").isFalse();
		Assertions.assertThat(chunk.getSize()).as("layout: chunk size").isEqualTo(DATA);
		Assertions.assertThat(chunk.getCapacity()).as("layout: chunk capacity").isGreaterThanOrEqualTo(DATA);
		return chunk;
	}
	
	/**
	 * Reads raw source bytes directly from the file, bypassing the chain, so the test can
	 * observe what fillZero actually left in the allocated region.
	 */
	private static byte[] rawRead(Cluster cl, long fileOffset, int len) throws IOException{
		var io  = cl.getSource().ioAt(fileOffset);
		var out = new byte[len];
		Assertions.assertThat(io.read(out, 0, len)).as("raw read length").isEqualTo(len);
		return out;
	}
	
	/**
	 * BUG-52 (a): after shrinking the chain while the cursor is past the new size,
	 * fillZero must zero the requested bytes (within capacity) — instead it throws
	 * NegativeArraySizeException because cRem = size - offset is negative (ChunkChainIO.java:532-534).
	 */
	@Test
	public void fillZero_pastShrunkSize_negativeArraySize() throws IOException{
		var cl    = Cluster.init(MemoryData.empty());
		var chunk = allocate20(cl);
		try(var io = chunk.io()){
			io.setPos(DATA - 5);    //cursor at 15, inside the 20 written bytes
			io.setSize(10);         //shrink chain to 10; the cursor (15) is now PAST the chunk size (10)
			
			//The documented use case: clear the garbage now lying past the new size (still in allocated capacity)
			try{
				io.fillZero(5);
			}catch(NegativeArraySizeException e){
				//BUG (a): cRem = getSize(10) - offset(15) = -5 -> toWrite = -5 (ChunkChainIO.java:532-533)
				//-> IOUtils.zeroFill does `new byte[-5]` (IOUtils.java:51-67).
				Assertions.fail(
					"fillZero() threw NegativeArraySizeException: cRem = chunk.getSize() - cursorOffset " +
					"(ChunkChainIO.java:532) is negative when the cursor is past the shrunk size, and " +
					"IOUtils.zeroFill (IOUtils.java:51-67) allocates new byte[negative]; the contract is to " +
					"zero within the chunk capacity without changing the size", e);
			}
			
			//Contractual behavior (passes if the bug is fixed): no exception, size untouched, zeros written
			Assertions.assertThat(io.getSize()).as("fillZero must not change the chain size").isEqualTo(10L);
			Assertions.assertThat(rawRead(cl, chunk.dataStart() + (DATA - 5), 5))
			          .as("bytes [15,20) are within the chunk capacity and must have been zeroed")
			          .containsOnly((byte)0);
		}
	}
	
	/**
	 * BUG-52 (b): fillZero on a chain whose remaining SIZE is shorter than the request silently
	 * drops the unfulfilled remainder (ChunkChainIO.java:536-550), leaving stale non-zero bytes
	 * in allocated capacity where zeros were requested.
	 */
	@Test
	public void fillZero_pastChainEnd_silentlyPartial() throws IOException{
		var cl    = Cluster.init(MemoryData.empty());
		var chunk = allocate20(cl);
		try(var io = chunk.io()){
			io.setPos(10);       //cursor at 10
			io.setSize(10);      //shrink: bytes [10,20) become stale garbage inside allocated capacity
			//(setSize only clamps the size — Chunk.clampSize (Chunk.java:385-390) does NOT zero the tail)
			
			//Request to clear the 10 stale bytes [10,20) — all inside the chunk's capacity
			io.fillZero(10);
			//fillZero returned without error ...
			
			//BUG (b): cRem = size(10) - offset(10) = 0 -> toWrite 0, then chunk.next() == null, so the
			//remaining 10 bytes are silently discarded (ChunkChainIO.java:536-550) and stale 0xAB survive.
			Assertions.assertThat(rawRead(cl, chunk.dataStart() + 10, 10))
			          .as("fillZero(10) must zero the 10 bytes at the cursor even past the chain size " +
			              "(they are within capacity); stale 0xAB bytes remain because the unfulfilled " +
			              "remainder is silently dropped (ChunkChainIO.java:536-550)")
			          .containsOnly((byte)0);
		}
	}
}
