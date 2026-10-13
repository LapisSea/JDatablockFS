package com.lapissea.dfs.tests;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOTransactionBuffer;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.FileIOData;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.iterableplus.OptionalPP;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

public class FileWalTests{
	private static FileIOData open(Path path, boolean mapped, boolean durable) throws IOException{
		return mapped? new FileMemoryMappedData(path.toFile(), false, durable) : new FileRandomAccessData(path.toFile(), false, durable);
	}
	private static Path wal(Path path)                       { return path.resolveSibling(path.getFileName() + ".wal"); }
	private static void cleanup(Path path) throws IOException{ Files.deleteIfExists(wal(path)); Files.deleteIfExists(path); }
	
	@Test
	public void standaloneWritesRollbackAndCheckpoint() throws Exception{
		for(boolean mapped : new boolean[]{false, true}){
			var path = Files.createTempFile("wal-basic", ".db");
			try{
				try(var data = open(path, mapped, true)){
					assertThat(data.isDurable()).isTrue();
					data.write(0, false, new byte[]{1, 2, 3, 4});
					long before = Files.size(wal(path));
					try(var tx = data.startTransaction()){
						data.write(0, false, new byte[]{9});
					}
					assertThat(Files.size(wal(path))).isEqualTo(before);
					assertThat(data.readAll()).containsExactly(1, 2, 3, 4);
					data.openIOTransaction(() -> {
						try(var io = data.io()){
							io.writeAtOffsets(List.of(new RandomIO.WriteChunk(0, new byte[]{7}), new RandomIO.WriteChunk(3, new byte[]{8})));
						}
					});
					data.checkpoint();
					assertThat(Files.size(wal(path))).isZero();
					try(var io = data.io()){
						io.setSize(2);
						io.setPos(2).writeWord(0x0605, 2);
						io.setPos(0).fillZero(1);
					}
					assertThat(data.readAll()).containsExactly(0, 2, 5, 6);
				}
				try(var data = open(path, !mapped, false)){
					assertThat(data.isDurable()).isFalse();
					assertThat(data.readAll()).containsExactly(0, 2, 5, 6);
				}
			}finally{ cleanup(path); }
		}
	}
	
	/**
	 * Child exits without shutdown hooks immediately after the first main-file mutation.
	 */
	public static void main(String[] args) throws Exception{
		var     path   = Path.of(args[0]);
		boolean mapped = Boolean.parseBoolean(args[1]);
		if(args.length>2){
			var data = mapped? new FileMemoryMappedData(path.toFile()) : new FileRandomAccessData(path.toFile());
			assertThat(data.isDurable()).isTrue();
			data.write(0, false, new byte[64]);
			assertThat(Files.size(wal(path))).isPositive();
			var values = new byte[64];
			Arrays.fill(values, (byte)7);
			data.write(0, false, values);
			assertThat(Files.size(wal(path))).isZero();
			Runtime.getRuntime().halt(74);
		}
		var hook = (IOHook)(source, offsets) -> Runtime.getRuntime().halt(73);
		var data = mapped? new FileMemoryMappedData(hook, path.toFile(), false, true) :
		           new FileRandomAccessData(hook, path.toFile(), FileRandomAccessData.Mode.READ_WRITE, true);
		try(var tx = data.startTransaction(); var io = data.io()){
			io.writeAtOffsets(List.of(new RandomIO.WriteChunk(0, new byte[]{9}), new RandomIO.WriteChunk(7, new byte[]{8})));
			io.setPos(9).write(0);
			io.setCapacity(10);
			tx.commit();
		}
		throw new AssertionError("Crash hook was not called");
	}
	
