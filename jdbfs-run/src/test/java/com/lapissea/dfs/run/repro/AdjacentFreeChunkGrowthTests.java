package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class AdjacentFreeChunkGrowthTests{
	@DataProvider
	public Object[][] smallGrowth(){
		return new Object[][]{{1}, {2}, {3}, {4}, {5}};
	}

	@Test(dataProvider = "smallGrowth")
	public void growthIntoWideHeaderLeavesZeroedRemainderData(int growth) throws Exception{
		var cluster = Cluster.emptyMem();
		var manager = cluster.getMemoryManager();
		// Seed free-list storage before freeing the adjacent chunk, keeping its layout stable.
		var seed = AllocateTicket.bytes(32).submit(cluster);
		var target = AllocateTicket.bytes(64).submit(cluster);
		var adjacent = AllocateTicket.bytes(200).submit(cluster);
		var tail = AllocateTicket.bytes(32).submit(cluster);
		manager.free(seed);
		assertThat(target.isNextPhysical(adjacent)).isTrue();
		assertThat(adjacent.isNextPhysical(tail)).isTrue();

		// Low-level fixture: widen the header while keeping the total physical extent fixed.
		// The capacity fits in a byte, but its stored capacity and size each occupy four bytes.
		adjacent.setBodyNumSize(NumberSize.INT);
		adjacent.setCapacity(194);
		adjacent.writeHeader();
		manager.free(adjacent);
		long start = adjacent.getPtr().getValue();
		int headerSize = adjacent.getHeaderSize();
		assertThat(headerSize).isEqualTo(9);
		assertThat(adjacent.isNextPhysical(tail)).isTrue();

		target.growBy(target, growth);
		assertThat(target.getCapacity()).isEqualTo(64 + growth);
		var remainderPointer = ChunkPointer.of(start + growth);
		var freeList = manager.getFreeChunks();
		boolean found = false;
		for(long i = 0; i<freeList.size(); i++){
			found |= freeList.get(i).equals(remainderPointer);
		}
		assertThat(found).as("remainder is registered as free").isTrue();
		var remainder = remainderPointer.dereference(cluster);
		assertThat(remainder.getHeaderSize()).isEqualTo(3);
		assertThat(remainder.getCapacity()).isEqualTo(200 - growth);
		assertThat(remainder.getSize()).isZero();
		assertThat(remainder.isNextPhysical(tail)).isTrue();
		assertThat(cluster.getSource().read(start, growth)).as("newly allocated header bytes").containsOnly((byte)0);
		int exposedHeaderBytes = headerSize - growth - remainder.getHeaderSize();
		assertThat(exposedHeaderBytes).as("old header overlaps remainder data").isPositive();
		assertThat(cluster.getSource().read(remainder.dataStart(), exposedHeaderBytes))
			.as("old header bytes in remainder data").containsOnly((byte)0);
	}

	@Test
	public void growthAfterMergePreservesHeaderBoundaryAndPhysicalLayout() throws Exception{
		var cluster = Cluster.emptyMem();
		var manager = cluster.getMemoryManager();
		var seed = AllocateTicket.bytes(32).submit(cluster);
		var target = AllocateTicket.bytes(64).submit(cluster);
		var adjacent = AllocateTicket.bytes(241).submit(cluster);
		var next = AllocateTicket.bytes(13).submit(cluster);
		var tail = AllocateTicket.bytes(32).submit(cluster);
		manager.free(seed);
		assertThat(target.isNextPhysical(adjacent)).isTrue();
		assertThat(adjacent.isNextPhysical(next)).isTrue();
		assertThat(next.isNextPhysical(tail)).isTrue();

		// Merging crosses BYTE to SHORT; the larger header consumes two capacity bytes.
		manager.free(List.of(adjacent, next));
		assertThat(adjacent.getHeaderSize()).isEqualTo(5);
		assertThat(adjacent.getCapacity()).isEqualTo(255);
		assertThat(adjacent.getBodyNumSize()).isEqualTo(NumberSize.SHORT);
		long start = adjacent.getPtr().getValue();
		target.growBy(target, 1);
		assertThat(target.getCapacity()).isEqualTo(65);
		var remainder = ChunkPointer.of(start + 1).dereference(cluster);
		assertThat(remainder.getHeaderSize()).isEqualTo(5);
		assertThat(remainder.getCapacity()).isEqualTo(254);
		assertThat(remainder.getSize()).isZero();
		assertThat(remainder.isNextPhysical(tail)).isTrue();
		assertThat(cluster.getSource().read(start, 1)).as("absorbed header byte").containsOnly((byte)0);
	}
}
