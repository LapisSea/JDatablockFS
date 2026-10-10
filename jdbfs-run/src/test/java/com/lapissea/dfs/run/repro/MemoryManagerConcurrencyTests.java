package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.exceptions.FreeWhileUsed;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Allocator operations must serialize free-list/header changes with physical-tail changes.
 * Approval callbacks pause allocation inside its critical section, before it writes headers.
 * These tests use independent raw chunks, avoiding concurrent mutation of the root collection.
 */
public class MemoryManagerConcurrencyTests{
	@Test(timeOut = 15_000)
	public void optionalCompactionDoesNotWaitForOpenReaders() throws Exception{
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
				assertThat(memory.getFreeChunks().size()).as("multiple holes permit optional compaction").isGreaterThan(1);
				assertThat(memory.getFreeChunks().getLast()).isEqualTo(lastHole.getPtr());
				assertThat(tail.checkLastPhysical()).as("compaction is deferred while a reader is open").isTrue();
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
	public void tailFreeWaitsForFreeChunkReuse() throws Exception{
		var cluster   = Cluster.emptyMem();
		var hole      = AllocateTicket.bytes(128).submit(cluster);
		var separator = AllocateTicket.bytes(16).submit(cluster);
		var tail      = AllocateTicket.bytes(16).submit(cluster);
		cluster.getMemoryManager().free(hole);
		assertThat(tail.checkLastPhysical()).isTrue();
		assertThat(separator.isNextPhysical(tail)).isTrue();
		var reused = new AtomicReference<Chunk>();
		assertSerialized(
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
	public void freeChunkReuseWaitsForAppend() throws Exception{
		var cluster   = Cluster.emptyMem();
		var hole      = AllocateTicket.bytes(128).submit(cluster);
		var separator = AllocateTicket.bytes(16).submit(cluster);
		cluster.getMemoryManager().free(hole);
		var appended = new AtomicReference<Chunk>();
		var reused   = new AtomicReference<Chunk>();
		assertSerialized(
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
	
	private static void assertSerialized(PausedAction first, Action second) throws Exception{
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
			assertThat(secondDone.await(300, TimeUnit.MILLISECONDS))
				.as("competing allocator operation must wait until the paused allocation releases its lock").isFalse();
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
