package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.run.TestUtils;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.FreeChunk;
import com.lapissea.dfs.core.chunk.PhysicalExtentWalker;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class IntrusiveAllocatorTests{
	
	@IOValue
	public static class Payload extends IOInstance.Managed<Payload>{
		public long   value;
		public byte[] bytes;
		public Payload(){ }
		public Payload(long value){
			this.value = value;
			this.bytes = new byte[80];
			Arrays.fill(bytes, (byte)value);
		}
	}
	
	@Test
	public void singleThreadReplayProducesIdenticalBytes() throws Exception{
		assertThat(replay()).isEqualTo(replay());
	}
	private static byte[] replay() throws Exception{
		var cluster   = Cluster.emptyMem();
		var manager   = cluster.getMemoryManager();
		var first     = AllocateTicket.bytes(256).submit(cluster);
		var separator = AllocateTicket.bytes(40).submit(cluster);
		var second    = AllocateTicket.bytes(128).submit(cluster);
		var tail      = AllocateTicket.bytes(40).submit(cluster);
		manager.free(first);
		var a = AllocateTicket.bytes(30).withDataPopulated((p, io) -> io.writeInt1(17)).submit(cluster);
		var b = AllocateTicket.bytes(25).withDataPopulated((p, io) -> io.writeInt1(23)).submit(cluster);
		manager.free(second);
		manager.free(List.of(a, b));
		var c = AllocateTicket.bytes(45).withDataPopulated((p, io) -> io.writeInt1(31)).submit(cluster);
		manager.free(tail);
		assertFreeChain(cluster);
		assertThat(separator.getPtr()).isNotEqualTo(c.getPtr());
		return cluster.getSource().readAll();
	}
	
	@DataProvider
	public Object[][] tinyPhysicalSizes(){ return new Object[][]{{9}, {10}}; }
	
	@Test(dataProvider = "tinyPhysicalSizes")
	public void tinyFreeExtentsRemainReachableAcrossReopenAndAreReusable(int physicalSize) throws Exception{
		var cluster = Cluster.emptyMem();
		var tiny    = AllocateTicket.bytes(physicalSize - 3).submit(cluster);
		var guard   = AllocateTicket.bytes(32).submit(cluster);
		assertThat(tiny.totalSize()).isEqualTo(physicalSize);
		long fileSize = cluster.getSource().getIOSize();
		cluster.getMemoryManager().free(tiny);
		assertThat(FreeChunk.read(cluster, tiny.getPtr()).totalSize()).isEqualTo(physicalSize);
		assertThat(guard.getPtr().getValue()).isEqualTo(tiny.getPtr().getValue() + physicalSize);
		assertFreeChain(cluster);
		var reopened = TestUtils.reopenMem(cluster);
		assertFreeChain(reopened);
		var reused = AllocateTicket.bytes(physicalSize - 3)
		                           .withApproval(chunk -> chunk.getPtr().equals(tiny.getPtr()))
		                           .submit(reopened);
		assertThat(reused).isNotNull();
		assertThat(reused.getPtr()).isEqualTo(tiny.getPtr());
		assertThat(reused.totalSize()).isEqualTo(physicalSize);
		assertThat(reopened.getSource().getIOSize()).isEqualTo(fileSize);
		assertFreeChain(reopened);
	}
	
	@DataProvider
	public Object[][] widePointerRemainders(){
		Object[][] values = new Object[10][];
		for(int remainder = 0; remainder<values.length; remainder++) values[remainder] = new Object[]{remainder};
		return values;
	}
	
	@Test(dataProvider = "widePointerRemainders")
	public void maximumWidthPointerPreservesOrAbsorbsEveryTinyRemainder(int remainder) throws Exception{
		var cluster = Cluster.emptyMem();
		var hole    = AllocateTicket.bytes(14 + remainder).submit(cluster);
		var guard   = AllocateTicket.bytes(32).submit(cluster);
		assertThat(hole.totalSize()).isEqualTo(17 + remainder);
		cluster.getMemoryManager().free(hole);
		var allocated = AllocateTicket.bytes(6).withExplicitNextSize(Optional.of(NumberSize.LONG))
		                              .withApproval(candidate -> candidate.getPtr().getValue()<guard.getPtr().getValue())
		                              .submit(cluster);
		assertThat(allocated).isNotNull();
		assertThat(allocated.getNextSize()).isEqualTo(NumberSize.LONG);
		assertThat(allocated.dataEnd()).isEqualTo(guard.getPtr().getValue());
		if(remainder<9){
			assertThat(allocated.getPtr()).isEqualTo(hole.getPtr());
			assertThat(allocated.totalSize()).isEqualTo(17 + remainder);
			assertThat(cluster.getMemoryManager().getFreeChunks().isEmpty()).isTrue();
		}else{
			assertThat(allocated.totalSize()).isEqualTo(17);
			assertThat(FreeChunk.read(cluster, hole.getPtr()).totalSize()).isEqualTo(9);
		}
		assertFreeChain(cluster);
	}
	
	@DataProvider
	public Object[][] approvalResults(){ return new Object[][]{{false}, {true}}; }
	
	@Test
	public void rejectingHintVisitsAnotherExtentWithoutRetryingIt() throws Exception{
		var cluster = Cluster.emptyMem();
		var manager = cluster.getMemoryManager();
		var first   = AllocateTicket.bytes(256).submit(cluster);
		AllocateTicket.bytes(32).submit(cluster);
		var second = AllocateTicket.bytes(512).submit(cluster);
		var guard  = AllocateTicket.bytes(32).submit(cluster);
		manager.free(first);
		AllocateTicket.bytes(24).submit(cluster);
		manager.free(second);
		var visited = new HashSet<ChunkPointer>();
		var allocated = AllocateTicket.bytes(32).withApproval(candidate -> {
			assertThat(visited.add(candidate.getPtr())).as("candidate is not retried").isTrue();
			return candidate.getPtr().getValue()>second.getPtr().getValue();
		}).submit(cluster);
		assertThat(visited).hasSize(2);
		assertThat(allocated.dataEnd()).isEqualTo(guard.getPtr().getValue());
		assertFreeChain(cluster);
	}
	
	@Test(dataProvider = "approvalResults")
	public void canAllocDoesNotChangeFileIndexOrNextAllocationChoice(boolean approve) throws Exception{
		assertThat(probeReplay(true, approve)).isEqualTo(probeReplay(false, approve));
	}
	private static byte[] probeReplay(boolean probe, boolean approve) throws Exception{
		var cluster = Cluster.emptyMem();
		var manager = cluster.getMemoryManager();
		var first   = AllocateTicket.bytes(256).submit(cluster);
		AllocateTicket.bytes(32).submit(cluster);
		var second = AllocateTicket.bytes(512).submit(cluster);
		AllocateTicket.bytes(32).submit(cluster);
		manager.free(first);
		AllocateTicket.bytes(24).submit(cluster); // Establish the last-chipped hint in the first extent.
		manager.free(second);
		if(probe){
			byte[] before   = cluster.getSource().readAll();
			var    pointers = new ArrayList<ChunkPointer>();
			manager.getFreeChunks().forEach(pointers::add);
			assertThat(manager.canAlloc(AllocateTicket.bytes(300).withApproval(candidate -> approve))).isEqualTo(approve);
			assertThat(cluster.getSource().readAll()).isEqualTo(before);
			assertThat(manager.getFreeChunks()).containsExactlyElementsOf(pointers);
			assertFreeChain(cluster);
		}
		var allocated = AllocateTicket.bytes(32).submit(cluster);
		assertThat(allocated.getPtr().getValue()).isLessThan(second.getPtr().getValue());
		assertFreeChain(cluster);
		return cluster.getSource().readAll();
	}
	
	@Test
	public void adjacentFreeExtentsCoalesceAndFreeTailTrimsWithoutRelocation() throws Exception{
		var  cluster      = Cluster.emptyMem();
		var  manager      = cluster.getMemoryManager();
		var  a            = AllocateTicket.bytes(48).submit(cluster);
		var  b            = AllocateTicket.bytes(64).submit(cluster);
		var  tail         = AllocateTicket.bytes(32).withDataPopulated((p, io) -> io.writeInt1(73)).submit(cluster);
		long combined     = a.totalSize() + b.totalSize();
		long tailPosition = tail.getPtr().getValue();
		manager.free(b);
		manager.free(a);
		assertThat(FreeChunk.read(cluster, a.getPtr()).totalSize()).isEqualTo(combined);
		assertThat(manager.getFreeChunks().size()).isEqualTo(1);
		assertThat(tail.getPtr().getValue()).isEqualTo(tailPosition);
		try(var io = tail.io()){ assertThat(io.readUnsignedInt1()).isEqualTo(73); }
		assertFreeChain(cluster);
		manager.free(tail);
		assertThat(cluster.getSource().getIOSize()).isEqualTo(a.getPtr().getValue());
		assertThat(manager.getFreeChunks().isEmpty()).isTrue();
		assertThat(cluster.getFirstFreeChunkPointer().isNull()).isTrue();
		assertFreeChain(cluster);
	}
	
	@Test(timeOut = 60_000)
	public void explicitCompactionPreservesRootsAndReopenedData() throws Exception{
		var cluster = Cluster.emptyMem();
		cluster.roots().provide("first", new Payload(11));
		var hole = AllocateTicket.bytes(1024).submit(cluster);
		cluster.roots().provide("second", new Payload(22));
		cluster.getMemoryManager().free(hole);
		long before = cluster.getSource().getIOSize();
		cluster.defragment();
		assertThat(cluster.getSource().getIOSize()).isLessThanOrEqualTo(before);
		cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		assertFreeChain(cluster);
		var reopened = TestUtils.reopenMem(cluster);
		for(var name : List.of("first", "second")){
			long expected = name.equals("first")? 11 : 22;
			var  payload  = reopened.roots().request(name, Payload.class);
			assertThat(payload.value).isEqualTo(expected);
			assertThat(payload.bytes).hasSize(80).containsOnly((byte)expected);
		}
		assertFreeChain(reopened);
	}
	
	static void assertFreeChain(Cluster cluster) throws Exception{
		var linked  = new HashSet<ChunkPointer>();
		var pointer = cluster.getFirstFreeChunkPointer();
		while(!pointer.isNull()){
			assertThat(linked.add(pointer)).as("persistent free chain is acyclic").isTrue();
			pointer = FreeChunk.read(cluster, pointer).getNextPtr();
		}
		var  physical = new HashSet<ChunkPointer>();
		long end      = cluster.getFirstChunk().getPtr().getValue();
		for(var extent : new PhysicalExtentWalker(cluster, cluster.getFirstChunk().getPtr())){
			assertThat(extent.getPtr().getValue()).isEqualTo(end);
			assertThat(extent.totalSize()).isGreaterThanOrEqualTo(Chunk.minSafeSize());
			if(extent instanceof FreeChunk) physical.add(extent.getPtr());
			end = extent.dataEnd();
		}
		assertThat(end).isEqualTo(cluster.getSource().getIOSize());
		assertThat(linked).containsExactlyInAnyOrderElementsOf(physical);
		assertThat(cluster.getMemoryManager().getFreeChunks()).containsExactlyInAnyOrderElementsOf(physical);
	}
}
