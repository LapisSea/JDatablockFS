package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.io.impl.MemoryData;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction for BUG-41 (memmgr-drainio): deadlock between the free-list "drain" in
 * {@code PersistentMemoryManager.tryPopFree()} and a thread that opens a nested
 * {@link ChunkChainIO} while already holding an open chain.
 *
 * <h2>What the code is supposed to do</h2>
 * <p>
 * When a freed chunk is not at the end of the file, {@code tryPopFree()} tries to compact the
 * free list by moving the last physical chunk ({@code toMove}) into the free hole before it
 * (PersistentMemoryManager.java:349-410). Moving a chunk invalidates open chains, so it first
 * sets {@code drainIO = true} and spins until every OTHER thread's chain stack is empty
 * (PersistentMemoryManager.java:371-384). A thread that opens a chain while {@code drainIO} is
 * set is supposed to wait for the drain to finish: {@code notifyStart()} calls {@code block()},
 * which sleeps while {@code drainIO} (PersistentMemoryManager.java:134-137, 161-164).
 *
 * <h2>What it actually does</h2>
 * <p>
 * A thread that ALREADY holds an open chain (e.g. it is reading a parent object) and then opens
 * a nested chain (e.g. it dereferences a reference, which is the common case) is parked in
 * {@code block()} while its own chain stack is non-empty. The drain's spin condition only turns
 * false when all non-drainer stacks are empty (PersistentMemoryManager.java:376-381), which can
 * never happen while that thread's stack is non-empty. The drainer waits for the other thread's
 * stack to empty, and the other thread waits for {@code drainIO} to be cleared by the very drain
 * that is waiting for it: a mutual, permanent deadlock under multithreaded use.
 *
 * <h2>Why the test fails</h2>
 * <p>
 * The test deterministically arranges the chunk layout so that thread B's
 * {@code free(List.of(b))} (after {@code free(List.of(f1))}) enters the drain branch: the free
 * list becomes [f1, b] (size &gt; 1), the last free chunk {@code b} is not the last physical
 * chunk, {@code b.getCapacity() >= 32}, {@code b.nextPhysical()} is {@code c} which IS the last
 * physical chunk, and {@code b.getCapacity() >= 2 * c.getSize()} (all asserted below). Thread A
 * opens an outer chain first, then - while the drain is active - opens a nested chain. B then
 * spins forever in the {@code tryPopFree()} drain and A sleeps forever in
 * {@code notifyStart() -> block()}; the liveness assertion below fails and prints both stacks,
 * identifying the two park locations.
 */
public class ReproMemMgrDrainIoTests{
	
