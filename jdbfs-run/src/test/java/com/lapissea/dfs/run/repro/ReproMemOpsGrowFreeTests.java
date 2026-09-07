package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import org.testng.annotations.Test;

import java.util.List;

/**
 * BUG-53 (memops-growfree)
 *
 * <h3>Reported defect</h3>
 * <p>
 * {@code MemoryOperations.growFreeAlloc} (jdbfs-core .../core/memory/MemoryOperations.java:901-908):
 * when a target chunk grows into an adjacent free chunk by a small amount
 * ({@code safeToAllocate < freeChunk.getHeaderSize()}), only {@code safeToAllocate} bytes of the
 * free chunk's old on-disk header are zeroed (line 903). The remainder chunk is reinserted into the
 * free list via {@code iter.ioSet(ch.getPtr())} (line 895), which bypasses the header shredding/purge
 * done in {@code MemoryOperations.mergeChunks}. The report claims the old header's tail bytes are
 * therefore left behind inside the remainder chunk's data region, where a later header-chip scan
 * ({@code Chunk.earlyCheckChunkAt} / {@code purgePossibleChunkHeaders}) could mistake them for a valid
 * chunk header.
 *
 * <h3>What the code should do</h3>
 * <p>
 * After the grow, the remainder chunk's data region must not contain any non-zero bytes of the old
 * free chunk's header.
 *
 * <h3>What it actually does (and why the bug does not manifest)</h3>
 * <p>
 * The leftover region is {@code [freePtr + s + H_c, freePtr + H_f)} (s = safeToAllocate,
 * {@code H_x = 1 + 2*b_x} with VOID next pointers, {@code b_x} = body num-size bytes). It is
 * non-empty only when {@code H_f > s + H_c}, i.e. {@code 2*b_f > s + 2*b_c} - an over-provisioned
 * free header ({@code b_f > b_c}). The code indeed never touches this region, but it can never
 * contain non-zero old header bytes. Proof:
 * <ul>
 * <li>a free chunk's on-disk header (written by {@code mergeChunks} via {@code setSize(0)} +
 * {@link Chunk#clearAndCompressHeader()}) is {@code [flags][capacity LE][size = 0 LE]} with a VOID
 * next pointer (every chunk entering the free list goes through {@code clearAndCompressHeader}, so
 * the next field is never present in a free chunk), hence its only non-zero bytes are the flags
 * byte (offset 0) and the low capacity bytes {@code [1, 1 + bySize(cap))}.</li>
 * <li>Lemma: whenever the leftover is non-empty, {@code bySize(cap) <= b_c}. The remainder capacity
 * is {@code chCap = H_f + cap - H_c - s = cap + 2*(b_f - b_c) - s}; since {@code bySize(chCap) =
 * b_c}, {@code chCap <= 2^(8*b_c) - 1}; and the non-empty leftover gives {@code s <= 2*(b_f - b_c)
 * - 1}, so {@code cap <= 2^(8*b_c) - 1}.</li>
 * <li>The leftover starts at {@code s + H_c >= 2 + 2*b_c}, which is strictly past the last possible
 * non-zero offset {@code 1 + bySize(cap) <= 1 + b_c} (gap {@code 1 + b_c >= 1}); the flags byte at
 * offset 0 is likewise covered. Every leftover offset therefore lies in the high (zero) bytes of
 * the LE capacity field or in the all-zero size field.</li>
 * </ul>
 * So the untouched leftover is provably all zero: it can never be misread as a chunk header, and
 * the reported "old header tail bytes remain" harm is unreachable. In addition, the only production
 * path that over-provisions a free header ({@code prepareFreeChunkMerge},
 * MemoryOperations.java:277-283, crossing a number-size class boundary) simultaneously forces the
 * remainder into the same header class ({@code b_c >= b_f}), so the leftover region does not even
 * exist there (test 3).
 *
 * <h3>Trigger strategies tried</h3>
 * <p>
 * The grow is driven through the public API {@code target.growBy(target, s)}
 * (Chunk.java:735), which routes through {@code MemoryManager.allocTo} to the {@code GROW_FREE_ALLOC}
 * strategy (PersistentMemoryManager.java:206-213) and thus executes exactly
 * {@code MemoryOperations.growFreeAlloc}. Tests 1-2 hand-craft the report's "free chunk with a large
 * header" precondition (INT body num-size, capacity shrunk by the header growth so the physical
 * layout stays valid); test 3 uses the production-reachable over-provisioning (a free-chunk merge
 * crossing the BYTE -&gt; SHORT boundary), with the free-list storage pre-seeded so the merge result
 * survives.
 * <ol>
 * <li>The report's own suggested trigger: a small allocation (1 byte) into a free chunk with a large
 * (over-provisioned INT) header, so the leftover region is non-empty - the exact branch at
 * MemoryOperations.java:901 executes and the remainder is reinserted via {@code iter.ioSet}
 * (line 895). Leftover is all zero.</li>
 * <li>Same setup, larger grow (5 bytes) with a 1-byte leftover at the very end of the old header.
 * Leftover is zero.</li>
 * <li>Production-reachable over-provisioning: two adjacent chunks freed through
 * {@code manager.free(...)} merge via {@code prepareFreeChunkMerge} (MemoryOperations.java:277-283)
 * crossing the BYTE -&gt; SHORT boundary, leaving the free header over-provisioned (cap 255,
 * bns=SHORT). The grow leaves no leftover region at all: the remainder is forced into the same
 * header class ({@code b_c >= b_f}), so zero-fill + remainder header fully cover the old header.</li>
 * </ol>
 *
 * <p>All three pass, i.e. the reported "old header tail bytes remain" state is not reachable:
 * <b>FALSE POSITIVE</b>.
 */
