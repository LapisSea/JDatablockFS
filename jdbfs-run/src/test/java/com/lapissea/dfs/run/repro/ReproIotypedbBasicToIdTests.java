package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.type.IOTypeDB;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-30 (iotypedb-basic-toid): MemoryOnlyDB.Basic.toID ignores the {@code record} flag.
 *
 * <p>What the code is supposed to do:
 * {@code IOTypeDB.toID(rootType, type, record)} (IOTypeDB.java:1119) is the sealed-universe
 * lookup API. With {@code record=false} the call is a LOOKUP-ONLY probe: it must return the
 * id if the type is already present in the universe for {@code rootType} and must NOT change
 * the universe if the type is missing. Lookup-only callers rely on that contract:
 * sizing code (CollectionAdapter.java:115) and validation code
 * (IOFieldInlineSealedObject.java:141) both call {@code toID(root, type, false)}.
 * The immutable {@code Fixed} variant honours it: it never inserts (IOTypeDB.java:370-375).
 *
 * <p>What it actually does:
 * {@code MemoryOnlyDB.Basic.toID} (IOTypeDB.java:195-203) never reads the {@code record}
 * parameter: on a {@code cl2id} miss it unconditionally calls {@code universe.newId(type)}
 * (IOTypeDB.java:200), which inserts the missing type into the SHARED MemUniverse's
 * cl2id/id2cl maps (IOTypeDB.java:99-104, fields at :91-92). So every "read-only"
 * sizing/validation pass silently grows the type universe of a live pre-bake DB, and the
 * bogus entries are then carried into the on-disk format by {@code bake()}
 * (IOTypeDB.java:233-235).
 *
 * <p>Why this test fails:
 * we build a Basic DB whose Shape universe contains exactly Circle, then make a
 * lookup-only call {@code toID(Shape, Triangle, false)}. The contract says the universe
 * must stay {Circle}, but the implementation inserts Triangle, so the final assertion
 * (universe does not contain Triangle) fails.
 */
public class ReproIotypedbBasicToIdTests{
	
	sealed interface Shape{
		
		final class Circle implements Shape{ }
		
		final class Square implements Shape{ }
		
		final class Triangle implements Shape{ }
	}
	
	@Test
	void lookupOnlyToIDMustNotMutateSharedUniverse() throws Exception{
		var db = new IOTypeDB.MemoryOnlyDB.Basic();
		
		// Seed the Shape universe with exactly one known type (explicit registration).
		int circleId = toId(db, Shape.class, Shape.Circle.class, true);
		assertThat(db.fromID(Shape.class, circleId))
			.as("seeded universe resolves the registered type")
			.isEqualTo(Shape.Circle.class);
		assertThat(universeKeys(db, Shape.class))
			.as("universe contents before the lookup-only call")
			.containsExactly(Shape.Circle.class);
		
		// LOOKUP-ONLY call (record=false): a missing type must not be added.
		int triangleId = toId(db, Shape.class, Shape.Triangle.class, false);
		
		// With the bug, the missing type WAS inserted, so the returned id is live
		// and resolves back to Triangle — the mutation is observable via the public API.
		assertThat(db.fromID(Shape.class, triangleId))
			.as("inserted id resolves back to the type (mutation visible through public API)")
			.isEqualTo(Shape.Triangle.class);
		
		// KEY ASSERTION: record=false must leave the shared universe unmutated.
		// Fails because Basic.toID (IOTypeDB.java:199-200) ignores `record` and
		// calls universe.newId(type) on a cl2id miss (IOTypeDB.java:99-104).
		assertThat(universeKeys(db, Shape.class))
			.as("record=false must NOT add the unknown type to the shared universe")
			.doesNotContain(Shape.Triangle.class);
	}
	
	/**
	 * toID is declared {@code <T> toID(Class<T>, Class<T>, boolean)}; the subtype's
	 * Class literal cannot be cast to {@code Class<root>} (JLS 5.5.1), so the call
	 * goes through raw types. Production callers do the same via unchecked casts
	 * (CollectionAdapter.java:115).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static int toId(IOTypeDB.MemoryOnlyDB.Basic db, Class<?> root, Class<?> type, boolean record){
		return db.toID((Class)root, (Class)type, record);
	}
	
	/**
	 * MemUniverse and its cl2id map are private (IOTypeDB.java:90-105), so the
	 * universe contents are inspected via reflection, per the bug report
	 * ("inspect cl2id/id2cl").
	 */
	@SuppressWarnings("unchecked")
	private static Set<Class<?>> universeKeys(IOTypeDB.MemoryOnlyDB.Basic db, Class<?> root) throws Exception{
		Field sm = IOTypeDB.MemoryOnlyDB.Basic.class.getDeclaredField("sealedMultiverse");
		sm.setAccessible(true);
		Map<Class<?>, ?> multiverse = (Map<Class<?>, ?>)sm.get(db);
		Object universe = multiverse.get(root);
		assertThat(universe)
			.as("sealed universe for %s must exist", root)
			.isNotNull();
		Field cl2id = universe.getClass().getDeclaredField("cl2id");
		cl2id.setAccessible(true);
		return ((Map<Class<?>, Integer>)cl2id.get(universe)).keySet();
	}
}
