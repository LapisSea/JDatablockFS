package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction for BUG-51 (prim-globalsize): a user-managed {@code @IODependency.NumSize}
 * companion that is too small for the value causes SILENT TRUNCATION on write instead of an error.
 *
 * <p>What the code is supposed to do:
 * A managed numeric field annotated with {@code @IODependency.NumSize("name")} is serialized using
 * the {@link NumberSize} stored in the companion field {@code name} (which the user manages).
 * If that size cannot hold the value, the write must fail with {@code VaryingSize.TooSmall} —
 * exactly what the non-dynamic branch does via {@code VaryingSize.safeSize}
 * (jdbfs-core .../type/field/VaryingSize.java:364-370), which throws when the needed size
 * is greater than the allowed one.
 *
 * <p>What it actually does:
 * {@code IOFieldPrimitive.getSafeSize} (jdbfs-core .../type/field/fields/reflection/IOFieldPrimitive.java:1543-1545)
 * short-circuits whenever a dynamic (user-managed) size companion exists: it returns the stored
 * companion {@link NumberSize} and never checks that the value fits in it (the computed
 * {@code neededNum} is ignored). {@code DynamicFieldSize.apply} (IOFieldPrimitive.java:1491-1496)
 * only checks that the stored size is inside the field's allowed set — BYTE is a perfectly
 * "allowed" size for an int, so no error is raised. The write then goes through
 * {@code NumberSize.write} (jdbfs-core .../objects/NumberSize.java:307-320), whose range check
 * ({@code validateUnsigned}) only runs when {@code GlobalConfig.DEBUG_VALIDATION} is on.
 * With validation off (release mode) {@code BYTE.write(out, 500)} just emits the low byte
 * ({@code ContentWriter.writeInt1 -> write(v)}), and the read side reads back that same byte
 * with the same (too small) size. Result: 500 round-trips as 244 (500 &amp; 0xFF) with no error
 * anywhere. The same shape exists for chunk pointers in
 * {@code IOFieldChunkPointer.getSafeSize} (jdbfs-core .../fields/reflection/IOFieldChunkPointer.java:98-101).
 *
 * <p>Why the test fails:
 * The test type mirrors the production Chunk header pattern (jdbfs-core .../core/chunk/Chunk.java:208-226:
 * a {@code @IOValue NumberSize} companion + a {@code @IODependency.NumSize} numeric field).
 * Writing {@code value=500} with the user-managed companion set to BYTE must either throw
 * (TooSmall) or round-trip unchanged. Instead the round-trip "succeeds" and the read-back value
 * is 244, so the assertion "read value == 500" fails. The control test (companion size that fits)
 * round-trips exactly, proving the harness itself is correct.
 *
 * <p>Preconditions (both enforced at run time, not in code):
 * <ol>
 * <li>{@code GlobalConfig.DEBUG_VALIDATION} (jdbfs-core .../config/GlobalConfig.java:6) is
 * {@code ConfigDefs.deb()} = {@code ConfigDefs.class.desiredAssertionStatus()}
 * (jdbfs-core .../config/ConfigDefs.java, deb()), i.e. the JVM assertion status. The test must
 * therefore be run with assertions DISABLED in the forked JVM: add
 * {@code -DenableAssertions=false} to the mvn command. The static block below guards this
 * precondition with an actionable message (with assertions on, the write would throw
 * {@code validateUnsigned}'s error instead of silently truncating — the guard working as
 * designed, not the reported bug).</li>
 * <li>This package is not opened by the JDatablockFS.run module-info, so the struct's
 * reflective field scan needs: add
 * {@code -DargLine="--add-opens JDatablockFS.run/com.lapissea.dfs.run.repro=JDatablockFS.core"}
 * to the mvn command.</li>
 * </ol>
 */
public class ReproPrimGlobalSizeTests{
	
	@BeforeClass
	void checkDebugValidation(){
		assertThat(GlobalConfig.DEBUG_VALIDATION)
			.as("This test requires GlobalConfig.DEBUG_VALIDATION to be OFF (release-mode write path). "
			    + "Run mvn with -DenableAssertions=false (DEBUG_VALIDATION derives from the JVM assertion status).")
			.isFalse();
	}
	
	/**
	 * Mirrors the production Chunk header (Chunk.java:208-226): a user-managed
	 * {@link NumberSize} companion field plus an unsigned numeric field that references it
	 * through {@code @IODependency.NumSize}.
	 */
	public static class NumSizeTestType extends IOInstance.Managed<NumSizeTestType>{
		
		@IOValue
		private NumberSize valueSize;
		
		@IOValue
		@IOValue.Unsigned
		@IODependency.NumSize("valueSize")
		private int value;
		
		public NumSizeTestType(){ }
	}
	
	/**
	 * Plain write-then-read round trip through a StandardStructPipe on an in-memory chunk.
	 * Any failure here is a harness problem and is wrapped so it is not mistaken for the bug.
	 */
	private static <T extends IOInstance<T>> T roundTrip(Class<T> type, T obj){
		try{
			var prov  = DataProvider.newVerySimpleProvider();
			var pipe  = StandardStructPipe.of(type);
			var chunk = AllocateTicket.bytes(128).submit(prov);
			try(var io = chunk.io()){
				pipe.write(prov, io, obj);
				io.trim();
			}
			return pipe.readNew(prov, chunk, null);
		}catch(IOException e){
			throw new UncheckedIOException("Harness round-trip failed before the assertion under test", e);
		}
	}
	
	@Test
	void controlProperSizeRoundTrips(){
		var obj = new NumSizeTestType();
		obj.value = 500;
		obj.valueSize = NumberSize.SHORT;//500 fits an unsigned short (needs >= 2 bytes) -> no truncation possible
		var read = roundTrip(NumSizeTestType.class, obj);
		
		//Control: with a user-managed companion that fits the value, the dynamic-size path
		//round-trips exactly — proves the harness works and the failure below is bug-specific.
		assertThat(read.value)
			.as("value must round-trip unchanged when the user-managed NumSize fits")
			.isEqualTo(500);
		assertThat(read.valueSize)
			.as("companion size must round-trip unchanged")
			.isEqualTo(NumberSize.SHORT);
	}
	
	@Test
	void userManagedNumSizeTooSmallSilentlyTruncates(){
		var obj = new NumSizeTestType();
		obj.value = 500;
		obj.valueSize = NumberSize.BYTE;//TOO SMALL: unsigned BYTE holds 0..255, 500 needs >= 2 bytes
		var read = roundTrip(NumSizeTestType.class, obj);
		
		//BUG: getSafeSize (IOFieldPrimitive.java:1543-1545) returns the stored BYTE companion
		//without any fit check (the non-dynamic branch would throw VaryingSize.TooSmall here),
		//and NumberSize.write (NumberSize.java:307-320) only range-checks when
		//DEBUG_VALIDATION is on — so 500 is written as its low byte and read back as 244.
		//Expected: the write throws TooSmall, or 500 round-trips. Actual: silent 500 -> 244.
		assertThat(read.value)
			.as("value 500 written with a too-small (BYTE) user-managed NumSize must not silently change on round-trip")
			.isEqualTo(500);
	}
}
