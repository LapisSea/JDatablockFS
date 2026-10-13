package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.core.chunk.FreeChunk;
import com.lapissea.dfs.core.chunk.PhysicalExtentWalker;
import com.lapissea.dfs.exceptions.CacheOutOfSync;
import com.lapissea.dfs.exceptions.FreeWhileUsed;
import com.lapissea.dfs.objects.ChunkPointer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Independent allocator operations can progress while an allocation approval is paused.
 * Extent reservations protect candidates without holding a global metadata lock.
 * These tests use independent raw chunks, avoiding concurrent mutation of the root collection.
 */
public class MemoryManagerConcurrencyTests{
	@Test(timeOut = 15_000)
	public void freeDoesNotRelocateLiveChunksOrWaitForUnrelatedReaders() throws Exception{
		var cluster = Cluster.emptyMem();
		var memory  = cluster.getMemoryManager();
		// Separated holes avoid merging; the larger hole precedes the physical tail.
		var reader    = AllocateTicket.bytes(16).submit(cluster);
		var firstHole = AllocateTicket.bytes(16).submit(cluster);
		var separator = AllocateTicket.bytes(16).submit(cluster);
		var lastHole  = AllocateTicket.bytes(64).submit(cluster);
		var tail      = AllocateTicket.bytes(16).submit(cluster);
		assertThat(reader.isNextPhysical(firstHole)).isTrue();
		assertThat(firstHole.isNextPhysical(separator)).isTrue();
		assertThat(separator.isNextPhysical(lastHole)).isTrue();
		assertThat(lastHole.isNextPhysical(tail)).isTrue();
		assertThat(tail.checkLastPhysical()).isTrue();
		assertThat(lastHole.getCapacity()).isGreaterThanOrEqualTo(32);
		assertThat(lastHole.getCapacity()).isGreaterThanOrEqualTo(2*tail.getSize());
		var error = new AtomicReference<Throwable>();
		var freed = new CountDownLatch(1);
		var freeing = worker("free-with-open-reader", error, () -> {
			try{
				memory.free(firstHole);
				memory.free(lastHole);
				assertThat(memory.getFreeChunks().size()).as("separated holes remain available").isGreaterThan(1);
				assertThat(memory.getFreeChunks().getLast()).isEqualTo(lastHole.getPtr());
				assertThat(tail.checkLastPhysical()).as("ordinary free does not relocate live data").isTrue();
			}finally{
				freed.countDown();
			}
		});
		try(var outer = reader.io()){
			freeing.start();
			// Keep the outer chain open until free returns, so compaction cannot wait for it.
			assertThat(freed.await(5, TimeUnit.SECONDS)).as("free completes while another thread holds a chain").isTrue();
			assertThat(error.get()).as("free worker failure").isNull();
			try(var nested = separator.io()){
				assertThat(nested.getCapacity()).isGreaterThanOrEqualTo(16);
			}
			assertThat(outer.getCapacity()).isGreaterThanOrEqualTo(16);
		}finally{
			freeing.interrupt();
			freeing.join(2_000);
		}
		assertThat(freeing.isAlive()).as("free worker terminated").isFalse();
	}
	
	@DataProvider
	public Object[][] openChainTargets(){
		return new Object[][]{{false, false}, {true, false}, {false, true}, {true, true}};
	}
	
