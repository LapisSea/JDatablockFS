package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.objects.ChunkPointer;
import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-22 (chunkset-recalc): ChunkSet metadata is corrupted after retainAll()/removeAll().
 *
 * What the code is supposed to do:
 *   ChunkSet is a Set&lt;ChunkPointer&gt; backed by a Roaring bitmap index plus cached
 *   bounds (start = smallest element, end = largest element + 1) and an element count
 *   (size). After any bulk mutation the cached info must be recomputed so that
 *   min(), max(), size(), isEmpty() and contains() stay consistent with the index.
 *   retainAll() and removeAll() both call recalcInfo() to do this
 *   (ChunkSet.java:355 and ChunkSet.java:374).
 *
 * What it actually does:
 *   recalcInfo() (ChunkSet.java:329-333) calls calcStart() (correctly setting
 *   start = smallest element, ChunkSet.java:440-442) and calcEnd(), and then
 *   OVERWRITES start with {@code index.iter().count()} — the number of elements in
 *   the index — instead of the smallest element. It also never updates the {@code size}
 *   field at all, so size stays at its pre-mutation value.
 *
 * Why the tests fail:
 *   - {10,20,30}.retainAll({20}) leaves start = 1 (element count) and size = 3 (stale),
 *     so min() returns 1 instead of 20 and size() returns 3 instead of 1.
 *   - {0,1,2,5}.retainAll({0,1,2}) leaves start = 3 (count) and end = 3 (max+1), so
 *     rageSize() == 0 and contains() short-circuits to false for elements that are
 *     still in the set (ChunkSet.java:484-485).
 *   In every case the bitmap index itself is intact (iteration still yields the
 *   correct elements), proving the corruption is in the cached metadata, not the index.
 */
public class ReproChunkSetRecalcTests{

	private static ChunkSet set(long... values){
		var ints = new int[values.length];
		for(var i = 0; i<values.length; i++) ints[i] = (int)values[i];
		return new ChunkSet(ints);
	}

	@Test
	void retainAll_minIsElementCountNotSmallestValue(){
		var set = set(10, 20, 30);
		set.retainAll(List.of(ChunkPointer.of(20)));

		// The index itself is intact: iteration still returns exactly {20}.
		assertThat(set.stream().toList()).containsExactly(ChunkPointer.of(20));

		// min() reads `start`, which recalcInfo() overwrote with index.iter().count()
		// (=1, the element count) instead of the smallest element (20).
		// ChunkSet.java:332. Expect 20, observe 1 -> this assertion FAILS.
		assertThat(set.min())
			.as("min() after retainAll must be the smallest retained element")
			.isEqualTo(20L);
	}

	@Test
	void retainAll_sizeNeverRecalculated(){
		var set = set(10, 20, 30);
		set.retainAll(List.of(ChunkPointer.of(20)));

		assertThat(set.stream().toList()).containsExactly(ChunkPointer.of(20));

		// recalcInfo() (ChunkSet.java:329-333) never updates `size`, so it still
		// holds the pre-retainAll count of 3 instead of 1. This assertion FAILS.
		assertThat(set.size())
			.as("size() after retainAll must be 1")
			.isEqualTo(1);
	}

	@Test
	void retainAll_containsFalseForRetainedMembers(){
		var set = set(0, 1, 2, 5);
		set.retainAll(List.of(ChunkPointer.of(0), ChunkPointer.of(1), ChunkPointer.of(2)));

		assertThat(set.stream().toList())
			.containsExactly(ChunkPointer.of(0), ChunkPointer.of(1), ChunkPointer.of(2));

		// After recalcInfo: start = count = 3, end = max+1 = 3, so rageSize() == 0
		// and contains() short-circuits to false (ChunkSet.java:484-485) even though
		// 0 is still in the set. This assertion FAILS (contains(0) is false).
		assertThat(set.contains(0))
			.as("contains(0) after retainAll must be true, 0 is still in the set")
			.isTrue();
	}

	@Test
	void removeAll_minCorrupt(){
		var set = set(10, 20, 30);
		set.removeAll(List.of(ChunkPointer.of(10), ChunkPointer.of(30)));

		assertThat(set.stream().toList()).containsExactly(ChunkPointer.of(20));

		// removeAll() reaches the same recalcInfo() (ChunkSet.java:374): start is
		// overwritten with the element count (1) instead of 20. This assertion FAILS.
		assertThat(set.min())
			.as("min() after removeAll must be the smallest remaining element")
			.isEqualTo(20L);
	}

	@Test
	void removeAll_sizeCorrupt(){
		var set = set(10, 20, 30);
		set.removeAll(List.of(ChunkPointer.of(10), ChunkPointer.of(30)));

		assertThat(set.stream().toList()).containsExactly(ChunkPointer.of(20));

		// `size` still holds the pre-removeAll count of 3 instead of 1. FAILS.
		assertThat(set.size())
			.as("size() after removeAll must be 1")
			.isEqualTo(1);
	}

}
