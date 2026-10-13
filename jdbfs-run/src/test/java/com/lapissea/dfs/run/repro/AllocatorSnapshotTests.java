package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.PhysicalExtent;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.ChunkPointer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

public class AllocatorSnapshotTests{
	private static final class SnapshotVerifier implements IOHook{
		private String operation = "open";
		private int frame;
		private final Map<String, Integer> frames = new HashMap<>();
		@Override
		public void writeEvent(IOInterface source, LongStream changeIds) throws IOException{
			var bytes = source.readAll();
			var provider = DataProvider.newVerySimpleProvider(MemoryData.viewOf(bytes));
			var description = "IPC frame " + frame++ + " during " + operation;
			long position = MagicID.size();
			while(position<bytes.length){
				var extent = PhysicalExtent.read(provider, ChunkPointer.of(position));
				assertThat(extent.dataEnd()).as(description + " advances from " + position).isGreaterThan(position);
				assertThat(extent.dataEnd()).as(description + " stays within the snapshot").isLessThanOrEqualTo(bytes.length);
				position = extent.dataEnd();
			}
			assertThat(position).as(description + " ends on a physical boundary").isEqualTo(bytes.length);
			frames.merge(operation, 1, Integer::sum);
		}
	}

	@Test
	public void everyAllocatorWriteSnapshotHasCompletePhysicalExtents() throws Exception{
		var initial = Cluster.emptyMem().getSource().readAll();
		var verifier = new SnapshotVerifier();
		var source = MemoryData.builder().withRaw(initial).withOnWrite(verifier).build();
		var cluster = new Cluster(source);
		var manager = cluster.getMemoryManager();

		verifier.operation = "append";
		var target = AllocateTicket.bytes(64).submit(cluster);
		var adjacent = AllocateTicket.bytes(256).submit(cluster);
		var tail = AllocateTicket.bytes(32).submit(cluster);
		assertThat(target.isNextPhysical(adjacent)).isTrue();
		assertThat(adjacent.isNextPhysical(tail)).isTrue();

		verifier.operation = "free";
		manager.free(adjacent);

		verifier.operation = "adjacent growth";
		target.growBy(target, 1);
		assertThat(target.getCapacity()).isEqualTo(65);

		verifier.operation = "reuse";
		var reused = AllocateTicket.bytes(16).submit(cluster);
		assertThat(reused.getPtr().getValue()).isGreaterThan(adjacent.getPtr().getValue());
		assertThat(reused.dataEnd()).isLessThanOrEqualTo(tail.getPtr().getValue());

		verifier.operation = "coalesce";
		manager.free(reused);

		verifier.operation = "front split";
		var front = manager.allocBefore(AllocateTicket.bytes(16), tail.getPtr());
		assertThat(front.getPtr().getValue()).isEqualTo(target.dataEnd());
		manager.free(front);

		verifier.operation = "trim";
		manager.free(tail);
		assertThat(source.getIOSize()).isEqualTo(target.dataEnd());
		manager.free(target);
		assertThat(source.getIOSize()).isEqualTo(initial.length);

		for(var operation : List.of("append", "free", "adjacent growth", "reuse", "coalesce", "front split", "trim")){
			assertThat(verifier.frames.getOrDefault(operation, 0)).as("snapshots recorded for " + operation).isPositive();
		}
	}
}
