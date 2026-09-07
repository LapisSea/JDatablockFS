package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.RNGType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * BUG-02 (fuzz-rng-order) reproduction.
 *
 * What the code is supposed to do:
 *   RNGType (Fuzzer/src/main/java/com/lapissea/fuzz/RNGType.java) registers one generator function
 *   per fuzz-action type and picks between them via apply(RandomGenerator). The pick is
 *   randomPick.get(random.nextInt(randomPick.size())) (RNGType.java:60), i.e. the pick is an index
 *   into the randomPick table. For recorded fuzz runs to be replayable across JVM launches, the
 *   same seed + the same registered types must always produce the same pick sequence, so the
 *   index->generator table must be stably defined. The only order defined by the API is the
 *   declaration order of the definition list passed to RNGType.of(...).
 *
 * What it actually does:
 *   The constructor builds "universe" with Collectors.toUnmodifiableMap keyed by the Class objects
 *   of the generated elements (RNGType.java:31-35). toUnmodifiableMap returns an unmodifiable
 *   HashMap whose iteration order is unspecified (Javadoc of Collectors.toUnmodifiableMap), and
 *   randomPick is initialized from universe.values() (RNGType.java:37), so it inherits that order.
 *   The Class keys hash by identity (Object.hashCode), and identity hash codes are not stable
 *   across JVM launches. The index->generator table is therefore a hash-based permutation of the
 *   declaration order that differs from process to process: the same seed indexes a DIFFERENT
 *   generator in a different JVM, so cross-process replay of recorded fuzz runs is non-deterministic.
 *
 * Why the test fails:
 *   Within one JVM the (wrong) order is fixed, so the tests demonstrate the mechanism directly. The
 *   index->generator table is derived with the public API only: with no chanceFor(...) registered,
 *   getFn (RNGType.java:45-61) consumes exactly one nextInt(12) per apply() call, so a
 *   RecordingRandom that logs nextInt(bound) captures the exact index used for every pick.
 *   (1) the table is asserted to be the declaration order — it is the HashMap identity-hash order;
 *   (2) the same 12 generators declared in reverse order must change the table in a defined
 *       implementation, but the table is identical because it only depends on the keys' identity
 *       hashes — proof the declaration order is ignored;
 *   (3) a fixed-seed pick sequence is asserted to match what the declaration-order table would
 *       produce — it does not, because the index->generator mapping is hash-based.
 *   A coincidental match of the hash order with the declaration order is negligible for 12 distinct
 *   Class keys, so the failures below are attributable to the defect, not to test flakiness.
 */
public final class ReproFuzzRngOrderTests {

	static abstract class Element{}
	static final class E01 extends Element{}
	static final class E02 extends Element{}
	static final class E03 extends Element{}
	static final class E04 extends Element{}
	static final class E05 extends Element{}
	static final class E06 extends Element{}
	static final class E07 extends Element{}
	static final class E08 extends Element{}
	static final class E09 extends Element{}
	static final class E10 extends Element{}
	static final class E11 extends Element{}
	static final class E12 extends Element{}

	private static final int TYPE_COUNT = 12;

	/** A Random that records every nextInt(bound) call, so the index RNGType.java:60 uses is observable. */
	private static final class RecordingRandom extends Random {
		final List<Integer> indices = new ArrayList<>();
		RecordingRandom(long seed){ super(seed); }
		@Override
		public int nextInt(int bound){
			var v = super.nextInt(bound);
			indices.add(v);
			return v;
		}
	}

	/** The 12 generator functions in their declaration order (one per distinct Element class). */
	private static List<java.util.function.Function<java.util.random.RandomGenerator, Element>> definitions(){
		return List.of(
			(java.util.function.Function<java.util.random.RandomGenerator, Element>) r -> new E01(),
			r -> new E02(),
			r -> new E03(),
			r -> new E04(),
			r -> new E05(),
			r -> new E06(),
			r -> new E07(),
			r -> new E08(),
			r -> new E09(),
			r -> new E10(),
			r -> new E11(),
			r -> new E12()
		);
	}