	@Test(dataProvider = "openChainTargets", timeOut = 30_000)
	public void openChainOnAnotherThreadPreventsFree(boolean tailTarget, boolean batch) throws Exception{
		var cluster = Cluster.emptyMem();
		var memory  = cluster.getMemoryManager();
		var tail    = AllocateTicket.bytes(64).withDataPopulated((p, io) -> io.writeInt1(0x42)).submit(cluster);
		var head    = AllocateTicket.bytes(64).withNext(tail).submit(cluster);
		var other   = AllocateTicket.bytes(64).withDataPopulated((p, io) -> io.writeInt1(0x73)).submit(cluster);
		var target  = tailTarget? tail : head;
		var toFree  = batch? List.of(other, target) : List.of(target);
		var opened  = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var failure = new AtomicReference<Throwable>();
		var holder = worker("open-chain-holder", failure, () -> {
			try(var ignored = new ChunkChainIO(head)){
				opened.countDown();
				assertThat(release.await(20, TimeUnit.SECONDS)).as("holder released").isTrue();
			}finally{
				opened.countDown();
			}
		});
		holder.start();
		try{
			assertThat(opened.await(5, TimeUnit.SECONDS)).as("holder opened chain").isTrue();
			assertThat(failure.get()).isNull();
			long size = cluster.getSource().getIOSize();
			assertThatThrownBy(() -> memory.free(toFree)).isInstanceOf(FreeWhileUsed.class);
			assertThat(cluster.getSource().getIOSize()).isEqualTo(size);
			try(var io = tail.io()){
				assertThat(io.readUnsignedInt1()).isEqualTo(0x42);
			}
			try(var io = other.io()){
				assertThat(io.readUnsignedInt1()).isEqualTo(0x73);
			}
			var unrelated = AllocateTicket.bytes(32).submit(cluster);
			memory.free(unrelated);
		}finally{
			release.countDown();
			holder.join(5_000);
			if(holder.isAlive()) holder.interrupt();
		}
		assertThat(holder.isAlive()).as("holder completed").isFalse();
		assertThat(failure.get()).as("holder failure").isNull();
		memory.free(List.of(head, tail, other));
	}
	
	@Test
	public void sameThreadOpenChainPreventsFree() throws Exception{
		var cluster = Cluster.emptyMem();
		var chunk   = AllocateTicket.bytes(64).submit(cluster);
		try(var ignored = chunk.io()){
			assertThatThrownBy(() -> cluster.getMemoryManager().free(chunk)).isInstanceOf(FreeWhileUsed.class);
		}
		cluster.getMemoryManager().free(chunk);
	}
	@Test(timeOut = 15_000)
	public void reusedPinStacksPreserveOrderThreadAndManagerOwnership() throws Exception{
		var cluster    = Cluster.emptyMem();
		var other      = Cluster.emptyMem();
		var memory     = cluster.getMemoryManager();
		var chunk      = AllocateTicket.bytes(64).submit(cluster);
		var otherChunk = AllocateTicket.bytes(64).submit(other);
		for(int cycle = 0; cycle<3; cycle++){
			try(var outer = chunk.io(); var unrelated = otherChunk.io()){
				try(var nested = chunk.io()){
					assertThatThrownBy(() -> memory.notifyEnd(outer)).isInstanceOf(IllegalStateException.class);
					var failure = new AtomicReference<Throwable>();
					var foreign = worker("foreign-pin-close", failure, () ->
						                                                   assertThatThrownBy(() -> memory.notifyEnd(nested)).isInstanceOf(IllegalStateException.class));
					foreign.start();
					foreign.join(5_000);
					assertThat(foreign.isAlive()).isFalse();
					assertThat(failure.get()).isNull();
				}
				assertThatThrownBy(() -> memory.free(chunk)).isInstanceOf(FreeWhileUsed.class);
				assertThatThrownBy(() -> other.getMemoryManager().free(otherChunk)).isInstanceOf(FreeWhileUsed.class);
			}
		}
		memory.free(chunk);
		other.getMemoryManager().free(otherChunk);
		assertThatThrownBy(chunk::io).isInstanceOf(CacheOutOfSync.class);
		var replacement = AllocateTicket.bytes(64).submit(cluster);
		try(var ignored = replacement.io()){
			assertThatThrownBy(() -> memory.free(replacement)).isInstanceOf(FreeWhileUsed.class);
		}
		memory.free(replacement);
	}
	@Test(timeOut = 15_000)
	public void tailFreeProgressesDuringFreeChunkApproval() throws Exception{
		var cluster   = Cluster.emptyMem();
		var hole      = AllocateTicket.bytes(128).submit(cluster);
		var separator = AllocateTicket.bytes(16).submit(cluster);
		var tail      = AllocateTicket.bytes(16).submit(cluster);
		cluster.getMemoryManager().free(hole);
		assertThat(tail.checkLastPhysical()).isTrue();
		assertThat(separator.isNextPhysical(tail)).isTrue();
		var reused = new AtomicReference<Chunk>();
		assertIndependentProgress(
			gate -> reused.set(AllocateTicket.bytes(32).withApproval(candidate -> {
				assertThat(candidate.getPtr().getValue()).isLessThan(separator.getPtr().getValue());
				gate.pause();
				return true;
			}).submit(cluster)),
			() -> cluster.getMemoryManager().free(tail));
		assertThat(reused.get()).isNotNull();
		assertThat(separator.checkLastPhysical()).isTrue();
		cluster.getChunk(reused.get().getPtr());
	}
	
