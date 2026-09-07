package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOTreeSet;
import org.assertj.core.api.Assertions;
import org.testng.annotations.Test;

import java.lang.reflect.Field;

/**
 * BUG-14 treeset-slot0
 *
 * What the code is supposed to do:
 *   IOTreeSet stores its elements in two backing pools: {@code values} (an
 *   IOList&lt;Val&gt;) and {@code nodes} (an IOList&lt;Node&gt;). When an element is
 *   removed, its pool slot is freed so it can be reused by a future add. A
 *   freed slot is "registered" in the {@code blankValueIds}/{@code blankNodeIds}
 *   TreeSet so that {@code findValueIdx}/{@code findNodeIdx} hand it back out.
 *   The pools are therefore expected to stay flat (bounded by the live element
 *   count) under repeated add/remove cycles.
 *
 * What it actually does:
 *   In gcValue (IOTreeSet.java:342-354) and gcNode (IOTreeSet.java:358-376) a
 *   freed slot is registered ONLY when {@code if(idx > 0)} (lines :352 and :374).
 *   Slot 0 is therefore never registered. The fallback discovery path,
 *   scanValueIds/scanNodeIds (lines :428-441 / :405-419), runs only ONCE
 *   (guarded by the blankValueIdsScanned/blankNodeIdsScanned flags). So once the
 *   first add has run the scan, a later-freed slot 0 is never reused: it stays a
 *   permanent null hole and every subsequent add that finds no registered blank
 *   slot APPENDS a new slot instead of reusing slot 0.
 *
 * Why the test fails (if the bug is present):
 *   The test seeds the set with two elements (so element #1 lands in values pool
 *   slot 0), then repeatedly removes the current minimum (the root, which owns
 *   slot 0 on the first cycle) and adds a fresh, larger element. Each cycle that
 *   frees slot 0 without it being reused forces an append, so the values pool
 *   grows monotonically (one slot per cycle) instead of staying flat. The
 *   assertion below bounds the pool growth; with the defect the pool climbs to
 *   ~N+2 after N cycles and the assertion fails. The second test method
 *   (slot0ElementAddedAndRemovedEachEra_poolStaysFlat) exercises the literal
 *   report scenario -- an element that lands in slot 0 is added and removed in
 *   every one of N eras -- and asserts the pool does not grow monotonically.
 *
 * Observed result (FALSE POSITIVE):
 *   The tests do NOT fail. The values pool grows by exactly one slot (2 -> 3)
 *   when slot 0 is first freed, then stays flat for all 50 cycles (max=3,
 *   live=2): every later removal frees a slot with idx > 0, which IS registered
 *   and reused. The literal era scenario (an element lands in slot 0 and is
 *   removed in each of 30 eras) never grows at all: the one-time slot-0 hole is
 *   physically cleared by the end-of-era drain (gcValue's remove + popLastIf
 *   branch, IOTreeSet.java:344-349). The mechanism itself is confirmed -- slot 0
 *   does become a permanent null hole in the non-drained case (see the
 *   TREESLOT0-TRACE output) -- but the reported consequence, monotonic /
 *   unbounded pool growth, does not manifest: growth is one-time and bounded by
 *   live+1.
 *
 * Note on measurement:
 *   Pool size is read straight off the private {@code values}/{@code nodes}
 *   IOList fields via reflection (IOList#size() counts the backing slots,
 *   including the null holes left by free()). No production code is modified.
 */
public class ReproTreeSetSlot0Tests {

	// Number of remove/add cycles. Large enough that "one slot per cycle" growth
	// is unambiguous, small enough to stay well under the ~90s budget.
	private static final int CYCLES = 50;

	@Test
	public void valuesPoolDoesNotGrowWithoutBound() throws Exception {
		var cluster = Cluster.emptyMem();
		var set = cluster.roots().<IOTreeSet<Integer>>request(1, IOTreeSet.class, Integer.class);

		var values = pool(set, "values");
		var nodes  = pool(set, "nodes");

		// Seed: element 1 -> values slot 0, element 2 -> values slot 1.
		// The very first add already runs the one-shot scan (scanValueIds),
		// setting blankValueIdsScanned=true for the life of this set.
		set.add(1);
		set.add(2);
		long startVals = values.size();
		long startNodes = nodes.size();

		long maxVals = startVals;
		long maxNodes = startNodes;
		var trace = new StringBuilder();

		for(int i = 0; i<CYCLES; i++){
			// Remove the current minimum (the BST root). On cycle 0 this frees
			// values slot 0 through the gcValue else-branch (IOTreeSet.java:351-352):
			// values.free(0) runs, but `if(idx > 0)` is false so slot 0 is NOT
			// added to blankValueIds and the one-shot scan will never find it again.
			// Then add a fresh distinct maximum so the set always holds 2 elements.
			set.remove(i + 1);
			set.add(i + 3);

			long vs = values.size();
			long ns = nodes.size();
			maxVals  = Math.max(maxVals, vs);
			maxNodes = Math.max(maxNodes, ns);
			if(i < 4 || i % 10 == 0){
				trace.append(String.format("  cycle %2d: values=%d nodes=%d%n", i, vs, ns));
			}
		}

		// Evidence of the mechanism: is values slot 0 a null hole at the end?
		Object slot0 = valAt(values, 0);
		trace.append(String.format("  values slot 0 at end: %s%n", slot0 == null ? "NULL HOLE (never reused)" : "reused (" + slot0 + ")"));
		trace.append(String.format("  live set size        : %d%n", set.size()));
		trace.append(String.format("  MAX values pool size : %d (start=%d)%n", maxVals, startVals));
		trace.append(String.format("  MAX nodes  pool size : %d (start=%d)%n", maxNodes, startNodes));

		// Emit the trace on stderr (the library replaces System.out during
		// bootstrap, which would swallow plain System.out output).
		System.err.println("TREESLOT0-TRACE");
		System.err.print(trace);

		// KEY ASSERTION (the reported failure): the backing pools must stay flat /
		// bounded by the live element count, because freed slots are reused. A
		// correct implementation keeps the values pool at ~2 (the live size). With
		// the slot-0 defect the pool grows by ~1 slot per cycle and reaches
		// ~CYCLES+2, so this bound is violated.
		Assertions.assertThat(maxVals)
			.as("values pool grew without bound on repeated slot-0 removals:\n" + trace)
			.isLessThanOrEqualTo(startVals + 2);
		Assertions.assertThat(maxNodes)
			.as("nodes pool grew without bound on repeated slot-0 removals:\n" + trace)
			.isLessThanOrEqualTo(startNodes + 2);
	}

