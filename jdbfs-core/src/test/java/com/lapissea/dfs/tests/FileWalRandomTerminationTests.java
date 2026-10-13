package com.lapissea.dfs.tests;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real subprocess deaths at seeded main-file mutation boundaries, complementing the power-loss model. */
public class FileWalRandomTerminationTests{
	private static final int BATCHES = 20;
	private static int value(long seed, int batch){ return new Random(seed + batch).nextInt(); }

	public static void main(String[] args) throws Exception{
		var path = Path.of(args[0]);
		var progress = Path.of(args[1]);
		boolean mapped = Boolean.parseBoolean(args[2]);
		long seed = Long.parseLong(args[3]);
		int cutoff = Integer.parseInt(args[4]);
		var armed = new AtomicBoolean();
		var writes = new AtomicInteger();
		IOHook hook = (source, offsets) -> {
			if(armed.get() && writes.incrementAndGet() == cutoff) Runtime.getRuntime().halt(75);
		};
		var source = mapped? new FileMemoryMappedData(hook, path.toFile(), false, true) :
		                     new FileRandomAccessData(hook, path.toFile(), FileRandomAccessData.Mode.READ_WRITE, true);
		ContiguousIOList<Integer> list = new Cluster(source).roots().request("values", ContiguousIOList.class, Integer.class);
		armed.set(true);
		for(int batch = 0; batch<BATCHES; batch++){
			try(var tx = source.startTransaction()){
				int value = value(seed, batch);
				list.add(value);
				list.set(batch%8, ~value);
				tx.commit();
			}
			// This is a process-death test: the parent keeps running, so no durability claim
			// is needed for this external acknowledgment file used by the test oracle.
			Files.writeString(progress, Integer.toString(batch + 1));
		}
		Runtime.getRuntime().halt(76);
	}

	@Test(timeOut = 120_000)
	public void seededTerminationRecoversCompleteCollectionTransactions() throws Exception{
		int cases = Integer.getInteger("wal.termination.cases", 12);
		for(int trial = 0; trial<cases; trial++){
			long seed = 0x4352415348L + trial;
			boolean mapped = trial%2 == 0;
			int cutoff = 1 + new Random(seed).nextInt(BATCHES);
			var directory = Files.createTempDirectory("wal-random-termination");
			var path = directory.resolve("data.db");
			var progress = directory.resolve("acknowledged");
			try{
				try(var source = new FileRandomAccessData(path.toFile(), false, true)){
					ContiguousIOList<Integer> list = Cluster.init(source).roots().request("values", ContiguousIOList.class, Integer.class);
					source.openIOTransaction(() -> { for(int i = 0; i<8; i++) list.add(i); });
				}
				Files.writeString(progress, "0");
				var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
				var classpath = System.getProperty("java.class.path") + System.getProperty("path.separator") + System.getProperty("jdk.module.path", "");
				var process = new ProcessBuilder(java, "-cp", classpath, FileWalRandomTerminationTests.class.getName(),
				                                path.toString(), progress.toString(), Boolean.toString(mapped), Long.toString(seed), Integer.toString(cutoff))
					.redirectErrorStream(true).start();
				if(!process.waitFor(15, TimeUnit.SECONDS)){ process.destroyForcibly(); throw new AssertionError("Child timed out: seed=" + seed); }
				assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes())).isEqualTo(75);
				int acknowledged = Integer.parseInt(Files.readString(progress));
				// IO hooks run during main application, after the current WAL batch is durable.
				int recoveredBatches = process.exitValue() == 75? acknowledged + 1 : BATCHES;
				var expected = new ArrayList<Integer>();
				for(int i = 0; i<8; i++) expected.add(i);
				for(int batch = 0; batch<recoveredBatches; batch++){
					int value = value(seed, batch);
					expected.add(value); expected.set(batch%8, ~value);
				}
				try(var source = new FileRandomAccessData(path.toFile(), false, false)){
					ContiguousIOList<Integer> list = new Cluster(source).roots().request("values", ContiguousIOList.class, Integer.class);
					var actual = new ArrayList<Integer>();
					for(int i = 0; i<list.size(); i++) actual.add(list.get(i));
					assertThat(actual).as("seed=%s cutoff=%s mapped=%s acknowledged=%s", seed, cutoff, mapped, acknowledged).isEqualTo(expected);
				}
			}finally{
				Files.deleteIfExists(directory.resolve("data.db.wal"));
				Files.deleteIfExists(progress); Files.deleteIfExists(path); Files.deleteIfExists(directory);
			}
		}
	}
}
