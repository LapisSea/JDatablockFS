package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BUG-54 fbyte-poolswap — reproduction attempt, expected outcome: FALSE POSITIVE (latent code smell, no behavioral bug).
 *
 * What the code is supposed to do:
 *   IOField#instancesEqual(ioPool1, inst1, ioPool2, inst2) must compare inst1's value in inst1's pool context
 *   (ioPool1) against inst2's value in inst2's pool context (ioPool2). Every sibling primitive field pairs
 *   pool with instance correctly, e.g. FShortBoxed (IOFieldPrimitive.java:1133-1135), FByteBoxed
 *   (IOFieldPrimitive.java:1276-1278) and all other F* siblings at lines 239, 310, 395, 462, 562, 611, 717,
 *   908, 960, 1066, 1339, 1431.
 *
 * What the code actually does:
 *   IOFieldPrimitive.FByte#instancesEqual (IOFieldPrimitive.java:1209-1211) SWAPS the pools:
 *       return getValue(ioPool2, inst1) == getValue(ioPool1, inst2);
 *   i.e. inst1 is read in inst2's pool context and vice versa.
 *
 * Why the test does NOT fail (FALSE POSITIVE):
 *   For a plain primitive byte, FByte#getValue(ioPool, instance) delegates to
 *   FieldAccessor#getByte(ioPool, instance), which for an ID_BYTE field resolves to
 *   ExactFieldAccessor#getExactByte reading the instance field straight via a VarHandle
 *   (VarHandleAccessor.java:206-208) and never touching ioPool. A plain byte has no virtual state
 *   (not nullable, no NumberSize — FByteBase#allowedSizes is only BYTE, IOFieldPrimitive.java:1153-1156),
 *   so the pool is dead weight on this path. The swap therefore has NO observable effect: the test below
 *   drives instancesEqual with two distinct, counting, value-poisoning pools and shows
 *   (a) the equality results are still correct (they would be WRONG if values came from the pools), and
 *   (b) both pools record zero accesses, proving the pools are never consulted.
 *   The report is thus a latent copy-paste hazard (it would misbehave in a pool-dependent field), not an
 *   active behavioral bug.
 */
public class ReproFBytePoolSwapTests{
	
	/** Minimal managed struct with a single plain (non-nullable) byte field → compiled to FByte. */
	public static class ByteHolder extends IOInstance.Managed<ByteHolder>{
		// Grant the field-access machinery a lookup from this class' own package/module
		// (the library's recommended pattern for user structs, cf. TemplateClassLoader.java:238).
		static{ allowFullAccess(MethodHandles.lookup()); }
		
		@IOValue
		byte b;
	}
	
	/**
	 * A VarPool that counts every non-object-method call and poisons getByte() with a fixed value:
	 * if FByte ever consulted the pool, the compared bytes would be the poison, not the instance's field.
	 */
	@SuppressWarnings("unchecked")
	private static VarPool<ByteHolder> countingPool(byte poisonByte, AtomicInteger accesses){
		InvocationHandler handler = (proxy, method, args) -> switch(method.getName()){
			case "toString" -> "CountingPool";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals"   -> proxy == args[0];
			default -> {
				accesses.incrementAndGet();
				yield switch(method.getReturnType().getName()){
					case "byte"    -> poisonByte;
					case "long"    -> 0L;
					case "int"     -> 0;
					case "boolean" -> false;
					default        -> null;
				};
			}
		};
		return (VarPool<ByteHolder>)Proxy.newProxyInstance(VarPool.class.getClassLoader(), new Class<?>[]{VarPool.class}, handler);
	}
	
	@Test
	void fBytePoolSwapHasNoObservableEffect(){
		var struct = Struct.of(ByteHolder.class, StagedInit.STATE_DONE);
		var field  = struct.getFields().requireByName("b");
		// We must be testing exactly the class whose instancesEqual swaps the pools (IOFieldPrimitive.java:1209-1211).
		// Matched by name because com.lapissea.dfs.type.field.fields.reflection is not exported to this test module.
		Assert.assertEquals(field.getClass().getName(),
			"com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive$FByte",
			"expected the plain byte field to compile to FByte, got: " + field.getClass().getName());
		
		var a         = new ByteHolder(); a.b = 7;
		var different = new ByteHolder(); different.b = 9;
		var same      = new ByteHolder(); same.b = 7;
		
		var accA = new AtomicInteger();
		var accB = new AtomicInteger();
		// Two DISTINCT pools. If FByte read values from them, getByte() would return the poison 42
		// and the 7-vs-9 comparison below would incorrectly come back equal.
		var poolA = countingPool((byte)42, accA);
		var poolB = countingPool((byte)42, accB);
		
		// The buggy line computes getValue(ioPool2, inst1) == getValue(ioPool1, inst2).
		// Correct result requires reading inst1's own byte (7) vs inst2's own byte (9):
		Assert.assertFalse(field.instancesEqual(poolA, a, poolB, different),
			"7 vs 9 must not be equal — a pool-based (or swapped-pool) read would wrongly report equality");
		// Same value → equal
		Assert.assertTrue(field.instancesEqual(poolA, a, poolB, same), "7 vs 7 must be equal");
		
		// Even with the pool order reversed by the caller, the outcome must be unchanged:
		// for FByte the swapped pools are unobservable, so the bug is behaviorally inert.
		Assert.assertFalse(field.instancesEqual(poolB, a, poolA, different), "7 vs 9 must not be equal (reversed pools)");
		Assert.assertTrue(field.instancesEqual(poolB, a, poolA, same), "7 vs 7 must be equal (reversed pools)");
		
		// Mechanism evidence: FByte never consults the pool for a plain byte (accessor reads the
		// instance field directly), which is precisely why the swap at IOFieldPrimitive.java:1210 is a no-op.
		Assert.assertEquals(accA.get(), 0, "pool A was consulted during FByte equality — swap would then be observable");
		Assert.assertEquals(accB.get(), 0, "pool B was consulted during FByte equality — swap would then be observable");
		
		// The public IOInstance#equals path (passes null pools, IOInstance.java:351) is also correct:
		Assert.assertFalse(a.equals(different), "IOInstance.equals must see 7 != 9");
		Assert.assertTrue(a.equals(same), "IOInstance.equals must see 7 == 7");
	}
}
