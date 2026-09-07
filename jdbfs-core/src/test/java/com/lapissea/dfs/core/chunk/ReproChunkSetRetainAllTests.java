package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.objects.ChunkPointer;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * BUG-23 (chunkset-retainall)
 *
 * What the code is supposed to do:
 *   ChunkSet implements java.util.Set&lt;ChunkPointer&gt;, so Collection#retainAll
 *   semantics apply: retain only the elements present in the argument and
 *   RETURN. A non-empty set retained against a non-empty DISJOINT collection
 *   must simply become EMPTY (like any other Set implementation).
 *
 * What it actually does:
 *   ChunkSet.retainAll (ChunkSet.java:354-355) runs index.and(toRetain), which
 *   leaves the backing RoaringBitmap EMPTY when nothing matches, and then
 *   unconditionally calls recalcInfo() (ChunkSet.java:329-333). recalcInfo()
 *   calls calcStart() (ChunkSet.java:440-442) which delegates to
 *   Bitmap32.calcStart (ChunkSet.java:98-100) — a bare
 *   {@code data.getIntIterator().next()} with NO hasNext() guard — so on the
 *   now-empty bitmap it throws an unchecked exception from inside the
 *   RoaringBitmap iterator (roaringbitmap 1.2.2: NullPointerException because
 *   the empty bitmap's internal iterator state is null; other versions:
 *   java.util.NoSuchElementException). The set is left in an inconsistent
 *   state and the caller never sees the "empty set" result. (Note: removeAll,
 *   ChunkSet.java:360-376, guards this with index.isEmpty() before
 *   recalcInfo(); retainAll has no such guard.)
 *
 * Why the test fails:
 *   retainAllDisjointMustEmptySet drives exactly that path (non-empty set +
 *   disjoint retainAll) and the exception escapes retainAll
 *   (ChunkSet.java:355 -> :330 -> :99), so the "set is empty afterwards"
 *   assertions are never reached. The control test proves the harness itself
 *   is sound: with an overlapping collection the bitmap stays non-empty,
 *   next() succeeds, and no exception is thrown.
 */
@SuppressWarnings("Convert2MethodRef")
public class ReproChunkSetRetainAllTests{
	@Test
	public void retainAllDisjointMustEmptySet(){
		var set = new ChunkSet(10L, 20L, 30L);
		assertFalse(set.isEmpty());
		assertEquals(set.size(), 3);
		
		// Disjoint collection: none of {10,20,30} survives.
		// BUG: this call throws from the unguarded next() on the now-empty
		// bitmap (ChunkSet.java:355 -> recalcInfo :330 -> Bitmap32.calcStart
		// :98-100) instead of clearing the set and returning true.
		boolean changed = set.retainAll(List.of(ChunkPointer.of(100L)));
		
		// Set contract: disjoint retainAll must leave the set EMPTY.
		assertTrue(changed, "disjoint retainAll must report a change (bug: exception thrown from empty-bitmap next() instead)");
		// BUG: unreachable — exception from empty-bitmap next() above. Expected: set emptied.
		assertTrue(set.isEmpty(), "set must be empty after a disjoint retainAll");
		assertEquals(set.size(), 0, "size must be 0 after a disjoint retainAll");
		assertEquals(new ArrayList<>(set).size(), 0, "iteration must yield no elements after a disjoint retainAll");
	}
	
	@Test
	public void controlRetainAllWithOverlapDoesNotThrow(){
		var set = new ChunkSet(10L, 20L, 30L);
		// Overlapping collection: the AND keeps {20}, so the bitmap is
		// non-empty and the unguarded next() in calcStart succeeds. This
		// proves the harness works and the failure in the test above is
		// specific to the empty-result path of retainAll.
		boolean changed = set.retainAll(List.of(ChunkPointer.of(20L), ChunkPointer.of(100L)));
		assertTrue(changed);
		assertEquals(new ArrayList<>(set), List.of(ChunkPointer.of(20L)));
	}
}
