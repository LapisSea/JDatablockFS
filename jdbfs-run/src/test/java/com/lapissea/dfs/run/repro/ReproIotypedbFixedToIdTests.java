package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-29: IOTypeDB.MemoryOnlyDB.Fixed.toID(rootType, type, record) crashes with an opaque
 * NullPointerException when the sealed subtype is absent from the baked universe, even when
 * record=true.
 *
 * What the code is supposed to do:
 *   IOTypeDB.toID(rootType, type, record) is documented (IOTypeDB.java:55-59) as the way to
 *   convert a type to an ID, and the record flag means "register the type if it is missing".
 *   The mutable Basic implementation honours that (IOTypeDB.java:195-203 inserts via
 *   universe.newId(type) when absent). A Fixed DB is a read-only "bake" of a Basic DB
 *   (IOTypeDB.java:233-235), but PersistentDB.toID (IOTypeDB.java:846-885) still relies on
 *   the sealed toID contract for types not found in the baked universe (it calls
 *   builtIn.toID at :848 before its own record logic at :878-884), so a Fixed DB must either
 *   record the new subtype (record=true) or report "unknown" (record=false) — never NPE.
 *
 * What it actually does:
 *   IOTypeDB.java:370-375 — Fixed.toID ignores the `record` parameter entirely and returns
 *   `universe.cl2id.get(type)`, where cl2id is a Map<Class, Integer>. For a subtype that was
 *   not in the universe at bake time, get() returns null and the implicit unboxing to the
 *   int return type throws a NullPointerException with no message about the missing type.
 *
 * Why the test fails:
 *   The test bakes a Fixed DB whose sealed universe contains only Seal.A and Seal.B, then
 *   calls toID(Seal.class, Seal.C.class, true). Per the record=true contract C should be
 *   accepted (new id) or at worst reported as unknown; instead the auto-unbox at
 *   IOTypeDB.java:374 throws NullPointerException.
 */
public class ReproIotypedbFixedToIdTests{
	
	sealed interface Seal{
		
		final class A extends IOInstance.Managed<A> implements Seal{
			@IOValue
			int a;
			public A(){ }
			public A(int a){
				this.a = a;
			}
		}
		
		final class B extends IOInstance.Managed<B> implements Seal{
			@IOValue
			int b;
			public B(){ }
			public B(int b){
				this.b = b;
			}
		}
		
		// C is deliberately left out of the baked universe below
		final class C extends IOInstance.Managed<C> implements Seal{
			@IOValue
			int c;
			public C(){ }
			public C(int c){
				this.c = c;
			}
		}
	}
	
	/**
	 * The sealed toID is declared as toID(Class&lt;T&gt;, Class&lt;T&gt;, boolean), which javac refuses to
	 * apply to (root, subtype) pairs like (Seal.class, Seal.A.class). The API is erased at
	 * runtime, so call it through raw Class exactly like the production callers do
	 * (e.g. IOTypeDB.java:474-480 with SealedUniverse's Class&lt;T&gt; entries).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static int sealedToId(IOTypeDB db, Class root, Class sub, boolean record) throws java.io.IOException{
		return db.toID(root, sub, record);
	}
	
	@Test
	void fixedToIdRecordsMissingSealedSubtype() throws java.io.IOException{
		// Seed a mutable Basic DB with exactly two of the three permitted subtypes
		var basic = new IOTypeDB.MemoryOnlyDB.Basic();
		var idA   = sealedToId(basic, Seal.class, Seal.A.class, true);
		var idB   = sealedToId(basic, Seal.class, Seal.B.class, true);
		assertThat(idA).isNotEqualTo(idB);
		
		// Bake the Fixed (immutable) view: its sealed universe is a snapshot of {A, B} only
		var fixed = basic.bake();
		
		// Sanity: a subtype present in the baked universe still resolves to the same id
		assertThat(sealedToId(fixed, Seal.class, Seal.A.class, true)).isEqualTo(idA);
		
		// Bug: C is absent from the baked universe. record=true must not crash — the call
		// below is the key assertion: Fixed.toID (IOTypeDB.java:370-375) ignores `record`
		// and auto-unboxes cl2id.get(Seal.C.class) == null, throwing NullPointerException
		// instead of recording/returning an id for the new subtype.
		var idC = sealedToId(fixed, Seal.class, Seal.C.class, true);
		
		// If the contract were honoured (record=true) this id would be a fresh one beyond A/B
		assertThat(idC).isGreaterThan(idB);
	}
	
}