	/**
	 * Literal expected-failure scenario from the bug report: repeat N times
	 * {add an element that lands in values pool slot 0, then remove it}.
	 *
	 * Draining the set completely between eras makes every era's first add append
	 * to an empty pool, i.e. land in slot 0; that element is also the minimum
	 * (the BST root), so its removal always goes through gcValue's else-branch
	 * (IOTreeSet.java:351-352) and frees slot 0 without registering it. The
	 * reported failure is that the pool then grows monotonically (one slot per
	 * era) instead of staying flat.
	 */
	@Test
	public void slot0ElementAddedAndRemovedEachEra_poolStaysFlat() throws Exception {
		var cluster = Cluster.emptyMem();
		var set = cluster.roots().<IOTreeSet<Integer>>request(1, IOTreeSet.class, Integer.class);

		var values = pool(set, "values");
		var nodes  = pool(set, "nodes");

		long maxVals  = 0;
		long maxNodes = 0;
		boolean sawSlot0Hole = false;
		var trace = new StringBuilder();

		for(int era = 0; era < 30; era++){
			int base = era * 100;
			set.add(base + 1);    // lands in values slot 0 (pool was drained last era)
			maxVals  = Math.max(maxVals, values.size());
			maxNodes = Math.max(maxNodes, nodes.size());
			set.add(base + 2);    // lands in values slot 1, becomes the right child
			maxVals  = Math.max(maxVals, values.size());
			maxNodes = Math.max(maxNodes, nodes.size());
			set.remove(base + 1); // removes the slot-0 element (the root)
			maxVals  = Math.max(maxVals, values.size());
			maxNodes = Math.max(maxNodes, nodes.size());
			if(values.size() == 2 && valAt(values, 0) == null){
				sawSlot0Hole = true; // mechanism evidence: slot 0 freed but not registered
			}
			set.remove(base + 2); // last slot -> physical shrink clears the hole

			long vs = values.size();
			long ns = nodes.size();
			if(era < 3 || era % 10 == 0){
				trace.append(String.format("  era %2d: end values=%d nodes=%d%n", era, vs, ns));
			}
		}

		trace.append(String.format("  saw slot-0 null hole mid-era : %s%n", sawSlot0Hole));
		trace.append(String.format("  MAX values pool size         : %d (max live = 2)%n", maxVals));
		trace.append(String.format("  MAX nodes  pool size         : %d (max live = 2)%n", maxNodes));

		System.err.println("TREESLOT0-ERA-TRACE");
		System.err.print(trace);

		// A flat pool never exceeds the max live element count (2 here). With the
		// reported monotonic growth, maxVals would climb toward the era count.
		Assertions.assertThat(maxVals)
			.as("values pool grew monotonically across slot-0 eras:\n" + trace)
			.isLessThanOrEqualTo(2);
		Assertions.assertThat(maxNodes)
			.as("nodes pool grew monotonically across slot-0 eras:\n" + trace)
			.isLessThanOrEqualTo(2);
	}

	// Reads the private backing IOList pool field off the IOTreeSet instance.
	private static IOList<?> pool(IOTreeSet<?> set, String field) throws Exception {
		Field f = IOTreeSet.class.getDeclaredField(field);
		f.setAccessible(true);
		return (IOList<?>)f.get(set);
	}

	// Returns the payload stored in a values-pool slot, or null if the slot is a
	// free/null hole. Used only to record whether slot 0 was reused.
	private static Object valAt(IOList<?> list, long index) throws Exception {
		Object el;
		try{
			el = list.get(index);
		}catch(Exception e){
			return "<unreadable: " + e + ">";
		}
		if(el == null) return null;
		try{
			Field f = el.getClass().getDeclaredField("val");
			f.setAccessible(true);
			return f.get(el);
		}catch(NoSuchFieldException e){
			return el;
		}
	}
}
