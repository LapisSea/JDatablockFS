package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.exceptions.OutOfBitDepth;
import com.lapissea.dfs.objects.NumberSize;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-26 (chainio-setcap): {@code ChunkChainIO.setCapacity} mixes CAPACITY space and CONTENT
 * (size) space when shrinking a chain.
 *
 * What the code is supposed to do:
 *   setCapacity(n) on a chunk chain must trim the chain's CONTENT to its first n bytes: the
 *   chunk holding content position n is clamped to its local offset, all chunks after it are
 *   freed and everything before it is untouched (this is how RandomIO.trim() and the Dealloc
 *   action of SlowTests.fuzzChainResize shorten chains).
 *
 * What it actually does:
 *   ChunkChainIO.setCapacity (jdbfs-core/src/main/java/com/lapissea/dfs/core/chunk/ChunkChainIO.java:187-254)
 *   walks the chain accumulating {@code prev += chunk.getCapacity()} (line 232, capacity space)
 *   while computing each chunk's content span as {@code [prev, prev + chunk.getSize()]} (line 194,
 *   size space). The two only agree when every non-tail chunk is FULL (size == capacity) - an
 *   invariant that is enforced only under DEBUG_VALIDATION (MemoryManager.checkChainData,
 *   jdbfs-core .../core/MemoryManager.java:138-149), so it is not guaranteed.
 *   With a partial non-tail chunk the spans shift and land in the chunk's unused tail ("the gap"):
 *
 *   chain:  A(10/10) -> B(5/10) -> C(10/10)   (size/capacity), real content = 25 bytes
 *   real:   A=[0,10)   B=[10,15)  C=[15,25)
 *   buggy:  A=[0,10)   B=[10,15)  C=[20,30)   <- C's span starts at the sum of CAPACITIES (20)
 *
 *   * setCapacity(20): 20 matches C's buggy span with local offset 0, so C is clamped to size 0
 *     (ChunkChainIO.java:198-208) while B keeps its 5 bytes. The post-check getCapacity()=30 >= 20
 *     (ChunkChainIO.java:210-213, which sums capacities) passes, so no error is raised - but 5
 *     LIVE bytes (content 15..19, C's first half) are silently deleted: readback yields 15 bytes
 *     instead of the required 20.
 *   * setCapacity(16): 16 is a real content position (C's 2nd byte) but falls inside the gap
 *     (15,20) of the buggy spans, so no chunk matches; the loop exits with toGrow = 16-30 <= 0
 *     and the method returns without trimming anything (ChunkChainIO.java:237-240): readback
 *     still yields all 25 bytes instead of 16.
 *
 * Why this test fails:
 *   The test builds exactly the chain A(10/10) -> B(5/10) -> C(10/10) through public APIs
 *   (AllocateTicket + Chunk.setNextPtr, the same building blocks as SlowTests.ioMultiWrite and
 *   fuzzChainResize) and calls setCapacity(20) / setCapacity(16). With the defect present the
 *   readback is 15 bytes (live data lost) / 25 bytes (silent no-op) instead of the 20 / 16 bytes
 *   setCapacity must leave behind, so the assertions below fail.
 */
public class ReproChainIoSetCapTests {
	
	@Test
	void setCapacityShrinkClampsWrongChunkAndLosesLiveBytes() throws IOException, OutOfBitDepth{
		ChainHarness chain = ChainHarness.make();
		Chunk a = chain.a, b = chain.b, c = chain.c;
		
		// target = 20 = A.cap + B.cap: in the buggy capacity-space walk this is C's "content start"
		// (ChunkChainIO.java:232 accumulates CAPACITIES), so C gets clamped to 0. In the real
		// content space 20 is C's 5th byte (A=[0,10), B=[10,15), C=[15,25)), so C must be clamped
		// to 5 and the first 20 content bytes must survive.
		long target  = a.getCapacity() + b.getCapacity();
		long cClamp  = target - (a.getSize() + b.getSize());
		try(var io = a.io()){
			io.setCapacity(target);
		}
		
		byte[] actual = a.readAll();
		
		// BUG: readback is A(10) + B(5) + C(0) = 15 bytes - C's 5 live bytes (content 15..19) were
		// silently deleted by clamping the WRONG chunk (C to 0 instead of to 5), and the
		// getCapacity() >= target check (ChunkChainIO.java:210-213) passed because it sums
		// capacities (30), not sizes.
		assertThat(actual.length)
			.as("setCapacity(%d) on chain [%s] must leave exactly %d content bytes (C clamped to %d), " +
			    "but only %d bytes are left: the buggy walk (ChunkChainIO.java:194/232) clamped C to 0 " +
			    "instead of to %d, dropping live bytes",
			    target, chain.describe(), target, cClamp, actual.length, cClamp)
			.isEqualTo(Math.toIntExact(target));
		
		// BUG (content): the survivors must be the first 20 pattern bytes (1..20).
		assertThat(actual)
			.as("content after setCapacity(%d) must be the first %d pattern bytes, chain [%s]",
			    target, target, chain.describe())
			.isEqualTo(Arrays.copyOf(chain.fullContent, Math.toIntExact(target)));
	}
	
	@Test
	void setCapacityShrinkInGapIsSilentNoOp() throws IOException, OutOfBitDepth{
		ChainHarness chain = ChainHarness.make();
		Chunk a = chain.a, b = chain.b;
		
		// target = 16 = a real content position (C's 2nd byte: A=10, B=5 -> C starts at 15), but in
		// the buggy capacity-space walk the spans are A=[0,10), B=[10,15), C=[20,30), so 16 matches
		// NO chunk; the loop ends with toGrow = 16-30 <= 0 and setCapacity returns without doing
		// anything (ChunkChainIO.java:237-240).
		long target = a.getSize() + b.getSize() + 1;
		try(var io = a.io()){
			io.setCapacity(target);
		}
		
		byte[] actual = a.readAll();
		
		// BUG: nothing was trimmed - the chain still holds all 25 bytes instead of 16.
		assertThat(actual.length)
			.as("setCapacity(%d) on chain [%s] must trim the content to %d bytes, but the shrink was " +
			    "silently skipped: the target falls in the capacity-space gap after B " +
			    "(ChunkChainIO.java:232, 237-240)",
			    target, chain.describe(), target)
			.isEqualTo(Math.toIntExact(target));
		assertThat(actual)
			.as("content after setCapacity(%d) must be the first %d pattern bytes", target, target)
			.isEqualTo(Arrays.copyOf(chain.fullContent, Math.toIntExact(target)));
	}
	
	/**
	 * Builds the chain A(10/10) -> B(5/10) -> C(10/10) (size/capacity) on a fresh in-memory
	 * cluster, i.e. a chain with a PARTIAL NON-TAIL chunk - exactly the state the bug needs.
	 */
	private static final class ChainHarness{
		final Chunk  a, b, c;
		final byte[] fullContent;
		
		private ChainHarness(Chunk a, Chunk b, Chunk c, byte[] fullContent){
			this.a = a;
			this.b = b;
			this.c = c;
			this.fullContent = fullContent;
		}
		
		static ChainHarness make() throws IOException, OutOfBitDepth{
			var dp = (DataProvider)Cluster.emptyMem();
			
			// pattern[i] = i+1 so every surviving/lost byte is visible in a failure message
			byte[] pattern = new byte[25];
			for(int i = 0; i<pattern.length; i++) pattern[i] = (byte)(i + 1);
			
			Chunk a = allocChunk(dp, pattern, 0, 10, true);   // full:           size 10 / cap 10
			Chunk b = allocChunk(dp, pattern, 10, 5, true);   // PARTIAL non-tail: size 5 / cap 10
			Chunk c = allocChunk(dp, pattern, 15, 10, false); // full (tail):    size 10 / cap 10
			
			// Link A -> B -> C with the public Chunk API (cf. SlowTests.ioMultiWrite). A partial
			// chunk WITH a next pointer is precisely the state that DEBUG_VALIDATION's
			// checkChainData (MemoryManager.java:138-149) forbids in normal operation, which is
			// what keeps the setCapacity defect hidden.
			a.setNextPtr(b.getPtr());
			a.syncStruct();
			b.setNextPtr(c.getPtr());
			b.syncStruct();
			
			byte[] fullContent = a.readAll();
			
			// Harness preconditions: fail clearly here (as a harness problem) if the allocator
			// does not produce the exact layout the bug report assumes.
			assertThat(a.getCapacity()).as("harness: A capacity must be 10").isEqualTo(10);
			assertThat(a.getSize()).as("harness: A must be full (10/10)").isEqualTo(10);
			assertThat(b.getCapacity()).as("harness: B capacity must be 10").isEqualTo(10);
			assertThat(b.getSize())
			    .as("harness: B must be a PARTIAL non-tail chunk (5/10)")
			    .isEqualTo(5);
			assertThat(b.hasNextPtr()).as("harness: B must have a next chunk").isTrue();
			assertThat(c.getCapacity()).as("harness: C capacity must be 10").isEqualTo(10);
			assertThat(c.getSize()).as("harness: C must be full (10/10)").isEqualTo(10);
			assertThat(c.hasNextPtr()).as("harness: C must be the tail").isFalse();
			assertThat(fullContent)
			    .as("harness: pre-trim chain content must be the full 25-byte pattern")
			    .isEqualTo(pattern);
			
			return new ChainHarness(a, b, c, fullContent);
		}
		
		private static Chunk allocChunk(DataProvider dp, byte[] pattern, int off, int len, boolean hasNext) throws IOException{
			AllocateTicket ticket = AllocateTicket.bytes(10);
			// give the next-pointer field a real width (2 bytes) so it can be re-pointed later
			// with Chunk.setNextPtr (default would be a VOID/0-byte field)
			if(hasNext) ticket = ticket.withExplicitNextSize(Optional.of(NumberSize.SHORT));
			return ticket
			    .withDataPopulated(c -> c.write(false, Arrays.copyOfRange(pattern, off, off + len)))
			    .submit(dp);
		}
		
		/** e.g. "10/10 -> 5/10 -> 0/10 -> " (size/capacity per chunk) */
		String describe() throws IOException{
			var sb = new StringBuilder();
			for(Chunk ch = a; ch != null; ch = ch.next()){
				sb.append(ch.getSize()).append('/').append(ch.getCapacity()).append(" -> ");
			}
			return sb.toString();
		}
	}
}
