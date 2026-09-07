package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-27 (chunk-zeroout) — Chunk.zeroOutFromTo bounds the wrong value.
 *
 * What the code is supposed to do:
 *   {@link Chunk#zeroOutFromTo(long, long)} zeroes the body range [from, to) of ONE chunk and
 *   must reject any range that extends past the chunk's own body, i.e. the guard must be
 *   {@code to > getCapacity()}.
 *
 * What it actually does (jdbfs-core/src/main/java/com/lapissea/dfs/core/chunk/Chunk.java:707-717):
 *   The guard at Chunk.java:714 checks {@code amount = to - from} against the capacity:
 *   {@code if(amount>getCapacity()) throw ...}. A call with `from>0` and `to` past the chunk end
 *   (amount <= capacity) passes the guard, and the write at Chunk.java:716
 *   {@code getSource().ioAt(dataStart() + from, io -> io.fillZero(amount))} then fills zero bytes
 *   beyond {@code dataEnd()}, silently clobbering the physically adjacent next chunk's header.
 *
 * Why this test fails:
 *   Two physically adjacent chunks are built on a MemoryData-backed provider:
 *     A: capacity 16, 4-byte header (flags + 1B capacity + 1B size + 1B nextPtr), body = [ptrA+4, ptrA+20)
 *     B: pointer == A.dataEnd(), capacity 16, 3-byte header (nextPtr is NULL/VOID), body = [ptrB+3, ptrB+19)
 *   The call {@code A.zeroOutFromTo(8, 24)} has amount == 16 (== capacity, passes the buggy guard)
 *   but to == 24 > 16. The zero-fill therefore covers [dataStartA+8, dataStartA+24): 8 bytes inside
 *   A's body plus 8 bytes past A.dataEnd() == B's whole 3-byte header plus 5 bytes of B's body.
 *   After the call B's flags/capacity/size/nextPtr bytes are all zero,
 *   so {@link Chunk#isChunkValidAt} reports B as invalid and B can no longer be re-read with its
 *   original size/capacity — the assertions below tie to exactly that corruption.
 *   If the guard were correct (to > capacity), the call would throw an "Overflow" IOException
 *   before any write, and the test would pass.
 */
public class ReproChunkZeroOutTests{
	
	private static final int MAGIC = 7; // MagicID.size() ("BYT-BAE")
	
	/**
	 * Zeroes B's header by abusing A's buggy bounds check.
	 * @return true if the (correct) code rejected the out-of-range call with an overflow IOException
	 */
	private static boolean zeroOutPastEnd(Chunk a) throws IOException{
		try{
			// to == 24 > A's capacity 16, but amount == 16 passes the `amount > getCapacity()` guard at Chunk.java:714
			a.zeroOutFromTo(8, 24);
			return false;
		}catch(IOException e){
			// the only legitimate rejection for this call is the overflow guard; anything else is a harness problem
			assertThat(e).as("unexpected IOException from zeroOutFromTo").hasMessageContaining("Overflow");
			return true;
		}
	}
	
	/**
	 * Builds A (capacity 16, nextPtr -> B) and B (capacity 16, physically right after A: B.ptr == A.dataEnd()).
	 * Both headers are written to the MemoryData source.
	 */
	private static Chunk[] adjacentChunks(DataProvider provider) throws IOException{
		var ptrA   = MAGIC;
		var headerA = 1 + NumberSize.BYTE.bytes*2 + NumberSize.BYTE.bytes; // flags + 1B cap + 1B size + 1B nextPtr
		var ptrB   = ptrA + headerA + 16;
		
		var a = new ChunkBuilder(provider, ChunkPointer.of(ptrA))
		      .withCapacity(16)
		      .withSize(16)
		      .withExplicitNextSize(NumberSize.BYTE)
		      .withNext(ChunkPointer.of(ptrB))
		      .create();
		a.writeHeader();
		
		var b = new ChunkBuilder(provider, ChunkPointer.of(ptrB))
		      .withCapacity(16)
		      .withSize(16)
		      .create();
		b.writeHeader();
		
		assertThat(a.getHeaderSize()).isEqualTo(headerA);
		assertThat(a.dataEnd()).as("chunks must be physically adjacent: B's header starts exactly at A's data end").isEqualTo(ptrB);
		return new Chunk[]{a, b};
	}
	
	@Test
	public void zeroOutPastCapacityMustNotCorruptNextChunkHeader() throws IOException{
		var provider = DataProvider.newVerySimpleProvider(MemoryData.builder().withCapacity(128).build());
		var chunks   = adjacentChunks(provider);
		var a        = chunks[0];
		var ptrB     = chunks[1].getPtr();
		
		// baseline: the next chunk's header on disk is a valid chunk header
		assertThat(Chunk.isChunkValidAt(provider, ptrB)).as("baseline: next chunk header is valid before the zero-out").isTrue();
		
		if(zeroOutPastEnd(a)) return; // correct code rejected the out-of-range zero-out before writing anything
		
		// BUG: the call was not rejected and zeroed 8 bytes past a.dataEnd() == ptrB,
		// wiping B's entire 3-byte header (flags, capacity, size, nextPtr).
		// Key assertion: B's header must still be a valid chunk header after the call:
		assertThat(Chunk.isChunkValidAt(provider, ptrB))
		       .as("next chunk's header must be intact: zeroOutFromTo(8,24) on a 16-capacity chunk must not write past the chunk end")
		       .isTrue();
	}
	
	@Test
	public void nextChunkSizeAndContentSurviveBuggyZeroOut() throws IOException{
		var provider = DataProvider.newVerySimpleProvider(MemoryData.builder().withCapacity(128).build());
		var chunks   = adjacentChunks(provider);
		var a        = chunks[0];
		var ptrB     = chunks[1].getPtr();
		
		if(zeroOutPastEnd(a)) return; // correct code rejected the out-of-range zero-out before writing anything
		
		// BUG: with B's header zeroed, re-reading B either fails outright (bit integrity check on the
		// zeroed flags byte -> MalformedPointer) or yields a chunk whose capacity/size no longer match
		// the 16/16 that were written — both prove the neighbor was corrupted.
		var reRead = Chunk.readChunk(provider, ptrB);
		assertThat(reRead.getCapacity()).as("next chunk's capacity must survive the neighbor's zero-out").isEqualTo(16);
		assertThat(reRead.getSize()).as("next chunk's size must survive the neighbor's zero-out").isEqualTo(16);
	}
	
}
