package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.core.MoveBuffer;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.RefField;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-28 (defrag-move-ref): DefragmentManager.moveReference leaves non-zero-offset
 * references dangling when it moves a chunk.
 *
 * What the code SHOULD do:
 *   When a chunk is moved (its data is copied to a new location and the source is freed),
 *   every reference that points into that chunk must be rewritten to point at the new
 *   location while preserving each reference's own offset. The correct implementation is
 *   DefragmentManager.moveChunkExact (DefragmentManager.java:520) which does:
 *       field.setReference(instance, Reference.of(newChunk, valueReference.getOffset()));
 *
 * What it ACTUALLY does:
 *   The public moveReference (DefragmentManager.java:125) -- the one used by defragment()
 *   -- skips any reference whose offset is non-zero (DefragmentManager.java:144:
 *       "if(!ptr.equals(toMove) || valueReference.getOffset() != 0) return CONTINUE;").
 *   It only rewrites offset-0 references (DefragmentManager.java:166). A non-zero-offset
 *   reference is therefore never updated, yet the source chunk is still freed
 *   (DefragmentManager.java:216), so the reference dangles into memory that is now free
 *   (and can be handed out again).
 *
 * How this test triggers it:
 *   A single root object {@link Duo} holds two @IOValue.Reference {@link Payload} fields that
 *   share ONE chunk C: {@code a} lives at offset 0 and {@code b} at a non-zero offset k.
 *   The reference walk visits fields in (alphabetical) order a < a:ref < b < b:ref, so the
 *   offset-0 reference {@code a} is hit first. It triggers the copy of C, the source chunk C
 *   is added to the free list, and the walk terminates (END|SAVE) -- so {@code b}'s
 *   non-zero-offset reference is never visited and is left pointing at the now-freed (C, k).
 *   The test then allocates a new chunk (the allocator reuses the freed C) and overwrites
 *   offset k with a sentinel payload. Reading {@code b} therefore yields the sentinel instead
 *   of the original payload, demonstrating the dangling reference.
 */
public class ReproDefragMoveRefTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	@IOValue
	public static class Payload extends IOInstance.Managed<Payload>{
		@IOValue long value;
		
		public Payload(){ }
		public Payload(long value){ this.value = value; }
	}
	
	public static class Duo extends IOInstance.Managed<Duo>{
		@IOValue @IOValue.Reference Payload a;
		@IOValue @IOValue.Reference Payload b;
		
		public Duo(){ }
		public Duo(Payload a, Payload b){
			this.a = a;
			this.b = b;
		}
	}
	
	@Test(timeOut = 60_000)
	public void nonZeroOffsetReferenceDanglesAfterChunkMove() throws Exception{
		var cluster = Cluster.emptyMem();
		
		var A = new Payload(111L);
		var B = new Payload(222L);
		
		// k = serialized size of one Payload -> the offset at which B sits inside the shared chunk
		var k = StandardStructPipe.sizeOfUnknown(cluster, A, WordSpace.BYTE);
		assertThat(k).as("serialized size of a single Payload").isGreaterThan(0);
		
		// One shared chunk C, large enough to hold both payloads back-to-back (A at 0, B at k)
		var C = AllocateTicket.bytes(2 * k + 64).submit(cluster);
		
		var         duo       = new Duo(A, B);
		var         duoStruct = Struct.of(Duo.class);
		@SuppressWarnings("unchecked")
		var refA = (RefField<Duo, Payload>)duoStruct.getFields().requireExact(Payload.class, "a");
		@SuppressWarnings("unchecked")
		var refB = (RefField<Duo, Payload>)duoStruct.getFields().requireExact(Payload.class, "b");
		
		// Point the two reference companions into the SAME chunk C: a at offset 0, b at offset k
		refA.setReference(duo, Reference.of(C.getPtr(), 0));
		refB.setReference(duo, Reference.of(C.getPtr(), k));
		
		// Persist the root object. This writes A into (C,0), B into (C,k) and stores the two companions.
		cluster.roots().provide("duo", duo);
		
		var payloadPipe = StandardStructPipe.of(Payload.class);
		
		// Sanity: both payloads are readable from their (shared-chunk) locations before the move.
		assertThat(Reference.of(C.getPtr(), 0).readNew(cluster, payloadPipe, null).value)
		               .as("A at offset 0 before the move").isEqualTo(111L);
		assertThat(Reference.of(C.getPtr(), k).readNew(cluster, payloadPipe, null).value)
		               .as("B at offset k before the move").isEqualTo(222L);
		
		// Move chunk C. The reference walk visits fields in order a < b. The offset-0 reference (a)
		// is hit first: it copies C to a new chunk, frees C and terminates the walk (END|SAVE) --
		// so b's non-zero-offset reference is never examined and is left pointing at the freed C.
		MoveBuffer move = DefragmentManager.moveReference(cluster, C.getPtr(), t -> t, true);
		assertThat(move.hasAny()).as("chunk C must actually be moved by moveReference").isTrue();
		var newC = move.toDest(C.getPtr()).orElseThrow();
		
		// a (offset 0) was rewritten to the new chunk; C itself was freed.
		assertThat(newC).as("C must have been copied to a new location and the old one freed").isNotEqualTo(C.getPtr());
		
		// Reuse the freed chunk: the allocator hands the same memory (C) back as a new chunk D...
		var D = AllocateTicket.bytes(C.getCapacity()).submit(cluster);
		assertThat(D.getPtr()).as("the freed chunk C is reused by the allocator").isEqualTo(C.getPtr());
		
		// ...and overwrite B's slot (offset k) with a sentinel payload, as any later allocation would.
		// D is freshly allocated so its current size is 0; the write at offset 0 extends it to k,
		// after which the write at offset k (B's slot) succeeds.
		var sentinel = new Payload(999L);
		Reference.of(D.getPtr(), 0).write(cluster, false, payloadPipe, sentinel);
		Reference.of(D.getPtr(), k).write(cluster, false, payloadPipe, sentinel);
		
		// BUG: b's reference (C, k) was never updated (DefragmentManager.java:144 skips non-zero
		// offsets), so it still points at the freed-and-reused chunk. Reading it now yields the
		// sentinel that overwrote B's slot -- not the original payload 222.
		assertThat(Reference.of(C.getPtr(), k).readNew(cluster, payloadPipe, null).value)
		               .as("b should still be the original payload 222, but its reference dangles into reused memory")
		               .isEqualTo(222L); // fails: actually reads 999 (the sentinel)
	}
}