public class ReproMemOpsGrowFreeTests{
	
	@Test
	public void leftoverRegionExistsButIsZero() throws Exception{
		var cluster = Cluster.init(MemoryData.empty());
		var mem     = cluster.getSource();
		var manager = cluster.getMemoryManager();
		
		// Layout [X][A][F][T] - APPEND_TO_FILE allocates contiguously, so F sits right after A and
		// T keeps F from being the last physical chunk (otherwise popFile would truncate it on
		// free). X (before A) is freed first to seed the free-list backing capacity: while free()
		// runs, the queued chunk is visible to the allocator and the free-list storage growth can
		// relocate into a free chunk's data region - seeding it beforehand keeps that growth from
		// running (and from rewriting F's header) when F itself is freed.
		var x = AllocateTicket.bytes(32).submit(cluster);
		var a = AllocateTicket.bytes(64).submit(cluster);
		var f = AllocateTicket.bytes(200).submit(cluster);
		var t = AllocateTicket.bytes(32).submit(cluster);
		assertEquals(f.getPtr(), ChunkPointer.of(a.dataEnd()), "F must be physically adjacent after A");
		
		manager.free(x); // seed free-list capacity; must not move A/F/T (they are allocated)
		assertEquals(f.getPtr(), ChunkPointer.of(a.dataEnd()), "F must still be adjacent after A");
		assertEquals(t.getPtr(), ChunkPointer.of(f.dataEnd()), "T must still be adjacent after F");
		
		// Make F's on-disk header over-provisioned: INT body num-size (4-byte capacity + 4-byte size
		// fields) although the capacity only needs a BYTE. This is the report's "free chunk with a
		// large header" precondition. The capacity is shrunk by exactly the header growth (6 bytes)
		// so that totalSize stays 203 and the physical layout (F followed by T) remains valid.
		// Freed through the production free() path (no storage growth needed), so the on-disk header
		// is exactly what mergeChunks writes: [flags][capacity:4B LE][size=0:4B LE] = 9 bytes.
		f.setBodyNumSize(NumberSize.INT);
		f.setCapacity(194);
		f.writeHeader();
		manager.free(f);
		
		long p  = f.getPtr().getValue();
		int hF  = f.getHeaderSize();
		assertEquals(hF, 9, "F must have the over-provisioned 9-byte header");
		assertTrue(NumberSize.bySize(f.getCapacity()).bytes < f.getBodyNumSize().bytes,
		           "F must be over-provisioned (bns larger than needed for its capacity)");
		
		// Old on-disk header: [flags][C2 00 00 00][00 00 00 00] (cap 194 LE) - non-zero only at
		// offsets 0 and 1; offsets 2..8 are all zero.
		byte[] oldHeader = mem.read(p, hF);
		assertTrue(anyNonZero(oldHeader),
		           "old header must contain non-zero bytes (non-degenerate scenario)");
		
		// Trigger: grow A by 1 byte into F (growBy -> allocTo -> GROW_FREE_ALLOC ->
		// MemoryOperations.growFreeAlloc). safeToAllocate = 1 < 9 -> MemoryOperations.java:901
		// branch: 1 byte zero-filled (line 903), remainder reinserted via iter.ioSet (line 895),
		// no purge.
		a.growBy(a, 1);
		assertEquals(a.getCapacity(), 65L, "grow must have taken 1 byte from the adjacent free chunk");
		
		// The remainder must be a free-list entry (proves the line-895 ioSet path ran).
		var freeList = manager.getFreeChunks();
		var expectedRemainder = ChunkPointer.of(p + 1);
		boolean found = false;
		for(long i = 0; i < freeList.size(); i++){
			found |= freeList.get(i).equals(expectedRemainder);
		}
		assertTrue(found, "remainder must be at freePtr + safeToAllocate in the free list: " + freeList);
		
		// Remainder chunk at p+1: bns=BYTE -> 3-byte header.
		var rem  = ChunkPointer.of(p + 1).dereference(cluster);
		int hRem = rem.getHeaderSize();
		assertEquals(hRem, 3, "remainder header size");
		assertEquals(rem.getCapacity(), 199, "remainder capacity (203 - 1 - 3)");
		
		// Leftover region: [p + 1 + 3, p + 9) = 5 bytes = old header offsets 4..8.
		assertTrue(hF > 1 + hRem, "leftover region must be non-empty (the bug's precondition)");
		byte[] leftover = mem.read(p + 1 + hRem, hF - 1 - hRem);
		
		// If the bug existed, leftover would hold the old header's tail bytes (oldHeader[4..9)).
		// Correct behavior: shredded (all zero) - no old header bytes remain.
		assertTrue(allZero(leftover),
		           "BUG-53: old free-chunk header tail bytes remain in the remainder's data region: "
		           + hex(leftover) + " (old header was: " + hex(oldHeader) + ")");
	}
	
