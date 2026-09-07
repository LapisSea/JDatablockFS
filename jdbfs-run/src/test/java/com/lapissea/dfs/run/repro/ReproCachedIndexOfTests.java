package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.objects.collections.IOList;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-16 (cached-indexof): IOListCached.indexOf returns a LATER occurrence when
 * the true first occurrence is not in the element cache.
 *
 * What the code is supposed to do:
 *   IOList.indexOf(value) (default impl, IOList.java:588) returns the index of
 *   the FIRST occurrence of value in the list. A cached view (IOListCached) must
 *   behave identically; its cache is only a read-acceleration, so the answer may
 *   never depend on which elements happen to be cached.
 *
 * What it actually does:
 *   IOListCached.indexOf (IOListCached.java:581-588) first scans cache.entrySet()
 *   in cache order (insertion order for the Sparse/LinkedHashMap layer, Array
 *   layer order for the Array one) and immediately returns the index of the
 *   FIRST CACHED match (IOListCached.java:585 `return e.getKey()`). Only when no
 *   cached entry matches does it fall through to data.indexOf(value). So if a
 *   later occurrence is cached and the earlier one is not, the method returns
 *   the later index; if a later occurrence was cached before an earlier one
 *   (Sparse layer), it returns the later index even when BOTH are cached.
 *
 * Why the test fails:
 *   Backing list [1, 2, 3, 2] (x, a, b, a). The cache is primed with only
 *   index 3 (value 2) via view.get(3). indexOf(2) then finds the cached (3, 2)
 *   entry first and returns 3, while the true first occurrence is index 1 —
 *   the assertion "indexOf(2) == 1" fails with actual value 3.
 */
public class ReproCachedIndexOfTests{

	@Test
	public void indexOfMustReturnFirstOccurrenceWhenOnlyLaterOneIsCached() throws IOException{
		// Backing list [x, a, b, a] = [1, 2, 3, 2]; first occurrence of 2 is index 1.
		var backing = new ArrayList<>(List.of(1, 2, 3, 2));
		var data    = IOList.wrap(backing);

		// Sanity: the underlying (uncached) list finds the true first occurrence.
		assertThat(data.indexOf(2)).as("baseline: underlying list indexOf(2) is 1").isEqualTo(1L);

		// Cached view over the same list.
		var view = data.cachedView(16, 8);

		// Prime the cache with ONLY the LATER occurrence: IOListCached.get()
		// stores every element it reads (setC), so afterwards the cache holds
		// exactly one entry: {3: 2}. Index 1 (value 2) is NOT cached.
		assertThat(view.get(3)).isEqualTo(2);

		// BUG (IOListCached.java:581-588): indexOf scans the cache first and
		// returns the first cached match — here the later occurrence at 3 —
		// without ever consulting the underlying list, so the true first
		// occurrence (1), which is not cached, is never reported.
		long idx = view.indexOf(2);

		// Key assertion: List#indexOf contract — the FIRST occurrence (1), not
		// the cached later one (3). Fails with "expected 1 but was 3".
		assertThat(idx).as("indexOf(2) must be the first occurrence (1), not the cached later index (3)").isEqualTo(1L);
	}

	@Test
	public void sparseCacheScanFollowsInsertionOrderNotIndexOrder() throws IOException{
		var backing = new ArrayList<>(List.of(1, 2, 3, 2));
		var data    = IOList.wrap(backing);
		// maxLinearCache (2) < data.size() (4) forces the Array cache layer to be
		// replaced by the Sparse layer (LinkedHashMap, insertion-ordered) on the
		// first cached put (IOListCached.java:314-320).
		var view = data.cachedView(16, 2);

		// Cache the LATER occurrence (index 3) FIRST ...
		assertThat(view.get(3)).isEqualTo(2);
		// ... then the EARLIER one (index 1). Sparse insertion order is now [3, 1].
		assertThat(view.get(1)).isEqualTo(2);

		// BUG (IOListCached.java:581-588): the cache scan walks entrySet() in
		// insertion order, so it hits (3, 2) before (1, 2) and returns 3 —
		// even though the earlier occurrence (1) IS cached.
		long idx = view.indexOf(2);

		// Key assertion: must be the first occurrence (1); fails with "expected 1 but was 3".
		assertThat(idx).as("indexOf(2) must be the first occurrence (1), not the first in cache insertion order (3)").isEqualTo(1L);
	}
}
