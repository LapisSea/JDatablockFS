package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.FileIOData;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** End-to-end collection writes, including root commit and automatic checkpoints. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(1)
public class FileWalBenchmark{
	@Param({"false", "true"}) public boolean durable;
	@Param({"randomAccess", "mapped"}) public String backend;
	@Param({"1", "32", "256"}) public int batchSize;
	private Path path;
	private FileIOData data;
	private ContiguousIOList<Integer> list;
	private int sequence;

	@Setup(Level.Trial)
	public void setup() throws Exception{
		path = Files.createTempFile("jdbfs-wal-benchmark", ".db");
		data = backend.equals("mapped")? new FileMemoryMappedData(path.toFile(), false, durable) :
		                               new FileRandomAccessData(path.toFile(), false, durable);
		list = Cluster.initOrOpen(data).roots().request("values", ContiguousIOList.class, Integer.class);
		data.openIOTransaction(() -> { for(int i = 0; i<4096; i++) list.add(i); });
		data.checkpoint();
	}

	@Benchmark
	public int update() throws Exception{
		try(var tx = data.startTransaction()){
			for(int i = 0; i<batchSize; i++){
				int value = ++sequence;
				list.set((value*997)&4095, value);
			}
			tx.commit();
		}
		return sequence;
	}

	@TearDown(Level.Trial)
	public void cleanup() throws Exception{
		try{ if(data != null) data.close(); }
		finally{
			if(path != null){
				Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".wal"));
				Files.deleteIfExists(path);
			}
		}
	}

	public static void main(String[] args) throws Exception{
		new Runner(new OptionsBuilder().parent(new CommandLineOptions(args)).include(FileWalBenchmark.class.getSimpleName()).build()).run();
	}
}
