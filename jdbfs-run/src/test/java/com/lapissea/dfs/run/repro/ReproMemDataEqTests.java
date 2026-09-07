package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.impl.MemoryData;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-50 memdata-eq: MemoryData violates the equals/hashCode contract.
 *
 * What the code is supposed to do:
 *   MemoryData.equals(Object) (jdbfs-core/src/main/java/com/lapissea/dfs/io/impl/MemoryData.java:33-37)
 *   declares two MemoryData instances equal when their underlying fileData byte arrays are
 *   content-equal (Arrays.equals). Per the Java equals/hashCode general contract, any two
 *   instances that are equal via equals() MUST return the same hashCode().
 *
 * What it actually does:
 *   hashCode() (MemoryData.java:39-42) returns a field initialized to
 *   System.identityHashCode(this) (MemoryData.java:30) — i.e. an identity-based hash that has
 *   nothing to do with the data content. Two instances built from identical byte arrays are
 *   therefore equals()-equal but have different hashCodes.
 *
 * Why the test fails:
 *   a.equals(b) is true (content-equal), so the contract requires a.hashCode() == b.hashCode(),
 *   but the identity-based hashes differ. This also breaks HashMap semantics: a map keyed by
 *   MemoryData cannot retrieve an entry using a content-equal (but not identical) instance,
 *   because the lookup lands in a different bucket.
 */
public class ReproMemDataEqTests{
	
	@Test
	void contentEqualInstancesHaveEqualHashCodes(){
		// MemoryData.of() clones the input array, so a and b hold distinct byte[] objects
		// with identical content — exactly the case equals() is written to treat as equal.
		var a = MemoryData.of(new byte[]{1, 2, 3, 4, 5});
		var b = MemoryData.of(new byte[]{1, 2, 3, 4, 5});
		
		// Sanity: equals() is content-based (Arrays.equals) and passes — this is the premise
		// of the contract violation.
		assertThat(a.equals(b)).as("equals() compares fileData contents").isTrue();
		
		// BUG: the equals/hashCode contract requires equal instances to have equal hashCodes.
		// hashCode() is System.identityHashCode(this) (MemoryData.java:30), so it fails here.
		assertThat(a.hashCode())
			.as("equals() is true, so hashCode() must be equal (identity-based hash is the bug)")
			.isEqualTo(b.hashCode());
	}
	
	@Test
	void hashMapCrossLookupWithContentEqualKey(){
		var a = MemoryData.of(new byte[]{9, 8, 7, 6});
		var b = MemoryData.of(new byte[]{9, 8, 7, 6});
		
		assertThat(a.equals(b)).as("premise: content-equal instances are equals()-equal").isTrue();
		
		Map<MemoryData, String> map = new HashMap<>();
		map.put(a, "value");
		
		// BUG: lookup with a content-equal but distinct instance misses because hashCode()
		// (identity-based) differs, so the map probes the wrong bucket and returns null.
		assertThat(map.get(b))
			.as("HashMap lookup with an equals()-equal key must find the entry")
			.isEqualTo("value");
	}
	
}
