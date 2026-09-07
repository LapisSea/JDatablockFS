package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.objects.ChunkPointer;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * BUG-25 (chunkset-cce): ClassCastException when bulk-merging a >2 GB pointer
 * into a ChunkSet whose index is still the 32-bit variant.
 *
 * What the code is supposed to do:
 *   ChunkSet stores chunk offsets in a 32-bit RoaringBitmap (Index.Bitmap32) while all
 *   offsets fit in an int, and upgrades to Index.Bitmap64 when an offset exceeds
 *   Integer.MAX_VALUE (see add(long), ChunkSet.java:388-395). The bulk operations
 *   addAll/retainAll/removeAll build their operand index via ptrsToIndex
 *   (ChunkSet.java:298-321), which is allowed to return a Bitmap64 operand whenever the
 *   incoming collection holds a value > Integer.MAX_VALUE (upgrade at :304-306). A set
 *   must be able to absorb such an operand (upgrading itself to 64-bit if needed).
 *
 * What the code actually does:
 *   The set's own index is never upgraded during a bulk operation, and Bitmap32.or/and/
 *   andNot blindly cast the operand to Bitmap32 (ChunkSet.java:57-70). So merging a
 *   collection containing even one >2 GB pointer into a 32-bit set throws
 *   ClassCastException (class Bitmap64 cannot be cast to class Bitmap32) inside
 *   addAll (:282 index.or(toAdd)), retainAll (:354 index.and(toRetain)) and
 *   removeAll (:367 index.andNot(toRemove)).
 *
 * Why the tests below fail:
 *   Each test builds a set containing ONLY pointers < 2^31, so its internal index is
 *   Bitmap32 (ChunkSet.java:389-390). It then applies one bulk operation with a
 *   collection mixing small pointers and one pointer > 2^31 (BIG). ptrsToIndex
 *   upgrades the OPERAND to Bitmap64 (:304-306), then the cast in Bitmap32.or/and/andNot
 *   (ChunkSet.java:58/63/68) throws ClassCastException before the (correct-behavior)
 *   assertions are ever reached.
 */
public class ReproChunkSetCceTests{
	
	/** 2^33 — comfortably above Integer.MAX_VALUE (2^31-1), i.e. a >2 GB chunk offset. */
	private static final long BIG = 1L << 33;
	
	/**
	 * A set holding only small pointers. Per add(long) ChunkSet.java:388-390 its
	 * internal index is Index.Bitmap32 — the precondition for the bug.
	 */
	private static ChunkSet smallSet(){
		var set = new ChunkSet();
		set.add(100L);
		set.add(200L);
		set.add(300L);
		return set;
	}
	
	@Test
	void retainAllWithLargePointer(){
		var set = smallSet();
		
		// Operand mixes a small ptr (in the set) with a >2 GB ptr:
		// ptrsToIndex (ChunkSet.java:300-310) starts a Bitmap32 and upgrades it to
		// Bitmap64 at :304-306 when it sees BIG.
		set.retainAll(List.of(ChunkPointer.of(200L), ChunkPointer.of(BIG)));
		
		// Correct behavior: intersection with {200, BIG} is {200} — but the bug makes
		// index.and(toRetain) at ChunkSet.java:354 cast the Bitmap64 operand to
		// Bitmap32 (:63) and throw ClassCastException before reaching this point.
		assertTrue(set.contains(200L), "200 is in the retained collection, must survive");
		assertFalse(set.contains(BIG), "BIG was not in the original set, intersection keeps it out");
		assertEquals(set.trueSize(), 1, "only 200 should be retained");
	}
	
	@Test
	void removeAllWithLargePointer(){
		var set = smallSet();
		
		// Same operand upgrade as above: Bitmap32 -> Bitmap64 at ChunkSet.java:304-306.
		set.removeAll(List.of(ChunkPointer.of(200L), ChunkPointer.of(BIG)));
		
		// Correct behavior: {100,200,300} minus {200,BIG} = {100,300} — but the bug
		// makes index.andNot(toRemove) at ChunkSet.java:367 cast the Bitmap64 operand
		// to Bitmap32 (:68) and throw ClassCastException first.
		assertTrue(set.contains(100L), "100 is not removed, must survive");
		assertTrue(set.contains(300L), "300 is not removed, must survive");
		assertFalse(set.contains(200L), "200 is in the removal collection, must go");
		assertEquals(set.trueSize(), 2, "only 100 and 300 should remain");
	}
	
	@Test
	void addAllWithLargePointer(){
		var set = smallSet();
		
		// Same operand upgrade: addAll's ptrsToIndex (ChunkSet.java:273) yields a
		// Bitmap64 once BIG is seen (:304-306).
		set.addAll(List.of(ChunkPointer.of(400L), ChunkPointer.of(BIG)));
		
		// Correct behavior: union with {400, BIG} = {100,200,300,400,BIG} — but the
		// bug makes index.or(toAdd) at ChunkSet.java:282 cast the Bitmap64 operand to
		// Bitmap32 (:58) and throw ClassCastException first.
		assertTrue(set.contains(400L), "400 was added, must be present");
		assertTrue(set.contains(BIG), "BIG was added, must be present");
		assertEquals(set.trueSize(), 5, "3 original + 2 added pointers");
	}
}
