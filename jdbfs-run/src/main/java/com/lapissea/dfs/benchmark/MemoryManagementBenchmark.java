package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.objects.collections.IONode;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Allocation timing on one continuously open database per worker. A fixed batch
 * amortizes invocation-hook overhead. Reclamation runs outside the measured
 * interval, retaining allocator caches and hints; GC profiler allocation counts
 * include that reclamation and must not be read as allocation-only byte counts.
 * Zero holes exercises append/truncate; other cases reuse separated mixed-size holes.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MemoryManagementBenchmark{
	/**
	 * JMH CLI options are passed through, including -prof gc and -rf json.
	 */
	public static void main(String[] args) throws IOException{
		var arguments = new String[args.length + 1];
		arguments[0] = ".*(MemoryManagementBenchmark.(allocate(Chunks|Nodes)|chipChunks)|HashMapBenchmark.recycleNodes)";
		System.arraycopy(args, 0, arguments, 1, args.length);
		Main.main(arguments);
	}
	
	public static final  int                          BATCH      = 128;
	private static final IOType                       NODE_TYPE  = IOType.of(IONode.class, Long.class);
	private static final BasicSizeDescriptor<Long, ?> VALUE_SIZE = BasicSizeDescriptor.IFixed.Basic.of(8);
	private static final Long                         VALUE      = 0x123456789ABCDEFL;
	
	@State(Scope.Thread)
	public static class Fixture{
		/**
		 * Requested separated holes before allocator metadata consumes any space.
		 */
		@Param({"0", "64", "4096"})
		public          int          freeChunks;
		protected       Cluster      cluster;
		protected       OptionalLong magnet;
		protected final Chunk[]      allocated = new Chunk[BATCH];
		private final   List<Chunk>  batch     = Arrays.asList(allocated);
		private         long         initialSize;
		
		@Setup(Level.Trial)
		public final void setup() throws IOException{
			cluster = Cluster.emptyMem();
			// Keep node next-pointer width constant across fragmentation cases.
			AllocateTicket.bytes(1<<20).submit(cluster);
			var warmNode = IONode.allocValNode(VALUE, null, VALUE_SIZE, NODE_TYPE, cluster, OptionalLong.empty());
			cluster.getMemoryManager().free(warmNode.getPointer().dereference(cluster));
			magnet = OptionalLong.of(cluster.getSource().getIOSize());
			var holes = new ArrayList<Chunk>(freeChunks);
			for(int i = 0; i<freeChunks; i++){
				holes.add(AllocateTicket.bytes(holeBodyBytes(i)).submit(cluster));
				AllocateTicket.bytes(16).submit(cluster); // Live separator prevents coalescing.
			}
			// A full guard prevents tail trimming/optional old-allocator compaction
			// from consuming the fixture's fragmented region.
			var guard = AllocateTicket.bytes(64<<10).submit(cluster);
			guard.setSize(guard.getCapacity());
			guard.syncStruct();
			cluster.getMemoryManager().free(holes);
			initialSize = cluster.getSource().getIOSize();
			afterSetup();
		}
		protected void afterSetup() throws IOException        { }
		protected void prepareReclamation() throws IOException{ }
		protected long holeBodyBytes(int index)               { return 32L<<(index&3); }
		
		@TearDown(Level.Invocation)
		public final void reclaim() throws IOException{
			prepareReclamation();
			cluster.getMemoryManager().free(batch);
			Arrays.fill(allocated, null);
		}
		
		@TearDown(Level.Trial)
		public void checkBoundedFile() throws IOException{
			// Allow free-list metadata growth in the old format; catch leaked batches.
			if(cluster.getSource().getIOSize()>initialSize + (1<<20)){
				throw new IllegalStateException("Allocation benchmark working set grew without bound");
			}
		}
	}
	
	@State(Scope.Thread)
	public static class Chunks extends Fixture{
		private AllocateTicket ticket;
		@Override
		protected void afterSetup(){
			ticket = AllocateTicket.bytes(32).withPositionMagnet(magnet);
		}
	}
	
	@State(Scope.Thread)
	public static class Nodes extends Fixture{
		private final IONode<?>[] nodes = new IONode<?>[BATCH];
		@Override
		protected void prepareReclamation() throws IOException{
			// Long is inline and next is null: only the node's physical chunk is owned.
			for(int i = 0; i<BATCH; i++) allocated[i] = nodes[i].getPointer().dereference(cluster);
			Arrays.fill(nodes, null);
		}
	}
	
	/**
	 * Large holes isolate repeated chipping; 24 KiB retains the same size bin.
	 */
	@State(Scope.Thread)
	public static class Chipping extends Chunks{
		@Param({"16384", "24576"})
		public int holeBytes;
		@Override
		protected long holeBodyBytes(int index){ return holeBytes; }
	}
	
	@Benchmark
	@OperationsPerInvocation(BATCH)
	public long chipChunks(Chipping state) throws IOException{
		return allocateChunks(state);
	}
	
	@Benchmark
	@OperationsPerInvocation(BATCH)
	public long allocateChunks(Chunks state) throws IOException{
		long checksum = 0;
		for(int i = 0; i<BATCH; i++){
			var chunk = state.ticket.submit(state.cluster);
			state.allocated[i] = chunk;
			checksum += chunk.getPtr().getValue();
		}
		return checksum;
	}
	
	/**
	 * Real IONode allocation/initialization with an eight-byte inline payload.
	 */
	@Benchmark
	@OperationsPerInvocation(BATCH)
	public long allocateNodes(Nodes state) throws IOException{
		long checksum = 0;
		for(int i = 0; i<BATCH; i++){
			var node = IONode.allocValNode(VALUE, null, VALUE_SIZE, NODE_TYPE, state.cluster, state.magnet);
			state.nodes[i] = node;
			checksum += node.getPointer().getValue();
		}
		return checksum;
	}
}