	private static byte[] crash(Path path, boolean mapped) throws Exception{
		Files.write(path, new byte[]{1, 2, 3, 4});
		var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		var classpath      = System.getProperty("java.class.path") + File.pathSeparator + System.getProperty("jdk.module.path", "");
		var process = new ProcessBuilder(javaExecutable, "-cp", classpath,
		                                 FileWalTests.class.getName(), path.toString(), Boolean.toString(mapped)).redirectErrorStream(true).start();
		assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
		assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes())).isEqualTo(73);
		return Files.readAllBytes(wal(path));
	}
	
	@Test(timeOut = 60_000)
	public void crashRecoversAcrossBackendsEvenWithWalDisabled() throws Exception{
		for(boolean mapped : new boolean[]{false, true}){
			var path = Files.createTempFile("wal-crash", ".db");
			try{
				crash(path, mapped);
				assertThatThrownBy(() -> new FileRandomAccessData(path.toFile(), true)).isInstanceOf(IOException.class).hasMessageContaining("recovery");
				try(var data = open(path, !mapped, false)){
					assertThat(data.readAll()).containsExactly(9, 2, 3, 4, 0, 0, 0, 8, 0, 0);
					assertThat(Files.size(wal(path))).isZero();
				}
			}finally{ cleanup(path); }
		}
	}
	
	@Test(timeOut = 60_000)
	public void ignoresEveryIncompleteTailAndRejectsChecksumCorruption() throws Exception{
		var path = Files.createTempFile("wal-tail", ".db");
		try{
			byte[] record = crash(path, false);
			for(int cut = 1; cut<record.length; cut++){
				Files.write(path, new byte[]{1, 2, 3, 4});
				Files.write(wal(path), Arrays.copyOf(record, cut));
				try(var data = open(path, false, false)){
					assertThat(data.readAll()).containsExactly(1, 2, 3, 4);
				}
			}
			// Earlier durable frames survive an incomplete following append.
			for(int cut = 1; cut<record.length; cut++){
				Files.write(path, new byte[]{1, 2, 3, 4});
				var prefixAndTail = Arrays.copyOf(record, record.length + cut);
				System.arraycopy(record, 0, prefixAndTail, record.length, cut);
				Files.write(wal(path), prefixAndTail);
				try(var data = open(path, true, false)){
					assertThat(data.readAll()).containsExactly(9, 2, 3, 4, 0, 0, 0, 8, 0, 0);
				}
			}
			Files.write(path, new byte[]{1, 2, 3, 4});
			record[28] ^= 1;
			var corruptInterior = new byte[record.length*2];
			System.arraycopy(record, 0, corruptInterior, 0, record.length);
			System.arraycopy(record, 0, corruptInterior, record.length, record.length);
			Files.write(wal(path), corruptInterior);
			assertThatThrownBy(() -> open(path, true, false)).isInstanceOf(IOException.class).hasMessageContaining("checksum");
			assertThat(Files.size(wal(path))).isEqualTo(corruptInterior.length);
			Files.write(wal(path), new byte[0]);
			try(var data = open(path, true, false)){ assertThat(data.readAll()).containsExactly(1, 2, 3, 4); }
		}finally{ cleanup(path); }
	}
	
	@Test(timeOut = 30_000)
	public void symlinkOpenRecoversTheCanonicalSidecar() throws Exception{
		var path  = Files.createTempFile("wal-target", ".db");
		var alias = path.resolveSibling(path.getFileName() + ".alias");
		try{
			crash(path, false);
			Files.createSymbolicLink(alias, path.getFileName());
			try(var data = open(alias, true, false)){
				assertThat(data.readAll()).containsExactly(9, 2, 3, 4, 0, 0, 0, 8, 0, 0);
			}
			assertThat(Files.exists(wal(alias))).isFalse();
		}finally{ Files.deleteIfExists(alias); cleanup(path); }
	}
	
	@Test(timeOut = 60_000)
	public void automaticCheckpointSurvivesAbruptExit() throws Exception{
		for(boolean mapped : new boolean[]{false, true}){
			var path = Files.createTempFile("wal-checkpoint", ".db");
			try{
				var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
				var classpath      = System.getProperty("java.class.path") + File.pathSeparator + System.getProperty("jdk.module.path", "");
				var process = new ProcessBuilder(javaExecutable, "-Ddfs.io.durableFileIO=true", "-Ddfs.io.walCheckpointBytes=128", "-cp", classpath,
				                                 FileWalTests.class.getName(), path.toString(), Boolean.toString(mapped), "checkpoint").redirectErrorStream(true).start();
				assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
				assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes())).isEqualTo(74);
				try(var data = new FileRandomAccessData(path.toFile(), true)){
					assertThat(data.readAll()).hasSize(64).containsOnly((byte)7);
				}
			}finally{ cleanup(path); }
		}
	}
	
	@Test
	public void failedPhysicalCommitRetainsRecoveryLogOnClose() throws Exception{
		for(boolean mapped : new boolean[]{false, true}){
			var path = Files.createTempFile("wal-failure", ".db");
			try{
				Files.write(path, new byte[]{1, 2, 3, 4});
				var hook = (IOHook)(source, offsets) -> { throw new IOException("simulated apply failure"); };
				try(var data = mapped? new FileMemoryMappedData(hook, path.toFile(), false, true) :
				               new FileRandomAccessData(hook, path.toFile(), FileRandomAccessData.Mode.READ_WRITE, true)){
					var changes = new IOTransactionBuffer.TransactionExport(
						OptionalPP.empty(), List.of(new RandomIO.WriteChunk(0, new byte[]{9, 8})));
					// Exercise the batch entry point directly, outside an explicit transaction as well.
					assertThatThrownBy(() -> data.applyTransaction(changes)).isInstanceOf(RuntimeException.class);
					assertThatThrownBy(data::readAll).isInstanceOf(IllegalStateException.class);
				}
				assertThat(Files.size(wal(path))).isPositive();
				try(var data = open(path, !mapped, false)){
					assertThat(data.readAll()).containsExactly(9, 8, 3, 4);
				}
			}finally{ cleanup(path); }
		}
	}
	
	@Test(timeOut = 20_000)
	public void otherThreadsWaitAndOtherHandlesCannotOpen() throws Exception{
		for(boolean mapped : new boolean[]{false, true}){
			var path = Files.createTempFile("wal-thread", ".db");
			try(var data = open(path, mapped, true); var pool = Executors.newVirtualThreadPerTaskExecutor()){
				data.write(0, false, new byte[]{1});
				assertThatThrownBy(() -> open(path, !mapped, true)).isInstanceOf(IOException.class).hasMessageContaining("access");
				// Failed same-JVM opens must not weaken the OS lock held against another process.
				var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
				var classpath      = System.getProperty("java.class.path") + File.pathSeparator + System.getProperty("jdk.module.path", "");
				var process = new ProcessBuilder(javaExecutable, "-cp", classpath, FileWalTests.class.getName(),
				                                 path.toString(), Boolean.toString(!mapped)).redirectErrorStream(true).start();
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
				assertThat(process.exitValue()).isEqualTo(1);
				assertThat(new String(process.getInputStream().readAllBytes())).contains("Unable to acquire file access");
				var       started = new CountDownLatch(1);
				Future<?> task;
				try(var tx = data.startTransaction()){
					data.write(0, false, new byte[]{2});
					task = pool.submit(() -> { started.countDown(); data.write(1, false, new byte[]{3}); return null; });
					assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
					assertThatThrownBy(() -> task.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
					tx.commit();
				}
				task.get(5, TimeUnit.SECONDS);
				assertThat(data.readAll()).containsExactly(2, 3);
			}finally{ cleanup(path); }
		}
	}
	
	@Test
	public void clusterCollectionsAreTransparentWithAndWithoutWal() throws Exception{
		for(boolean mapped : new boolean[]{false, true})
			for(boolean durable : new boolean[]{false, true}){
				var path = Files.createTempFile("wal-cluster", ".db");
				try{
					try(var data = open(path, mapped, durable)){
						var                       cluster = Cluster.initOrOpen(data);
						ContiguousIOList<Integer> list    = cluster.roots().request("values", ContiguousIOList.class, Integer.class);
						data.openIOTransaction(() -> { for(int i = 0; i<100; i++) list.add(i); });
						assertThat(list.size()).isEqualTo(100);
					}
					try(var data = open(path, mapped, durable)){
						ContiguousIOList<Integer> list = new Cluster(data).roots().request("values", ContiguousIOList.class, Integer.class);
						for(int i = 0; i<100; i++) assertThat(list.get(i)).isEqualTo(i);
					}
					if(!durable) assertThat(Files.exists(wal(path))).isFalse();
				}finally{ cleanup(path); }
			}
	}
}
