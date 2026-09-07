package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.SizeDescriptor;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;

/*
 * BUG-47 (structpipe-intcast) reproduction.
 *
 * [supposed behaviour]
 * StructPipe#writeIOFields (jdbfs-core .../io/instancepipe/StructPipe.java:733-735) buffers the
 * write when the destination is a "direct" writer (ChunkChainIO#isDirect() is always true, so
 * this is every chunk write). The buffer is pre-sized from the struct's long-based predicted
 * allocation:
 *     var siz    = getSizeDescriptor().calcAllocSize(WordSpace.BYTE); // long
 *     destBuff   = new ContentOutputBuilder((int)siz);                // long -> int
 * A single object whose allocation exceeds 2GiB must fail with a clean, descriptive error —
 * never with an internal int-overflow artifact.
 *
 * [actual behaviour]
 * For any predicted allocation above Integer.MAX_VALUE, (int)siz wraps negative and
 * ContentOutputBuilder(int) -> ByteArrayOutputStream(int) -> new byte[negative] dies with an
 * obscure constructor-level exception (NegativeArraySizeException, or the JDK guard
 * IllegalArgumentException "Negative initial size") instead of a clean error.
 *
 * [why this test fails]
 * No annotation-driven field type can produce a struct with a >2GiB size descriptor, so the test
 * drives the real public API StructPipe#write with the same descriptor class the library itself
 * produces for fixed-size structs (SizeDescriptor.Fixed, see StructPipe#createSizeDescriptor) —
 * here 2.2e9 bytes — and writes through a real chunk (Chunk.io() -> ChunkChainIO, isDirect == true).
 * The instance is a Blob (unmanaged) so the DEBUG_VALIDATION wrapper
 * (StructPipe#validateAndSafeDestination, StructPipe.java:765-790) leaves the destination
 * untouched and the buggy branch is reached in both validation modes. The write currently dies
 * with the int-overflow artifact; the test treats that as a failure. It passes once the >2GiB
 * case is handled cleanly (write succeeds or a descriptive error is thrown).
 */
public class ReproStructPipeIntCastTests{
	
	static final long OVER_2GB = 2_200_000_000L; // > Integer.MAX_VALUE (2_147_483_647)
	
	static final class OversizedPipe extends StandardStructPipe<Blob>{
		
		OversizedPipe(Struct<Blob> struct){
			super(struct, StructPipe.STATE_DONE);
		}
		
		// StructPipe#createSizeDescriptor normally returns SizeDescriptor.Fixed for fixed-size
		// structs. No built-in field type can reach >2GiB, so we supply that same descriptor
		// class with the >2GiB predicted allocation that BUG-47 says is mishandled.
		@Override
		protected SizeDescriptor<Blob> createSizeDescriptor(){
			return SizeDescriptor.Fixed.of(WordSpace.BYTE, OVER_2GB);
		}
	}
	
	@Test
	public void oversizedObjectThroughDirectWritePath() throws IOException{
		var struct = Struct.of(Blob.class, StructPipe.STATE_DONE);
		var pipe   = new OversizedPipe(struct);
		
		// harness self-check: the predicted allocation must actually exceed the int range
		var predicted = pipe.getSizeDescriptor().calcAllocSize(WordSpace.BYTE);
		Assert.assertEquals(predicted, OVER_2GB);
		Assert.assertTrue(predicted > Integer.MAX_VALUE, "harness: predicted size must be > 2GiB");
		
		var provider = DataProvider.newVerySimpleProvider();
		try(var identity = AllocateTicket.bytes(128).submitAsTempMem(provider)){
			var blob = new Blob(provider, identity.chunk(), IOType.of(Blob.class));
			try(var destination = AllocateTicket.bytes(128).submitAsTempMem(provider)){
				// Chunk.io() is a ChunkChainIO whose isDirect() == true — this is what triggers
				// the buffering branch at StructPipe.java:733-735
				try(var io = destination.chunk().io()){
					try{
						pipe.write(provider, io, blob);
						// if it ever succeeds, the >2GiB case is handled properly: fine
					}catch(NegativeArraySizeException e){
						// BUG: the (int) cast of calcAllocSize overflows -> negative buffer size
						Assert.fail("BUG-47: writing an object whose predicted allocation is " + predicted
						           + " bytes through a direct writer died with NegativeArraySizeException "
						           + "(int-cast overflow at StructPipe.java:735) instead of a clean error");
					}catch(IllegalArgumentException e){
						if(String.valueOf(e.getMessage()).contains("Negative initial size")){
							// same int-overflow mechanism; the JDK guards the negative size in
							// ByteArrayOutputStream(int) before new byte[negative]
							Assert.fail("BUG-47: writing an object whose predicted allocation is " + predicted
							           + " bytes through a direct writer died with '" + e.getMessage() + "' "
							           + "(int-cast overflow at StructPipe.java:735) instead of a clean error");
						}
						throw e; // unrelated IAE: surface it as a harness problem
					}catch(Exception clean){
						// a clean, descriptive failure is acceptable behaviour
					}
				}
			}
		}
	}
}
