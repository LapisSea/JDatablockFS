package com.lapissea.dfs.run.repro;

/**
 * BUG-33 (sealed-unknown-id): reading a sealed id that does not exist in the
 * current type DB universe crashes with an opaque NullPointerException instead
 * of a clean malformed-data error.
 *
 * What the code is supposed to do:
 * An inline sealed field is serialized as [flag byte][universeID byte][payload of the chosen subtype].
 * On read, the universeID is resolved to a subtype via IOTypeDB.fromID(rootType, id) and the
 * subtype's StructPipe is looked up in the field's pipe map. When the id is not in the current
 * universe (version drift: a subtype was added/removed between writer and reader; or file
 * corruption), the read/skip must fail with a clean malformed-data error
 * (MalformedObject/IOException or MalformedStruct) that identifies the bad id.
 *
 * What actually happens:
 * IOTypeDB.fromID(rootType, id) returns null for an unknown id
 * (Basic: IOTypeDB.java:188-192, Synchronized delegates, Fixed: IOTypeDB.java:361-367), and the
 * read/skip paths pass that null straight into the pipe map lookup without a null check:
 *   - IOFieldInlineSealedObject.readNew: IOFieldInlineSealedObject.java:196-199
 *   - IOFieldInlineSealedObject.skip:    IOFieldInlineSealedObject.java:222-226
 *   - CollectionAdapter read/skip:       CollectionAdapter.java:145-146, 152-153 (same mechanism)
 * The pipe map is an immutable Map.of (built at SealedUtil.java:43), so typeToPipe.get(null)
 * itself throws NullPointerException ("Cannot invoke Object.hashCode() because pk is null") -
 * an opaque crash with no message tying it to the malformed data (and even with a null-tolerant
 * map, the null pipe would be invoked on the very next line).
 *
 * Why the tests fail:
 * Each test registers the "current" universe {A, B} in a fresh in-memory (VerySimple) provider,
 * serializes a valid Holder, then hand-crafts a corrupted stream whose universeID byte is an id
 * that is not in the universe (99), and asserts that reading/skipping it fails with a clean
 * malformed-data error and no NullPointerException. With the bug present the pipe crashes with
 * an (opaque) NullPointerException, so the assertions fail and name the mechanism.
 *
 * Note: B deliberately has a different payload size than A (two ints vs one). If all subtypes
 * had the same size, the sealed field would get a FIXED size descriptor and skip() would take
 * the optionallySkipExact fast path (IOFieldInlineSealedObject.java:210) without ever resolving
 * the id - the buggy skip lines would be unreachable. With differing sizes both read and skip
 * resolve the id and hit the bug.
 *
 * knownSealedId_roundTrips is a positive control proving the harness (encoding, reader,
 * provider) is correct: known ids round-trip fine, so the failures in the other tests are
 * specific to the unknown-id path.
 */
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ReproSealedUnknownIdTests{
	
	/** The sealed universe of the "reader side": only A and B are known. */
	sealed interface Seal{
		
		final class A extends IOInstance.Managed<A> implements Seal{
			@IOValue
			int a;
			public A(){ }
			public A(int a){
				this.a = a;
			}
		}
		
		/** Two ints on purpose: a different payload size than A (see class comment). */
		final class B extends IOInstance.Managed<B> implements Seal{
			@IOValue
			int x;
			@IOValue
			int y;
			public B(){ }
			public B(int x, int y){
				this.x = x;
				this.y = y;
			}
		}
	}
	
	/** A managed struct with a single non-null inline sealed field: [universeID][subtype payload]. */
	static final class Holder extends IOInstance.Managed<Holder>{
		@IOValue
		Seal seal;
		public Holder(){ }
		public Holder(Seal seal){
			this.seal = seal;
		}
	}
	
	/** A sealed id that is not in the universe (only A and B are registered). */
	private static final int UNKNOWN_ID = 99;
	
	/** The ids of the registered universe: id of A and id of B. */
	private record UniverIds(int a, int b){ }
	
	/**
	 * Creates a fresh in-memory provider and registers the current universe {A, B}.
	 * (The unchecked casts mirror IOFieldInlineSealedObject.java:140-141, which registers the same way.)
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static UniverIds registerUniverse(DataProvider provider) throws IOException{
		var idA = provider.getTypeDb().toID(Seal.class, (Class<Seal>)(Class<?>)Seal.A.class, true);
		var idB = provider.getTypeDb().toID(Seal.class, (Class<Seal>)(Class<?>)Seal.B.class, true);
		assertTrue(idA != idB && idA > 0 && idB > 0, "harness check: A and B must get distinct ids, got A=" + idA + " B=" + idB);
		return new UniverIds(idA, idB);
	}
	
	/** Serializes a Holder with the given sealed value using the production pipe. */
	private static byte[] writeHolder(StandardStructPipe<Holder> pipe, DataProvider provider, Seal val) throws IOException{
		var out = new ContentOutputBuilder(16);
		pipe.write(provider, out, new Holder(val));
		return out.toByteArray();
	}
	
	/**
	 * Hand-crafts a corrupted stream: takes a valid serialized Holder and rewrites its universeID
	 * value byte to an id that is absent from the universe.
	 *
	 * Stream layout (verified by the self-checks below): [flag byte][universeID value byte][subtype payload].
	 * The flag byte only encodes the size class of the id (here 1 byte, upper bits filled with ones),
	 * so any other 1-byte id (0..255) can be substituted under the same flag without changing the
	 * stream length; the payload after the id is left untouched.
	 */
	private static byte[] corruptUniverseId(byte[] valid, int knownId, int unknownId){
		// harness self-check: the universeID must be the 2nd byte of the stream
		assertTrue(valid.length >= 5, "harness check: stream too short: " + Arrays.toString(valid));
		assertEquals(valid[1] & 0xFF, knownId, "harness check: the universeID must be the 2nd byte of the stream, got " + Arrays.toString(valid));
		// harness self-check: both ids must be BYTE-sized so the flag byte stays valid
		assertTrue(unknownId >= 0 && unknownId < 256, "harness check: unknown id must fit in one byte like the known id");
		
		var corrupted = valid.clone();
		corrupted[1] = (byte)unknownId;
		return corrupted;
	}
	
	/** Returns the first NullPointerException in the exception chain (or null if there is none). */
	private static Throwable findNpe(Throwable t){
		for(var e = t; e != null; e = (e.getCause() == e)? null : e.getCause()){
			if(e instanceof NullPointerException) return e;
		}
		return null;
	}
	
	private static String describe(Throwable t){
		return t == null? "no exception" : t.getClass().getName() + (t.getMessage() != null? ": " + t.getMessage() : "");
	}
	
	/**
	 * Positive control: sealed ids that are in the universe round-trip through the same
	 * production read path. Proves the harness (encoding, reader, provider) is correct, so the
	 * failures in the other tests are attributable to the unknown-id mechanism, not the setup.
	 */
	@Test
	public void knownSealedId_roundTrips() throws IOException{
		var provider = DataProvider.newVerySimpleProvider();
		registerUniverse(provider);
		var pipe     = StandardStructPipe.of(Holder.class);
		
		var backA = pipe.readNew(provider, new ContentInputStream.BA(writeHolder(pipe, provider, new Seal.A(42))), null);
		assertEquals(backA, new Holder(new Seal.A(42)), "a sealed id that is in the universe must round-trip (A)");
		
		var backB = pipe.readNew(provider, new ContentInputStream.BA(writeHolder(pipe, provider, new Seal.B(7, 9))), null);
		assertEquals(backB, new Holder(new Seal.B(7, 9)), "a sealed id that is in the universe must round-trip (B)");
	}
	
	/**
	 * BUG-33: reading a sealed id absent from the universe must fail with a clean malformed-data
	 * error. Currently IOTypeDB.fromID(Seal.class, 99) returns null and
	 * IOFieldInlineSealedObject.readNew (IOFieldInlineSealedObject.java:196-199) passes it to
	 * typeToPipe.get(null) without a null check -> opaque NullPointerException.
	 */
	@Test
	public void unknownSealedId_read_mustFailCleanly() throws IOException{
		var provider = DataProvider.newVerySimpleProvider();
		var ids      = registerUniverse(provider);
		var pipe     = StandardStructPipe.of(Holder.class);
		
		var corrupted = corruptUniverseId(writeHolder(pipe, provider, new Seal.A(42)), ids.a(), UNKNOWN_ID);
		
		Exception thrown = null;
		try{
			pipe.readNew(provider, new ContentInputStream.BA(corrupted), null);
		}catch(Exception e){
			thrown = e;
		}
		assertNotNull(thrown, "reading a sealed id absent from the universe must not succeed");
		
		// The failure must not be an opaque NullPointerException crash (raw or wrapped).
		var npe = findNpe(thrown);
		assertNull(npe,
		           "BUG-33: reading sealed id " + UNKNOWN_ID + " (absent from the universe) must fail with a clean " +
		           "malformed-data error, but it crashes with an opaque NullPointerException: " + describe(npe) +
		           ". Mechanism: IOTypeDB.fromID(rootType, id) returns null (IOTypeDB.java:188-192) and " +
		           "IOFieldInlineSealedObject.readNew passes it to typeToPipe.get(null) without a null check " +
		           "(IOFieldInlineSealedObject.java:196-199); the immutable pipe map (SealedUtil.java:43) NPEs on get(null).");
		
		// And it must be a clean malformed-data error identifying the bad data.
		assertTrue(thrown instanceof IOException || thrown instanceof MalformedStruct,
		           "BUG-33: reading sealed id " + UNKNOWN_ID + " (absent from the universe) must fail with a clean " +
		           "malformed-data error (MalformedObject/IOException or MalformedStruct), but got: " + describe(thrown));
	}
	
	/**
	 * BUG-33: skipping a sealed id absent from the universe must fail with a clean malformed-data
	 * error. Same mechanism as the read path, via IOFieldInlineSealedObject.skip
	 * (IOFieldInlineSealedObject.java:222-226) and CollectionAdapter.java:152-153.
	 */
	@Test
	public void unknownSealedId_skip_mustFailCleanly() throws IOException{
		var provider = DataProvider.newVerySimpleProvider();
		var ids      = registerUniverse(provider);
		var pipe     = StandardStructPipe.of(Holder.class);
		
		var corrupted = corruptUniverseId(writeHolder(pipe, provider, new Seal.B(7, 9)), ids.b(), UNKNOWN_ID);
		
		Exception thrown = null;
		try{
			pipe.skip(provider, new ContentInputStream.BA(corrupted), null);
		}catch(Exception e){
			thrown = e;
		}
		assertNotNull(thrown, "skipping a sealed id absent from the universe must not succeed");
		
		// The failure must not be an opaque NullPointerException crash (raw or wrapped).
		var npe = findNpe(thrown);
		assertNull(npe,
		           "BUG-33: skipping sealed id " + UNKNOWN_ID + " (absent from the universe) must fail with a clean " +
		           "malformed-data error, but it crashes with an opaque NullPointerException: " + describe(npe) +
		           ". Mechanism: IOTypeDB.fromID(rootType, id) returns null (IOTypeDB.java:188-192) and " +
		           "IOFieldInlineSealedObject.skip passes it to typeToPipe.get(null) without a null check " +
		           "(IOFieldInlineSealedObject.java:222-226); the immutable pipe map (SealedUtil.java:43) NPEs on get(null).");
		
		// And it must be a clean malformed-data error identifying the bad data.
		assertTrue(thrown instanceof IOException || thrown instanceof MalformedStruct,
		           "BUG-33: skipping sealed id " + UNKNOWN_ID + " (absent from the universe) must fail with a clean " +
		           "malformed-data error (MalformedObject/IOException or MalformedStruct), but got: " + describe(thrown));
	}
}
