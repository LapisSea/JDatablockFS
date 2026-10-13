package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.FreeChunk;
import com.lapissea.dfs.exceptions.FreeWhileUsed;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.run.TestUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.RefField;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CompactionSafetyTests{
	
	@IOValue
	public static class Aliases extends IOInstance.Managed<Aliases>{
		public ContiguousIOList<Long> a;
		public ContiguousIOList<Long> b;
		public ChunkPointer           raw;
		public Aliases(){ }
	}
	
	@IOValue
	public static class ChainRefs extends IOInstance.Managed<ChainRefs>{
		public ChunkPointer head;
		public ChunkPointer suffix;
		public ChainRefs(){ }
	}
	
	@IOInstance.Order({"a", "b"})
	public interface FixedRefs extends IOInstance.Def<FixedRefs>{
		ChunkPointer a();
		ChunkPointer b();
	}
	
	@Test
	public void readOnlyPointerAliasesAreCopiedAndSaved() throws Exception{
		var cluster = Cluster.emptyMem();
		var source  = AllocateTicket.bytes(32).withDataPopulated((p, io) -> io.write(new byte[32])).submit(cluster);
		var make    = IOInstance.Def.constrRef(FixedRefs.class, ChunkPointer.class, ChunkPointer.class);
		cluster.roots().provide("refs", make.apply(source.getPtr(), source.getPtr()));
		var move        = DefragmentManager.moveReference(cluster, source.getPtr(), ticket -> ticket);
		var destination = move.toDest(source.getPtr()).orElseThrow();
		var reopened    = TestUtils.reopenMem(cluster);
		var restored    = reopened.roots().request("refs", FixedRefs.class);
		assertThat(restored.a()).isEqualTo(destination);
		assertThat(restored.b()).isEqualTo(destination);
		reopened.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
	}
	
	@Test(timeOut = 60_000)
	public void compactionReplayIsDeterministicAndReachesAStableLayout() throws Exception{
		assertThat(replayCompaction()).isEqualTo(replayCompaction());
	}
	private static byte[] replayCompaction() throws Exception{
		var                    cluster = Cluster.emptyMem();
		var                    hole    = AllocateTicket.bytes(8192).submit(cluster);
		ContiguousIOList<Long> list    = cluster.roots().request("list", ContiguousIOList.class, Long.class);
		for(long i = 0; i<64; i++) list.add(i*7919);
		for(int i = 62; i>=0; i -= 2) list.remove(i);
		cluster.getMemoryManager().free(hole);
		cluster.defragment();
		byte[] result = cluster.getSource().readAll();
		cluster.defragment();
		assertThat(cluster.getSource().readAll()).as("a second compaction has no remaining move to perform").isEqualTo(result);
		cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		IntrusiveAllocatorTests.assertFreeChain(cluster);
		return result;
	}
	
	@DataProvider
	public Object[][] remainders(){
		var result = new Object[10][];
		for(int i = 0; i<result.length; i++) result[i] = new Object[]{i};
		return result;
	}
	
	@Test(dataProvider = "remainders")
	public void frontSplitPreservesEveryTinyRemainderAndMaximumPointerWidth(int remaining) throws Exception{
		var cluster = Cluster.emptyMem();
		var hole    = AllocateTicket.bytes(14 + remaining).submit(cluster);
		var guard   = AllocateTicket.bytes(32).submit(cluster);
		cluster.getMemoryManager().free(hole);
		var    ticket      = AllocateTicket.bytes(6).withExplicitNextSize(Optional.of(NumberSize.LONG));
		byte[] beforeProbe = cluster.getSource().readAll();
		assertThat(cluster.getMemoryManager().canAllocBefore(ticket, guard.getPtr())).isTrue();
		assertThat(cluster.getSource().readAll()).isEqualTo(beforeProbe);
		var result = cluster.getMemoryManager().allocBefore(ticket, guard.getPtr());
		assertThat(result.getPtr()).isEqualTo(hole.getPtr());
		assertThat(result.getNextSize()).isEqualTo(NumberSize.LONG);
		if(remaining<9){
			assertThat(result.dataEnd()).isEqualTo(guard.getPtr().getValue());
			assertThat(cluster.getMemoryManager().getFreeChunks()).isEmpty();
		}else{
			var pointer = ChunkPointer.of(result.dataEnd());
			assertThat(FreeChunk.read(cluster, pointer).totalSize()).isEqualTo(9);
		}
		IntrusiveAllocatorTests.assertFreeChain(cluster);
		IntrusiveAllocatorTests.assertFreeChain(TestUtils.reopenMem(cluster));
	}
	
	@Test
	public void openSourceIsRejectedBeforeRelocationChangesAnyBytes() throws Exception{
		var cluster = Cluster.emptyMem();
		var source  = AllocateTicket.bytes(32).withDataPopulated((p, io) -> io.write(new byte[32])).submit(cluster);
		var refs    = new ChainRefs();
		refs.head = source.getPtr();
		refs.suffix = source.getPtr();
		cluster.roots().provide("refs", refs);
		try(var pin = source.io()){
			byte[] before = cluster.getSource().readAll();
			assertThatThrownBy(() -> DefragmentManager.moveReference(cluster, source.getPtr(), ticket -> ticket))
				.isInstanceOf(FreeWhileUsed.class);
			assertThat(cluster.getSource().readAll()).isEqualTo(before);
		}
	}
	
	@Test
	public void laterChunkOfOpenChainIsRejectedBeforeRelocationChangesAnyBytes() throws Exception{
		var fixture = pinnedChain();
		try(var pin = fixture.head.io()){
			assertPinnedMoveRejected(fixture, fixture.suffix);
		}
		assertReleasedMoveSucceeds(fixture, fixture.suffix);
	}
	
	@DataProvider
	public Object[][] pinnedMoveTargets(){ return new Object[][]{{false}, {true}}; }
	
	@Test(dataProvider = "pinnedMoveTargets", timeOut = 30_000)
	public void chainHeldByAnotherThreadIsRejectedBeforeRelocationChangesAnyBytes(boolean moveSuffix) throws Exception{
		var fixture = pinnedChain();
		var opened  = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var failure = new AtomicReference<Throwable>();
		var holder = new Thread(() -> {
			try(var pin = fixture.head.io()){
				opened.countDown();
				assertThat(release.await(20, TimeUnit.SECONDS)).as("holder released").isTrue();
			}catch(Throwable error){
				failure.set(error);
			}finally{
				opened.countDown();
			}
		}, "compaction-chain-holder");
		holder.setDaemon(true);
		var target = moveSuffix? fixture.suffix : fixture.head;
		holder.start();
		try{
			assertThat(opened.await(5, TimeUnit.SECONDS)).as("holder opened chain").isTrue();
			assertThat(failure.get()).isNull();
			assertPinnedMoveRejected(fixture, target);
		}finally{
			release.countDown();
			holder.join(5_000);
			if(holder.isAlive()){
				holder.interrupt();
				holder.join(2_000);
			}
		}
		assertThat(holder.isAlive()).as("holder terminated").isFalse();
		assertThat(failure.get()).as("holder failure").isNull();
		assertReleasedMoveSucceeds(fixture, target);
	}
	
	private record PinnedChain(Cluster cluster, Chunk head, Chunk suffix){ }
	
	private static PinnedChain pinnedChain() throws Exception{
		var cluster = Cluster.emptyMem();
		var suffix = AllocateTicket.bytes(16).withDataPopulated((p, io) -> {
			for(int i = 0; i<16; i++) io.writeInt1(0x53);
		}).submit(cluster);
		var head = AllocateTicket.bytes(16).withNext(suffix)
		                         .withExplicitNextSize(Optional.of(NumberSize.LONG))
		                         .withDataPopulated((p, io) -> {
			                         for(int i = 0; i<16; i++) io.writeInt1(0x17);
		                         }).submit(cluster);
		var refs = new ChainRefs();
		refs.head = head.getPtr();
		refs.suffix = suffix.getPtr();
		cluster.roots().provide("refs", refs);
		return new PinnedChain(cluster, head, suffix);
	}
	
	private static void assertPinnedMoveRejected(PinnedChain fixture, Chunk target) throws Exception{
		byte[] before    = fixture.cluster.getSource().readAll();
		var    approvals = new AtomicInteger();
		assertThatThrownBy(() -> DefragmentManager.moveReference(fixture.cluster, target.getPtr(),
		                                                         ticket -> ticket.withApproval(candidate -> {
			                                                         approvals.incrementAndGet();
			                                                         return true;
		                                                         })))
			.isInstanceOf(FreeWhileUsed.class);
		assertThat(approvals.get()).as("refused before destination allocation").isZero();
		assertThat(fixture.cluster.getSource().readAll()).as("refusal leaves all bytes intact").isEqualTo(before);
	}
	
	private static void assertReleasedMoveSucceeds(PinnedChain fixture, Chunk target) throws Exception{
		var move        = DefragmentManager.moveReference(fixture.cluster, target.getPtr(), ticket -> ticket);
		var destination = move.toDest(target.getPtr()).orElseThrow();
		var reopened    = TestUtils.reopenMem(fixture.cluster);
		var refs        = reopened.roots().request("refs", ChainRefs.class);
		assertThat(target == fixture.head? refs.head : refs.suffix).isEqualTo(destination);
		try(var io = refs.head.dereference(reopened).io()){
			byte[] expected = new byte[32];
			Arrays.fill(expected, 0, 16, (byte)0x17);
			Arrays.fill(expected, 16, 32, (byte)0x53);
			assertThat(io.readRemaining()).isEqualTo(expected);
		}
		try(var io = refs.suffix.dereference(reopened).io()){
			assertThat(io.readRemaining()).hasSize(16).containsOnly((byte)0x53);
		}
		reopened.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		IntrusiveAllocatorTests.assertFreeChain(reopened);
	}
	
	@Test(timeOut = 60_000)
	public void warmedRootCacheReturnsFreshCollectionAfterCompaction() throws Exception{
		var                    cluster = Cluster.emptyMem();
		var                    hole    = AllocateTicket.bytes(64*1024).submit(cluster);
		ContiguousIOList<Long> list    = cluster.roots().request("list", ContiguousIOList.class, Long.class);
		list.addAll(List.of(11L, 22L, 33L));
		for(int i = 0; i<30; i++) list = cluster.roots().request("list", ContiguousIOList.class, Long.class);
		cluster.getMemoryManager().free(hole);
		cluster.defragment();
		ContiguousIOList<Long> restored = cluster.roots().request("list", ContiguousIOList.class, Long.class);
		assertThat(restored).containsExactly(11L, 22L, 33L);
		restored.add(44L);
		var                    reopened  = TestUtils.reopenMem(cluster);
		ContiguousIOList<Long> persisted = reopened.roots().request("list", ContiguousIOList.class, Long.class);
		assertThat(persisted).containsExactly(11L, 22L, 33L, 44L);
	}
	
	@Test(timeOut = 60_000)
	public void compactionMovesAnInteriorSeparatorToMakeSpaceForTheTail() throws Exception{
		var cluster = Cluster.emptyMem();
		var refs    = new ChainRefs();
		refs.head = ChunkPointer.of(Long.MAX_VALUE);
		refs.suffix = ChunkPointer.of(Long.MAX_VALUE);
		cluster.roots().provide("refs", refs);
		var holeA  = AllocateTicket.bytes(128).submit(cluster);
		var middle = AllocateTicket.bytes(64).withDataPopulated((p, io) -> io.write(new byte[64])).submit(cluster);
		var holeB  = AllocateTicket.bytes(128).submit(cluster);
		var tail   = AllocateTicket.bytes(190).withDataPopulated((p, io) -> io.write(new byte[190])).submit(cluster);
		cluster.rootWalker(new MemoryWalker.PointerRecord(){
			@Override
			public <T extends IOInstance<T>> int log(Reference location, T instance, RefField<T, ?> field, Reference value, Holder holder){
				return MemoryWalker.CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference location, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder){
				if(!(instance instanceof ChainRefs stored)) return MemoryWalker.CONTINUE;
				stored.head = middle.getPtr();
				stored.suffix = tail.getPtr();
				return MemoryWalker.SAVE|MemoryWalker.END;
			}
		}, false).walk();
		cluster.getMemoryManager().free(List.of(holeA, holeB));
		long before = cluster.getSource().getIOSize();
		cluster.defragment();
		assertThat(cluster.getSource().getIOSize()).as("interior relocation makes room to evacuate the tail").isLessThan(before);
		var reopened = TestUtils.reopenMem(cluster);
		var restored = reopened.roots().request("refs", ChainRefs.class);
		try(var io = restored.head.dereference(reopened).io()){
			assertThat(io.readRemaining()).hasSize(64).containsOnly((byte)0);
		}
		try(var io = restored.suffix.dereference(reopened).io()){
			assertThat(io.readRemaining()).hasSize(190).containsOnly((byte)0);
		}
		reopened.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		IntrusiveAllocatorTests.assertFreeChain(reopened);
	}
	
	@Test
	public void compactionSearchFindsEligibleSpaceBeyondTheNormalSearchLimit() throws Exception{
		var cluster = Cluster.emptyMem();
		var earlier = AllocateTicket.bytes(1024).submit(cluster);
		var limit   = AllocateTicket.bytes(64).submit(cluster);
		var later   = new ArrayList<Chunk>();
		for(int i = 0; i<40; i++){
			later.add(AllocateTicket.bytes(64).submit(cluster));
			AllocateTicket.bytes(16).submit(cluster);
		}
		cluster.getMemoryManager().free(earlier);
		cluster.getMemoryManager().free(later);
		var ticket = AllocateTicket.bytes(16).withPositionMagnet(0)
		                           .withApproval(candidate -> candidate.getPtr().compareTo(limit.getPtr())<0);
		assertThat(ticket.canSubmit(cluster))
			.as("bounded search rejects 32 later extents and never reaches the eligible larger hole")
			.isFalse();
		assertThat(cluster.getMemoryManager().canAllocBefore(ticket, limit.getPtr())).isTrue();
		var allocated = cluster.getMemoryManager().allocBefore(ticket, limit.getPtr());
		assertThat(allocated.getPtr()).isEqualTo(earlier.getPtr());
		IntrusiveAllocatorTests.assertFreeChain(cluster);
	}
	
	@Test
	public void compactionSearchIgnoresLastChippedHint() throws Exception{
		var cluster = Cluster.emptyMem();
		var earlier = AllocateTicket.bytes(512).submit(cluster);
		AllocateTicket.bytes(16).submit(cluster);
		var later = AllocateTicket.bytes(512).submit(cluster);
		AllocateTicket.bytes(16).submit(cluster);
		cluster.getMemoryManager().free(List.of(earlier, later));
		AllocateTicket.bytes(16).withApproval(candidate -> candidate.getPtr().compareTo(later.getPtr())>=0).submit(cluster);
		var selected = cluster.getMemoryManager().allocBefore(AllocateTicket.bytes(16), later.getPtr());
		assertThat(selected.getPtr()).as("exact search chooses the first free extent despite the hint").isEqualTo(earlier.getPtr());
		IntrusiveAllocatorTests.assertFreeChain(cluster);
	}
	
	@Test(timeOut = 60_000)
	public void unmanagedAliasesAndRawPointerSurviveMoveAndReopen() throws Exception{
		var                    cluster = Cluster.emptyMem();
		ContiguousIOList<Long> list    = cluster.roots().request("list", ContiguousIOList.class, Long.class);
		list.addAll(List.of(11L, 22L, 33L));
		var aliases = new Aliases();
		aliases.a = list;
		aliases.b = list;
		aliases.raw = list.getPointer();
		cluster.roots().provide("aliases", aliases);
		var old = list.getPointer();
		cluster = TestUtils.reopenMem(cluster);
		var move        = DefragmentManager.moveReference(cluster, old, ticket -> ticket);
		var destination = move.toDest(old).orElseThrow();
		var reopened    = TestUtils.reopenMem(cluster);
		var restored    = reopened.roots().request("aliases", Aliases.class);
		assertThat(restored.a.getPointer()).isEqualTo(destination);
		assertThat(restored.b.getPointer()).isEqualTo(destination);
		assertThat(restored.raw).isEqualTo(destination);
		assertThat(restored.a).containsExactly(11L, 22L, 33L);
		assertThat(restored.b).containsExactly(11L, 22L, 33L);
		reopened.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		IntrusiveAllocatorTests.assertFreeChain(reopened);
	}
	
	@Test(timeOut = 60_000)
	public void movingChainPreservesIndependentReferenceToItsSuffix() throws Exception{
		var cluster = Cluster.emptyMem();
		var suffix  = AllocateTicket.bytes(16).withDataPopulated((p, io) -> io.write(new byte[16])).submit(cluster);
		var head = AllocateTicket.bytes(16).withNext(suffix)
		                         .withExplicitNextSize(Optional.of(NumberSize.LONG))
		                         .withDataPopulated((p, io) -> io.write(new byte[16])).submit(cluster);
		var refs = new ChainRefs();
		refs.head = head.getPtr();
		refs.suffix = suffix.getPtr();
		cluster.roots().provide("refs", refs);
		DefragmentManager.moveReference(cluster, head.getPtr(), ticket -> ticket);
		var reopened = TestUtils.reopenMem(cluster);
		var restored = reopened.roots().request("refs", ChainRefs.class);
		try(var io = restored.head.dereference(reopened).io()){
			assertThat(io.readRemaining()).hasSize(32).containsOnly((byte)0);
		}
		try(var io = restored.suffix.dereference(reopened).io()){
			assertThat(io.readRemaining()).hasSize(16).containsOnly((byte)0);
		}
		reopened.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		IntrusiveAllocatorTests.assertFreeChain(reopened);
	}
}
