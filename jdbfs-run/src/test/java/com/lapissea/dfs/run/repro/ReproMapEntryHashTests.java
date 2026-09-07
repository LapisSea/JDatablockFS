package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.type.IOInstance;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-19 map-entry-hash — IOMap.IOEntry.Abstract.hashCode() ignores the value.
 *
 * What the code is supposed to do:
 *   IOMap.IOEntry.Abstract.hashCode() (jdbfs-core .../objects/collections/IOMap.java:70-78) is the
 *   shared hashCode for ALL map entries (Simple, Modifiable.Unsupported, HashIOMap.ModifiableIOEntry,
 *   and user-defined Modifiable.Abstract subclasses). Per the Map.Entry#hashCode contract, an entry's
 *   hashCode must be derived from BOTH its key and its value, so that entries with the same key but
 *   different values do not all collide. Its companion equals() (IOMap.java:63-68) already
 *   distinguishes entries by both key and value.
 *
 * What it actually does:
 *   IOMap.java:72 reads `var v = getKey();` — it should be `getValue()`. The method then folds k and
 *   v into the hash (lines 74-77), but since v is the key again, the value never contributes:
 *   hash = 31*(31*1 + keyHash) + keyHash, i.e. the key is folded in twice and the value is ignored.
 *   Consequence: any two entries sharing a key have identical hashCodes even though equals()
 *   returns false for them — an equals/hashCode contract violation (and a guaranteed hash collision
 *   for every key in any collection of entries).
 *
 * Why the test fails:
 *   We build two entries with the SAME key but DIFFERENT values (via the IOEntry.of() factory, and
 *   via live HashIOMap.getEntry() over two clusters) and assert, per Map.Entry#hashCode semantics,
 *   that their hashCodes DIFFER. Because of IOMap.java:72 both hashCodes are identical, so the
 *   isNotEqualTo assertion fails.
 */
public class ReproMapEntryHashTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	@Test
	void entryHashCodeMustDependOnValue_directEntryFactory(){
		// IOEntry.of() returns a Simple entry (IOMap.java:81-98) which inherits the buggy
		// Abstract.hashCode() — no cluster needed to hit the defect.
		var e1 = IOMap.IOEntry.of("key", 1);
		var e2 = IOMap.IOEntry.of("key", 2);
		
		// equals() is implemented correctly (key AND value) — it must tell these entries apart
		assertThat(e1.equals(e2))
		           .as("equals() must distinguish entries with the same key but different values")
		           .isFalse();
		
		// BUG (IOMap.java:72): v = getKey() instead of getValue() → hash = f(key, key), the value
		// (1 vs 2) is never folded in → both entries produce the identical hash and collide.
		// Per Map.Entry#hashCode semantics the hashCodes of these non-equal entries must differ.
		assertThat(e1.hashCode())
		           .as("hashCode must depend on the value: entry(key,1) and entry(key,2) must not collide (IOMap.java:72 folds the key twice, ignoring the value)")
		           .isNotEqualTo(e2.hashCode());
	}
	
	@Test
	void entryHashCodeIsValueInsensitive_forAllValuesOfSameKey(){
		// Stronger form of the same defect: with the value dropped from the hash, EVERY value for a
		// fixed key yields the very same hashCode.
		var base = IOMap.IOEntry.of("key", 0);
		for(int value = 1; value < 50; value++){
			// Each of these fails as soon as the buggy IOMap.java:72 is present: the hash of
			// entry("key", value) is identical to the hash of entry("key", 0).
			assertThat(IOMap.IOEntry.of("key", value).hashCode())
			           .as("hashCode of entry(key, %d) must differ from hashCode of entry(key, 0) — the value is ignored (IOMap.java:72)", value)
			           .isNotEqualTo(base.hashCode());
		}
	}
	
	@Test
	void liveHashIOMapEntryHashCodeMustDependOnValue() throws IOException{
		// Live path: HashIOMap.getEntry() returns ModifiableIOEntry (HashIOMap.java:331,372) which
		// extends IOEntry.Modifiable.Abstract → same buggy hashCode. A single map can only hold one
		// value per key, so use two maps (two clusters) with the same key but different values.
		IOMap.IOEntry<Integer, Integer> e1 = liveEntry(1, 100);
		IOMap.IOEntry<Integer, Integer> e2 = liveEntry(2, 200);
		
		assertThat(e1).as("getEntry must find the put key").isNotNull();
		assertThat(e2).as("getEntry must find the put key").isNotNull();
		assertThat(e1.getKey()).isEqualTo(7);
		assertThat(e2.getKey()).isEqualTo(7);
		assertThat(e1.getValue()).isEqualTo(100);
		assertThat(e2.getValue()).isEqualTo(200);
		
		// same key, different values → not equal, but identical hashCodes because of IOMap.java:72
		assertThat(e1.equals(e2)).as("entries with different values must not be equal").isFalse();
		assertThat(e1.hashCode())
		           .as("live map entries with the same key but different values must not collide (IOMap.java:72)")
		           .isNotEqualTo(e2.hashCode());
	}
	
	private static IOMap.IOEntry<Integer, Integer> liveEntry(int id, int value) throws IOException{
		var cl = Cluster.init(MemoryData.empty());
		HashIOMap<Integer, Integer> map = cl.roots().request(id, HashIOMap.class, Integer.class, Integer.class);
		map.put(7, value);
		return map.getEntry(7);
	}
}
