package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

/*
 * BUG-18 (hash-minval) — reproduction test.
 *
 * What the code is supposed to do: HashIOMap and IOHashSet index their bucket arrays with
 *     bucket = HashCommons.toHash(key) % capacity
 * where toHash (jdbfs-core .../internal/HashCommons.java:5-10) is
 *     Math.abs(mixMurmur32(key.hashCode()))
 * and is expected to always be non-negative, so the remainder is a valid bucket index in [0, capacity).
 *
 * What it actually does: the murmur3 32-bit finalizer (mixMurmur32) is a bijection, so exactly 1 of
 * 2^32 possible hashCodes maps to Integer.MIN_VALUE. For that key, Math.abs(Integer.MIN_VALUE) ==
 * Integer.MIN_VALUE (Math.abs does not negate the minimum two's-complement value), so toHash() returns
 * a NEGATIVE "hash". In HashIOMap.BucketSet.find (HashIOMap.java:185-192) the index is computed as
 * `keyHash % capacity` (Java's % keeps the sign of the dividend) and the only guard is
 * `index >= data.size()`, which does not catch negative values, so data.get(negative) ->
 * ContiguousIOList.get -> Objects.checkIndex (UnmanagedIOList.java:156-161) throws
 * IndexOutOfBoundsException. The same defect exists in IOHashSet (IOHashSet.java:35-37 and
 * :217-219: smallHash() re-applies Math.abs to a hash that is already negative).
 *
 * The defect is masked on a fresh map: the initial bucket set has capacity MIN_SIZE == 4 (a power of
 * two) and Integer.MIN_VALUE % 4 == 0, a valid index. Once the set grows (HashIOMap.java:483-485 and
 * :517: new capacity = (long)(capacity * GROWTH_FACTOR 1.618), e.g. 4 -> 6) the capacity no longer
 * divides 2^31 and Integer.MIN_VALUE % 6 == -2, so the first put/get/containsKey with the poison key
 * after growth throws IndexOutOfBoundsException instead of storing/finding the entry.
 *
 * Why this test fails: POISON_KEY = 0x7EC69360 is the unique key K with
 * mixMurmur32(K.hashCode()) == Integer.MIN_VALUE, obtained by inverting the fmix32 finalizer (undo
 * ^>>>16, multiply by the mod-2^32 inverse of 0xc2b2ae35, undo ^>>>13, multiply by the mod-2^32
 * inverse of 0x85ebca6b, undo ^>>>16). The test verifies the preimage by forward evaluation, puts
 * three regular keys (forcing the capacity 4 -> 6 growth) and then the poison key. put() reaches
 * BucketSet.find with keyHash == Integer.MIN_VALUE, computes index == -2 and throws
 * IndexOutOfBoundsException("Index -2 out of bounds for length 6") instead of storing the entry.
 * The assertions after the put state the behavior the code should have (entry stored and readable)
 * and are unreachable while the bug is present.
 */
public class ReproHashMinValTests{
	
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	/// Unique key K with mixMurmur32(K.hashCode()) == Integer.MIN_VALUE: the exact inverse of the
	/// fmix32 finalizer applied to 0x80000000 (see class comment for the derivation).
	private static final int POISON_KEY = 0x7EC69360;
	
	/// Mirror of HashCommons.mixMurmur32 (jdbfs-core .../internal/HashCommons.java:19-23), copied so
	/// the preimage can be verified without importing the non-exported com.lapissea.dfs.internal package.
	private static int mixMurmur32(int z){
		z = (z^(z >>> 16))*0x85ebca6b;
		z = (z^(z >>> 13))*0xc2b2ae35;
		return z^(z >>> 16);
	}
	
	@Test
	public void putGet_poisonKey_roundTrip() throws IOException{
		// Precondition: the inverted preimage must be exact (forward evaluation of the mix).
		Assert.assertEquals(mixMurmur32(POISON_KEY), Integer.MIN_VALUE,
			"preimage check: mixMurmur32(0x7EC69360) must be Integer.MIN_VALUE, making toHash() return a negative hash");
		
		var cl      = Cluster.init(MemoryData.empty());
		var typeDef = IOType.of(HashIOMap.class, Integer.class, Integer.class);
		var chunk   = AllocateTicket.bytes(64).submit(cl);
		var map     = new HashIOMap<Integer, Integer>(cl, chunk, typeDef);
		
		// Three entries in the initial capacity-4 bucket set: the next put sees
		// occupancy(1) == 4/4 == 1.0 >= MAX_OCCUPANCY(0.8) and grows the set to
		// (long)(4 * 1.618) == 6 buckets — the first capacity that does not divide 2^31.
		map.put(1, 10);
		map.put(2, 20);
		map.put(3, 30);
		
		// BUG: toHash(POISON_KEY) == Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE (negative), so
		// BucketSet.find (HashIOMap.java:186) computes index == MIN_VALUE % 6 == -2, passes the
		// `index >= data.size()` guard, and data.get(-2) (HashIOMap.java:189) throws
		// IndexOutOfBoundsException. A correct toHash (e.g. h & 0x7FFFFFFF) would store the entry:
		map.put(POISON_KEY, 99);
		
		// Key assertions (unreachable while the bug is present): the poison key must behave like any
		// other key — stored, found and counted.
		Assert.assertEquals(map.getEntry(POISON_KEY).getValue(), 99,
			"poison key must be storable and readable");
		Assert.assertTrue(map.containsKey(POISON_KEY), "poison key must be found by containsKey");
		Assert.assertEquals(map.size(), 4L, "all four entries must be stored");
	}
}