	@Test(timeOut = 15_000)
	public void freeChunkReuseProgressesDuringAppendApproval() throws Exception{
		var cluster   = Cluster.emptyMem();
		var hole      = AllocateTicket.bytes(128).submit(cluster);
		var separator = AllocateTicket.bytes(16).submit(cluster);
		cluster.getMemoryManager().free(hole);
		var appended = new AtomicReference<Chunk>();
		var reused   = new AtomicReference<Chunk>();
		assertIndependentProgress(
			gate -> appended.set(AllocateTicket.bytes(256).withApproval(candidate -> {
				assertThat(candidate.getPtr().getValue()).isEqualTo(separator.dataEnd());
				gate.pause();
				return true;
			}).submit(cluster)),
			() -> reused.set(AllocateTicket.bytes(32).submit(cluster)));
		assertThat(appended.get().checkLastPhysical()).isTrue();
		assertThat(reused.get().getPtr().getValue()).isLessThan(separator.getPtr().getValue());
		cluster.getChunk(appended.get().getPtr());
		cluster.getChunk(reused.get().getPtr());
	}
	
	@Test(timeOut = 15_000)
	public void rejectedApprovalReleasesExtentAndCoalescesConcurrentNeighborFrees() throws Exception{
		var cluster = Cluster.emptyMem();
		var before  = AllocateTicket.bytes(32).submit(cluster);
		var hole    = AllocateTicket.bytes(128).submit(cluster);
		var after   = AllocateTicket.bytes(32).submit(cluster);
		var guard   = AllocateTicket.bytes(32).submit(cluster);
		cluster.getMemoryManager().free(hole);
		var result = new AtomicReference<Chunk>();
		var paused = new AtomicBoolean();
		assertIndependentProgress(gate -> result.set(AllocateTicket.bytes(16).withApproval(candidate -> {
			if(paused.compareAndSet(false, true)) gate.pause();
			return false;
		}).submit(cluster)), () -> cluster.getMemoryManager().free(List.of(before, after)));
		assertThat(result.get()).isNull();
		var merged = FreeChunk.read(cluster, before.getPtr());
		assertThat(merged.dataEnd()).isEqualTo(guard.getPtr().getValue());
		assertThat(cluster.getMemoryManager().getFreeChunks().size()).isEqualTo(1);
		IntrusiveAllocatorTests.assertFreeChain(cluster);
	}
	
	@Test(timeOut = 15_000)
	public void rejectedApprovalReleasesAndTrimsExtentThatBecamePhysicalTail() throws Exception{
		var cluster = Cluster.emptyMem();
		var hole    = AllocateTicket.bytes(128).submit(cluster);
		var tail    = AllocateTicket.bytes(32).submit(cluster);
		cluster.getMemoryManager().free(hole);
		var paused = new AtomicBoolean();
		assertIndependentProgress(gate -> {
			assertThat(AllocateTicket.bytes(16).withApproval(candidate -> {
				if(paused.compareAndSet(false, true)) gate.pause();
				return false;
			}).submit(cluster)).isNull();
		}, () -> cluster.getMemoryManager().free(tail));
		assertThat(cluster.getSource().getIOSize()).isEqualTo(hole.getPtr().getValue());
		assertThat(cluster.getMemoryManager().getFreeChunks().isEmpty()).isTrue();
		IntrusiveAllocatorTests.assertFreeChain(cluster);
	}
	