	@Test
	public void largerGrowStillLeavesZeroLeftover() throws Exception{
		var cluster = Cluster.init(MemoryData.empty());
		var mem     = cluster.getSource();
		var manager = cluster.getMemoryManager();
		
		var x = AllocateTicket.bytes(32).submit(cluster);
		var a = AllocateTicket.bytes(64).submit(cluster);
		var f = AllocateTicket.bytes(200).submit(cluster);
		AllocateTicket.bytes(32).submit(cluster);
		assertEquals(f.getPtr(), ChunkPointer.of(a.dataEnd()), "F must be physically adjacent after A");
		
		manager.free(x); // seed free-list capacity (see test 1); must not move A/F/T
		assertEquals(f.getPtr(), ChunkPointer.of(a.dataEnd()), "F must still be adjacent after A");
		
		// Same over-provisioned header as test 1 (INT bns, capacity shrunk to keep totalSize 203).
		f.setBodyNumSize(NumberSize.INT);
		f.setCapacity(194);
		f.writeHeader();
		manager.free(f);
		
		long p  = f.getPtr().getValue();
		int hF  = f.getHeaderSize();
		assertEquals(hF, 9, "F must have the over-provisioned 9-byte header");
		
		// Grow by 5 bytes: safeToAllocate = 5 < 9 still hits MemoryOperations.java:901, and the
		// leftover region is the single byte at old header offset 8 (top byte of the size field).
		a.growBy(a, 5);
		assertEquals(a.getCapacity(), 69L, "grow must have taken 5 bytes from the adjacent free chunk");
		
		var rem  = ChunkPointer.of(p + 5).dereference(cluster);
		int hRem = rem.getHeaderSize();
		assertEquals(hRem, 3, "remainder header size");
		
		assertTrue(hF > 5 + hRem, "leftover region must be non-empty (the bug's precondition)");
		byte[] leftover = mem.read(p + 5 + hRem, hF - 5 - hRem);
		assertTrue(allZero(leftover),
		           "BUG-53: old free-chunk header tail bytes remain in the remainder's data region: "
		           + hex(leftover));
	}
	
