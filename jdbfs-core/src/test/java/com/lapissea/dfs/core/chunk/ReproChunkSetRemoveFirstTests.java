package com.lapissea.dfs.core.chunk;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * BUG-24 (chunkset-removefirst): ChunkSet.removeFirst()/removeLast() cannot handle chunk
 * offsets above 2 GB.
 *
 * What the code is supposed to do:
 *   ChunkSet indexes chunk offsets (longs) in a Roaring bitmap. Offsets are longs and the
 *   64-bit index variant fully supports values above Integer.MAX_VALUE: add(long) selects
 *   Index.Bitmap64 when ptr > Integer.MAX_VALUE (ChunkSet.java:389), and the Index
 *   interface's contains(long)/remove(long) already take longs (ChunkSet.java:177-178).
 *   removeFirst() (ChunkSet.java:444) should simply remove the smallest offset, and
 *   removeLast() (ChunkSet.java:457) the largest.
 *
 * What it actually does:
 *   removeFirst() funnels the first offset through Math.toIntExact(start) before calling
 *   index.contains/remove (ChunkSet.java:449-450), and removeLast() does the same with
 *   lastIndex() (ChunkSet.java:462-463). When the smallest (resp. largest) chunk offset
 *   exceeds Integer.MAX_VALUE (i.e. files larger than 2 GB), Math.toIntExact throws
 *   ArithmeticException("... overflow") instead of removing the element — even though the
 *   underlying 64-bit bitmap could store and remove that offset without any int cast.
 *   This is actively reachable: DefragmentManager.findFreeChunks (:446/:460) calls
 *   removeFirst()/removeLast() on ChunkSets of free chunk pointers in >2 GB files.
 *
 * Why the test fails:
 *   The tests below assert the CORRECT post-removal state. With the bug present, the
 *   removeFirst()/removeLast() call itself throws ArithmeticException before any
 *   assertion, so the test fails with that exception — demonstrating the reported
 *   mechanism. The control test (offsets that fit in an int) passes, proving the harness
 *   is sound and the failure is specific to offsets above Integer.MAX_VALUE.
 */
public class ReproChunkSetRemoveFirstTests {
	
	private static final long GB2 = 0x2_0000_0000L; // 2^31 — first offset above Integer.MAX_VALUE
	
	@Test
	public void removeFirst_minAboveIntMax_removesFirstElement(){
		// Both elements are above 2 GB, so min() == GB2 > Integer.MAX_VALUE and the
		// internal index is the 64-bit variant (ChunkSet.java:389), which can already
		// store and remove these offsets without any int conversion.
		var set = new ChunkSet(GB2, GB2 + 16);
		assertEquals(set.min(), GB2, "sanity: smallest offset is above Integer.MAX_VALUE");
		assertEquals(set.trueSize(), 2, "sanity: both pointers were added");
		
		// BUG: ChunkSet.java:449-450 runs Math.toIntExact(start) with start == GB2,
		// which overflows and throws ArithmeticException. Correct behavior would remove
		// the first element, leaving min() == GB2+16 and one element in the set.
		set.removeFirst();
		// Key assertion: only reachable if removeFirst() did NOT overflow.
		assertEquals(set.min(), GB2 + 16, "after removeFirst the smallest offset must advance to the next element");
		assertEquals(set.trueSize(), 1, "after removeFirst exactly one element must remain");
		assertTrue(set.contains(GB2 + 16), "the surviving element must still be contained");
	}
	
	@Test
	public void removeLast_maxAboveIntMax_removesLastElement(){
		// Small first offset (so removeFirst would work) + last offset above 2 GB,
		// isolating the removeLast() variant of the same defect (ChunkSet.java:462-463).
		var set = new ChunkSet(100L, GB2);
		assertEquals(set.max(), GB2, "sanity: largest offset is above Integer.MAX_VALUE");
		assertEquals(set.trueSize(), 2, "sanity: both pointers were added");
		
		// BUG: ChunkSet.java:462-463 runs Math.toIntExact(lastIndex()) with lastIndex() == GB2,
		// which overflows and throws ArithmeticException. Correct behavior would remove the
		// last element, leaving max() == 100 and one element in the set.
		set.removeLast();
		// Key assertion: only reachable if removeLast() did NOT overflow.
		assertEquals(set.max(), 100L, "after removeLast the largest offset must fall back to the previous element");
		assertEquals(set.trueSize(), 1, "after removeLast exactly one element must remain");
		assertTrue(set.contains(100L), "the surviving element must still be contained");
	}
	
	@Test
	public void control_removeFirst_belowIntMax_works(){
		// Control: with offsets that fit in an int, removeFirst/removeLast work.
		// If this failed, the harness itself would be at fault, not the >2 GB path.
		var set = new ChunkSet(100L, 116L);
		set.removeFirst();
		assertEquals(set.min(), 116L, "removeFirst removes 100, leaving 116");
		set.removeLast();
		assertEquals(set.trueSize(), 0, "removeLast removes the only remaining element");
		assertTrue(set.isEmpty(), "set must be empty after removing both elements");
	}
}