	@Test(timeOut = 30_000)
	public void concurrentAllocationsAndFreesPreservePayloadsAndPhysicalBoundaries() throws Exception{
		var cluster = Cluster.emptyMem();
		var start   = new CountDownLatch(1);
		try(var pool = Executors.newFixedThreadPool(4)){
			var futures = new ArrayList<Future<?>>();
			for(int worker = 0; worker<4; worker++){
				int id = worker;
				futures.add(pool.submit(() -> {
					try{
						assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
						for(int iteration = 0; iteration<80; iteration++){
							byte marker = (byte)(id*43 + iteration);
							int  size   = 9 + (id*13 + iteration*17)%190;
							var chunk = AllocateTicket.bytes(size)
							                          .withDataPopulated((p, io) -> {
								                          byte[] bytes = new byte[size];
								                          Arrays.fill(bytes, marker);
								                          io.write(bytes);
							                          }).submit(cluster);
							try(var io = chunk.io()){
								byte[] bytes = new byte[size];
								io.readFully(bytes);
								assertThat(bytes).containsOnly(marker);
							}
							cluster.getMemoryManager().free(chunk);
						}
					}catch(Exception e){ throw new RuntimeException(e); }
				}));
			}
			start.countDown();
			for(var future : futures) future.get(20, TimeUnit.SECONDS);
		}
		long end          = cluster.getFirstChunk().getPtr().getValue();
		var  physicalFree = new HashSet<ChunkPointer>();
		for(var extent : new PhysicalExtentWalker(cluster, cluster.getFirstChunk().getPtr())){
			assertThat(extent.getPtr().getValue()).isEqualTo(end);
			if(extent instanceof FreeChunk) physicalFree.add(extent.getPtr());
			end = extent.dataEnd();
		}
		assertThat(end).isEqualTo(cluster.getSource().getIOSize());
		assertThat(cluster.getMemoryManager().getFreeChunks()).containsExactlyInAnyOrderElementsOf(physicalFree);
	}
	
	@FunctionalInterface
	private interface Action{
		void run() throws Exception;
	}
	
	
	private interface PausedAction{
		void run(Gate gate) throws Exception;
	}
	
	private static final class Gate{
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		void pause(){
			entered.countDown();
			try{
				assertThat(release.await(5, TimeUnit.SECONDS)).as("release paused allocation").isTrue();
			}catch(InterruptedException e){
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}
	
	private static void assertIndependentProgress(PausedAction first, Action second) throws Exception{
		var    gate          = new Gate();
		var    error         = new AtomicReference<Throwable>();
		var    secondStarted = new CountDownLatch(1);
		var    secondDone    = new CountDownLatch(1);
		Thread owner         = worker("allocator-owner", error, () -> first.run(gate));
		Thread contender = worker("allocator-contender", error, () -> {
			secondStarted.countDown();
			try{ second.run(); }finally{ secondDone.countDown(); }
		});
		owner.start();
		try{
			assertThat(gate.entered.await(5, TimeUnit.SECONDS)).as("allocation reached approval callback").isTrue();
			contender.start();
			assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(secondDone.await(3, TimeUnit.SECONDS))
				.as("independent allocator operation completes while approval remains paused").isTrue();
		}finally{
			gate.release.countDown();
			owner.join(3_000);
			if(contender.getState() != Thread.State.NEW) contender.join(3_000);
			if(owner.isAlive()) owner.interrupt();
			if(contender.isAlive()) contender.interrupt();
		}
		assertThat(owner.isAlive()).as("allocation completed").isFalse();
		assertThat(contender.isAlive()).as("competing operation completed").isFalse();
		assertThat(error.get()).as("worker failure").isNull();
	}
	
	private static Thread worker(String name, AtomicReference<Throwable> error, Action action){
		var thread = new Thread(() -> {
			try{ action.run(); }catch(Throwable e){ error.compareAndSet(null, e); }
		}, name);
		thread.setDaemon(true);
		return thread;
	}
}