	@Test(timeOut = 5_000)
	void freeListDrainDeadlocksThreadOpeningNestedChain() throws Exception{
		var cluster = Cluster.init(MemoryData.empty());
		var mm      = cluster.getMemoryManager();
		
		// Physical layout (each submit appends right after the previous chunk):
		//   [ a: live ] [ f1: to be freed ] [ d: live ] [ b: to be freed, cap>=32 ] [ c: live, LAST ]
		// d between f1 and b keeps the two freed chunks from being merged into one free chunk.
		Chunk a  = AllocateTicket.bytes(16).submit(cluster);
		Chunk f1 = AllocateTicket.bytes(16).submit(cluster);
		Chunk d  = AllocateTicket.bytes(16).submit(cluster);
		Chunk b  = AllocateTicket.bytes(64).submit(cluster);
		Chunk c  = AllocateTicket.bytes(16).submit(cluster);
		
		// Sanity: this layout must deterministically drive tryPopFree() into the
		// free-list compaction / drain branch (PersistentMemoryManager.java:349-384), so the
		// test exercises the drain no matter what. Without these, a "pass" would be vacuous.
		assertThat(a.isNextPhysical(f1)).as("layout a->f1").isTrue();
		assertThat(f1.isNextPhysical(d)).as("layout f1->d").isTrue();
		assertThat(d.isNextPhysical(b)).as("layout d->b").isTrue();
		assertThat(b.isNextPhysical(c)).as("layout b->c").isTrue();
		assertThat(b.checkLastPhysical()).as("b must NOT be the last physical chunk (c is)").isFalse();
		assertThat(c.checkLastPhysical()).as("c must be the last physical chunk (drain requires it)").isTrue();
		assertThat(b.getCapacity()).as("drain filter: last free chunk needs capacity >= 32").isGreaterThanOrEqualTo(32);
		assertThat(b.getCapacity()).as("drain filter: lastFree.capacity >= 2 * toMove.size").isGreaterThanOrEqualTo(2*c.getSize());
		
		var outerOpen = new CountDownLatch(1);
		var goNested  = new CountDownLatch(1);
		var f1Freed   = new CountDownLatch(1);
		
		var aError = new AtomicReference<Throwable>();
		var bError = new AtomicReference<Throwable>();
		
		// Thread A: "dereferencing a reference while the parent's ChunkChainIO is open".
		// Opens an OUTER chain and keeps it open, then opens a NESTED chain.
		var aThread = new Thread(() -> {
			ChunkChainIO outer = null;
			try{
				outer = new ChunkChainIO(a);          // notifyStart: A's stack = [outer]
				outerOpen.countDown();
				goNested.await();
				try(var nested = new ChunkChainIO(d)){// BUG: if drainIO is set, this parks in
					// notifyStart->block() forever: the drain waits for A's (non-empty) stack to
					// empty, but A is blocked waiting for that same drain to clear drainIO.
				}
			}catch(Throwable e){
				aError.set(e);
			}finally{
				if(outer != null){
					try{ outer.close(); }catch(Throwable ignored){ }
				}
			}
		}, "repro-thread-A");
		aThread.start();
		assertThat(outerOpen.await(10, TimeUnit.SECONDS)).as("A should open its outer chain").isTrue();
		
		// Thread B: frees f1 first (free list becomes [f1], size 1 -> no drain branch), then b
		// (free list becomes [f1, b] -> tryPopFree() enters the drain: drainIO=true, spin until
		// every other thread's chain stack is empty -> hangs for as long as A holds a chain).
		var bThread = new Thread(() -> {
			try{
				mm.free(List.of(f1));
				f1Freed.countDown();
				mm.free(List.of(b));   // BUG: never returns while A's stack is non-empty
			}catch(Throwable e){
				bError.set(e);
			}
		}, "repro-thread-B");
		bThread.start();
		assertThat(f1Freed.await(10, TimeUnit.SECONDS)).as("B should free f1").isTrue();
		
		// B is now inside the drain spin loop (it sleeps 0.1s per iteration; reaching it from the
		// second free() call is a sub-millisecond path on MemoryData). Let A open the nested chain
		// so it hits drainIO==true in notifyStart().
		Thread.sleep(750);
		goNested.countDown();
		
		// If the code were correct, both threads would finish promptly.
		bThread.join(30_000);
		aThread.join(20_000);
		
		// Any exception inside the worker threads would be a harness problem, not the reported bug.
		assertThat(bError.get()).as("free thread must not fail unexpectedly (harness issue, not BUG-41)").isNull();
		assertThat(aError.get()).as("reader thread must not fail unexpectedly (harness issue, not BUG-41)").isNull();
		
		// KEY ASSERTION: with this layout the drain is guaranteed to run, and a thread that holds an
		// open chain while opening a nested one must not deadlock with it.
		// BUG-41 present: B alive (spinning in the tryPopFree() drain, PersistentMemoryManager.java:371-384)
		// AND A alive (parked in notifyStart->block(), PersistentMemoryManager.java:134-137,161-164)
		// -> this assertion fails, printing both stacks to identify the two park locations.
		assertThat(bThread.isAlive() || aThread.isAlive()).as(
			                                                  "DEADLOCK (BUG-41): the free-list drain and a nested chain open deadlocked. " +
			                                                  "B (freeing) alive=%b: its drain spins until every other thread's chain stack is empty, " +
			                                                  "which can never happen while A holds an open chain. " +
			                                                  "A (reading) alive=%b: its nested ChunkChainIO open parks in notifyStart->block() waiting for " +
			                                                  "drainIO to be cleared - by the very drain that is waiting for A's stack. Stacks:\n--- B ---\n%s--- A ---\n%s",
			                                                  bThread.isAlive(), aThread.isAlive(), stackOf(bThread), stackOf(aThread))
		                                                  .isFalse();
	}
	
	private static String stackOf(Thread t){
		var sb = new StringBuilder();
		sb.append(t.getName()).append(t.isAlive()? " [alive]" : " [terminated]").append('\n');
		for(var frame : t.getStackTrace()){
			sb.append("\tat ").append(frame).append('\n');
		}
		return sb.toString();
	}
}