	/** The class names in declaration order — the only order defined by the RNGType.of API. */
	private static List<String> declarationOrder(){
		var names = new ArrayList<String>();
		var probe = new Random(0);
		for(var fn : definitions()){
			names.add(fn.apply(probe).getClass().getSimpleName());
		}
		return List.copyOf(names);
	}

	/**
	 * The index->generator table actually in effect (simple name of the class produced by the
	 * generator at each index 0..11), derived through the public apply() API alone.
	 */
	private static List<String> indexToClassTable(RNGType<Element> rng, long seed){
		var rec   = new RecordingRandom(seed);
		var table = new ArrayList<String>(Collections.nCopies(TYPE_COUNT, null));
		var picks = 0;
		while(table.contains(null) && picks < 100_000){
			var picked = rng.apply(rec);
			var idx    = rec.indices.get(rec.indices.size() - 1);
			var cls    = picked.getClass().getSimpleName();
			// the table is fixed for the lifetime of the RNGType: the same index must always
			// return the same generator (this is a sanity check on the harness, not the bug)
			var prev = table.get(idx);
			if(prev != null) assertEquals(prev, cls, "index " + idx + " mapped to two different classes");
			table.set(idx, cls);
			++picks;
		}
		assertTrue(!table.contains(null), "not all 12 indices were observed within 100000 picks");
		return table;
	}

	@Test
	void pickOrderFollowsDeclarationOrder(){
		var rng      = RNGType.of(definitions());
		var actual   = indexToClassTable(rng, 7);
		var expected = declarationOrder();

		// BUG: randomPick is built from the iteration of the "universe" HashMap (RNGType.java:31-37)
		// keyed by identity-hashed Class objects, not from the declaration list — so this defined
		// order is not the one in effect, and the mapping changes across JVM launches.
		assertEquals(actual, expected,
		             "the index->generator table must follow the declaration order so a fixed seed maps "
		                     + "to the same generators in every JVM launch, but the HashMap identity-hash "
		                     + "order was: " + actual);
	}

	@Test
	void declarationOrderDeterminesPickOrder(){
		var forward  = definitions();
		var backward = new ArrayList<>(forward);
		Collections.reverse(backward);

		var tableForward  = indexToClassTable(RNGType.of(forward), 11);
		var tableBackward = indexToClassTable(RNGType.of(backward), 11);

		// A defined implementation derives the index->generator table from the declaration order, so
		// declaring the same 12 generators in reverse order must change the table. Under the bug
		// (RNGType.java:31-37) both tables are the identity-hash iteration order of the SAME Class
		// keys, hence identical — proving the order is hash-based, not declaration-defined.
		assertNotEquals(tableForward, tableBackward,
		                "reversing the declaration order must change the pick table if the table is "
		                        + "defined by declaration; identical tables show it depends only on the "
		                        + "Class identity hashes: " + tableForward);
	}

	@Test
	void seededReplayMatchesDeclarationOrderTable(){
		var rng  = RNGType.of(definitions());
		var decl = declarationOrder();

		// Same seed, two walks: what the library actually picks vs what the defined
		// declaration-order table would pick. apply() consumes exactly one nextInt(12) per call
		// (chances empty -> RNGType.java:60), so both walks use the same index sequence.
		var actualSeq = new ArrayList<String>();
		var actual    = new RecordingRandom(42);
		for(int i = 0; i<500; i++){
			actualSeq.add(rng.apply(actual).getClass().getSimpleName());
		}

		var expectedSeq = new ArrayList<String>();
		var expected    = new Random(42);
		for(int i = 0; i<500; i++){
			expectedSeq.add(decl.get(expected.nextInt(decl.size())));
		}

		// BUG: because the index->generator table is the HashMap identity-hash order
		// (RNGType.java:31-37) and not the declaration order, the same seed yields a different
		// type sequence than the defined table would — cross-process replay is non-deterministic.
		assertEquals(actualSeq, expectedSeq,
		             "same seed must pick the same type sequence under the defined (declaration-order) "
		                     + "table; first 20 actual picks: " + actualSeq.subList(0, 20));
	}
}
