package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.util.ArrayList;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

/**
 * BUG-31 (iotypedb-fromid): off-by-one bounds check in
 * IOTypeDB.MemoryOnlyDB.Fixed.fromID(Class, int).
 *
 * What the code is supposed to do: resolve a sealed subtype from its registered ID and, like the
 * other out-of-range lookups in the same class (e.g. idToTyp() at IOTypeDB.java:377-379, which
 * bounds-checks with `id >= idToTyp.length`), return null for an ID that is not present in the
 * baked universe.
 *
 * What it actually does: the bounds check at IOTypeDB.java:365 is
 *     if(id > universe.id2cl.length || id <= 0) return null;
 * which uses `>` where it must use `>=`. The id2cl array is sized max(id) + 1
 * (IOTypeDB.java:299-300), so for a universe with N registered types id2cl.length == N + 1, and
 * the unknown id N + 1 (i.e. id == length) slips past the guard and the array access at
 * IOTypeDB.java:366 throws ArrayIndexOutOfBoundsException instead of returning null.
 *
 * Why the test fails: the sealed universe below registers exactly 2 subtypes (ids 1 and 2), so
 * the baked Fixed universe's id2cl has length 3. fromID(Root.class, 3) is therefore an unknown id
 * that must yield null, but the buggy guard `3 > 3 == false` lets it through to id2cl[3] → AIOOBE.
 * The sanity checks show the rest of the ID space behaves as intended (valid ids resolve, ids 0
 * and far out of range already return null), isolating id == length as the anomaly.
 */
public class ReproIotypedbFromIdTests {
	
	sealed interface Root{
		
		final class SubA extends IOInstance.Managed<SubA> implements Root{
			@IOValue
			int a;
			public SubA(){ }
			public SubA(int a){
				this.a = a;
			}
		}
		
		final class SubB extends IOInstance.Managed<SubB> implements Root{
			@IOValue
			int b;
			public SubB(){ }
			public SubB(int b){
				this.b = b;
			}
		}
	}
	
	@Test
	public void fixedFromIdAtId2clLengthReturnsNullInsteadOfAioobe() throws Exception{
		var basic = new IOTypeDB.MemoryOnlyDB.Basic();
		// Register the sealed universe the same way the built-in DB does (IOTypeDB.java:473-476);
		// SealedUniverse hands both args to toID as Class<Root>, and sorts members by class name
		// (SealedUtil.java:99), so registration order is deterministically [SubA, SubB].
		var uni  = SealedUtil.getSealedUniverse(Root.class, false).orElseThrow();
		var subs = new ArrayList<>(uni.universe());
		assertEquals(subs.size(), 2);
		// Basic's sealed MemUniverse starts the id counter at 1 (IOTypeDB.java:184),
		// so SubA gets id 1 and SubB gets id 2.
		assertEquals(basic.toID(uni.root(), subs.get(0), true), 1);
		assertEquals(basic.toID(uni.root(), subs.get(1), true), 2);
		
		// Baking converts the map-based universe into a Fixed one whose id2cl array is sized
		// max(id) + 1 == 3 (IOTypeDB.java:299-300); valid indices are 1 and 2 only.
		var fixed = basic.bake();
		
		// Sanity: valid ids resolve to the registered classes...
		assertEquals(fixed.fromID(uni.root(), 1), Root.SubA.class);
		assertEquals(fixed.fromID(uni.root(), 2), Root.SubB.class);
		// ...and the other out-of-range ids are already handled cleanly by the guard.
		assertEquals(fixed.fromID(uni.root(), 0), null);
		assertEquals(fixed.fromID(uni.root(), 100), null);
		
		// The unknown id that is exactly id2cl.length: the contract (see class javadoc) is null,
		// but the buggy `>` guard at IOTypeDB.java:365 lets id == length through to id2cl[id].
		try{
			assertEquals(fixed.fromID(uni.root(), 3), null); // key assertion: must be null, not a crash
		}catch(ArrayIndexOutOfBoundsException e){
			// Reaching this catch is the reproduction: id == id2cl.length crashed instead of
			// returning null, because the guard uses `>` instead of `>=`.
			fail("BUG-31: Fixed.fromID(Root.class, 3) threw " + e
			     + " — guard `id > universe.id2cl.length` (IOTypeDB.java:365) must be `>=`, "
			   + "so id == id2cl.length (an unknown id) returns null instead of crashing");
		}
	}
}
