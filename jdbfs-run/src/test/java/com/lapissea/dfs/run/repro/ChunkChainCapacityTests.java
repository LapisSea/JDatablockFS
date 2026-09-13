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
 * Shrinking must preserve the content prefix even when chunk sizes differ from capacities.
 * The partial non-tail fixture is assembled through low-level APIs and violates the allocator's
 * normal full-non-tail invariant; it does not demonstrate a normal allocation path.
 */
public class ChunkChainCapacityTests{

	@org.testng.annotations.DataProvider
	public Object[][] zeroRanges(){
		return new Object[][]{
			{20, 0, 0}, {20, 0, 10}, {20, 5, 10}, {20, 0, 20},
			{10, 10, 10}, {10, 15, 5}, {10, 5, 15}
		};
	}

	@Test(dataProvider = "zeroRanges")
	public void fillZeroClearsRequestedCapacityWithoutChangingSize(int size, int offset, int length) throws IOException{
		var cluster = Cluster.emptyMem();
		var expected = new byte[20];
		Arrays.fill(expected, (byte)0xAB);
		var chunk = AllocateTicket.bytes(expected.length)
			.withDataPopulated(c -> c.write(false, expected)).submit(cluster);
		assertThat(chunk.hasNextPtr()).isFalse();
		assertThat(chunk.getSize()).isEqualTo(expected.length);
		try(var io = chunk.io()){
			io.setPos(offset);
			io.setSize(size);
			long capacity = io.getCapacity();
			io.fillZero(length);
			assertThat(io.getSize()).as("logical size").isEqualTo(size);
			assertThat(io.getCapacity()).as("allocated capacity").isEqualTo(capacity);
			assertThat(io.getPos()).as("cursor position").isEqualTo(offset);
		}
		Arrays.fill(expected, offset, offset + length, (byte)0);
		// Read physical bytes to include allocated space beyond the logical end.
		assertThat(cluster.getSource().read(chunk.dataStart(), expected.length)).isEqualTo(expected);
	}

	@org.testng.annotations.DataProvider
	public Object[][] shrinkTargets(){
		return new Object[][]{
			{0}, {1}, {9}, {10}, {11}, {14}, {15},
			{16}, {19}, {20},
			{21}, {24}, {25}
		};
	}

	@Test(dataProvider = "shrinkTargets")
	public void shrinkPreservesPrefixWithPartialMiddleChunk(int target) throws IOException, OutOfBitDepth{
		assertShrink(5, target);
	}

	@Test(dataProvider = "shrinkTargets")
	public void shrinkPreservesPrefixWithFullMiddleChunk(int target) throws IOException, OutOfBitDepth{
		assertShrink(10, target);
	}

	private static void assertShrink(int middleSize, int target) throws IOException, OutOfBitDepth{
		var chain = makeChain(middleSize);
		try(var io = chain.head().io()){
			io.setCapacity(target);
			assertThat(io.getSize()).as("live size after shrinking to %d", target).isEqualTo(target);
			// Allocation granularity permits retaining more capacity than requested.
			assertThat(io.getCapacity()).as("capacity after shrinking to %d", target).isGreaterThanOrEqualTo(target);
		}
		assertThat(chain.head().readAll())
			.as("content prefix after shrinking to %d (middle size %d)", target, middleSize)
			.isEqualTo(Arrays.copyOf(chain.content(), target));

		int remaining = target;
		Chunk chunk = chain.head();
		for(int originalSize : new int[]{10, middleSize, 10}){
			assertThat(chunk).as("retained chunk for %d remaining bytes", remaining).isNotNull();
			int retained = Math.min(remaining, originalSize);
			assertThat(chunk.getSize()).as("retained chunk size").isEqualTo(retained);
			remaining -= retained;
			if(remaining == 0){
				assertThat(chunk.hasNextPtr()).as("trimmed tail must be detached").isFalse();
				break;
			}
			chunk = chunk.next();
		}
		assertThat(remaining).isZero();
	}

	private record Chain(Chunk head, byte[] content){ }

	private static Chain makeChain(int middleSize) throws IOException, OutOfBitDepth{
		var provider = Cluster.emptyMem();
		byte[] pattern = new byte[20 + middleSize];
		for(int i = 0; i<pattern.length; i++) pattern[i] = (byte)(i + 1);

		Chunk a = allocChunk(provider, pattern, 0, 10, true);
		Chunk b = allocChunk(provider, pattern, 10, middleSize, true);
		Chunk c = allocChunk(provider, pattern, 10 + middleSize, 10, false);
		a.setNextPtr(b.getPtr());
		a.syncStruct();
		b.setNextPtr(c.getPtr());
		b.syncStruct();

		for(Chunk chunk : new Chunk[]{a, b, c}){
			assertThat(chunk.getCapacity()).as("fixture chunk capacity").isEqualTo(10);
		}
		assertThat(a.getSize()).isEqualTo(10);
		assertThat(b.getSize()).isEqualTo(middleSize);
		assertThat(c.getSize()).isEqualTo(10);
		assertThat(c.hasNextPtr()).isFalse();
		assertThat(a.readAll()).as("fixture content before shrinking").isEqualTo(pattern);
		return new Chain(a, pattern);
	}

	private static Chunk allocChunk(DataProvider provider, byte[] pattern, int offset, int size, boolean hasNext) throws IOException{
		var ticket = AllocateTicket.bytes(10);
		// Reserve pointer width before linking independently allocated chunks.
		if(hasNext) ticket = ticket.withExplicitNextSize(Optional.of(NumberSize.SHORT));
		return ticket.withDataPopulated(chunk -> chunk.write(false, Arrays.copyOfRange(pattern, offset, offset + size)))
		             .submit(provider);
	}
}