	@Test
	public void productionMergeOverprovisioningLeavesNoLeftover() throws Exception{
		var cluster = Cluster.init(MemoryData.empty());
		var mem     = cluster.getSource();
		var manager = cluster.getMemoryManager();
		
		// Layout [X][A][P][N][T]:
		// - X (before A) is freed first to seed the free-list backing capacity, so the second
		//   free() below does not need to grow the free-list storage itself (during free(), the
		//   queued chunk is visible to the allocator and such a growth could perturb the layout).
		// - P (cap 241) and N (cap 13, total 16 = minSafeSize) are physically adjacent; T keeps
		//   both from being truncated by popFile.
		// - X is not adjacent to P, so the merged P is not re-merged with it (a re-merge would
		//   grow P's capacity back into its header class, healing the over-provisioning).
		var x = AllocateTicket.bytes(32).submit(cluster);
		var a = AllocateTicket.bytes(64).submit(cluster);
		var p = AllocateTicket.bytes(241).submit(cluster);
		var n = AllocateTicket.bytes(13).submit(cluster);
		var t = AllocateTicket.bytes(32).submit(cluster);
		assertEquals(n.getPtr(), ChunkPointer.of(p.dataEnd()), "N must be adjacent after P");
		assertEquals(t.getPtr(), ChunkPointer.of(n.dataEnd()), "T must be adjacent after N");
		
		manager.free(x); // seed free-list capacity; must not move A/P/N (they are allocated)
		
		assertEquals(n.getPtr(), ChunkPointer.of(p.dataEnd()), "N must still be adjacent after P");
		assertEquals(t.getPtr(), ChunkPointer.of(n.dataEnd()), "T must still be adjacent after N");
		
		// Freeing both merges them through prepareFreeChunkMerge (MemoryOperations.java:277-283):
		// P absorbs N -> P.setCapacityAndModifyNumSize(241 + 16 = 257) crosses the BYTE->SHORT
		// boundary, and the 2-byte header growth eats into the capacity, leaving P over-provisioned
		// in a production-reachable way: bns=SHORT (5-byte header) with capacity 255
		// (bySize(255) = BYTE < SHORT).
		manager.free(List.of(p, n));
		
		long pP  = p.getPtr().getValue();
		int  hP  = p.getHeaderSize();
		assertEquals(hP, 5, "P must have the over-provisioned 5-byte (SHORT) header");
		assertEquals(p.getCapacity(), 255, "P capacity after absorbing N");
		assertTrue(NumberSize.bySize(p.getCapacity()).bytes < p.getBodyNumSize().bytes,
		           "P must be over-provisioned (production-reachable via free-chunk merge)");
		
		byte[] oldHeader = mem.read(pP, hP); // [flags][FF 00][00 00]
		assertTrue(anyNonZero(oldHeader),
		           "old header must contain non-zero bytes (non-degenerate scenario)");
		
		// Grow A by 1 byte into P (growBy -> allocTo -> GROW_FREE_ALLOC -> growFreeAlloc).
		a.growBy(a, 1);
		assertEquals(a.getCapacity(), 65L, "grow must have taken 1 byte from the adjacent free chunk");
		
		var rem  = ChunkPointer.of(pP + 1).dereference(cluster);
		int hRem = rem.getHeaderSize();
		assertEquals(hRem, 5, "remainder must also use the SHORT header class");
		
		// The boundary crossing that over-provisioned P's header also forced the remainder's
		// number size to the same class (b_c >= b_f), so zero-fill (1 byte) + remainder header
		// (5 bytes) fully cover the old 5-byte header: no leftover region exists.
		assertTrue(hP <= 1 + hRem,
		           "production over-provisioning must leave no leftover region (b_c >= b_f)");
		assertEquals(mem.read(pP, 1)[0], (byte)0, "zero-fill must cover the old flags byte");
	}
	
	private static void assertEquals(Object actual, Object expected, String msg){
		org.testng.Assert.assertEquals(actual, expected, msg);
	}
	private static void assertEquals(long actual, long expected, String msg){
		org.testng.Assert.assertEquals(actual, expected, msg);
	}
	private static void assertEquals(int actual, int expected, String msg){
		org.testng.Assert.assertEquals(actual, expected, msg);
	}
	private static void assertTrue(boolean cond, String msg){
		org.testng.Assert.assertTrue(cond, msg);
	}
	
	private static boolean anyNonZero(byte[] b){
		for(byte x : b){
			if(x != 0) return true;
		}
		return false;
	}
	private static boolean allZero(byte[] b){
		for(byte x : b){
			if(x != 0) return false;
		}
		return true;
	}
	
	private static String hex(byte[] b){
		var sb = new StringBuilder();
		for(byte x : b){
			sb.append(String.format("%02x ", x));
		}
		return sb.toString().trim();
	}
}
