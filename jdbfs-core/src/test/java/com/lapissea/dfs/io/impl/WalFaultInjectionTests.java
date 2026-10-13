package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.content.WordIO;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Power-loss model: successful barriers persist everything; unforced writes may tear or disappear.
 */
public class WalFaultInjectionTests{
	private static final class PowerLoss extends Error{ }
	
	private record Patch(long offset, byte[] bytes, int size){ }
	
	private static final class Disk{
		byte[] live = new byte[0], stable = new byte[0];
		final List<Patch> pending = new ArrayList<>();
		void write(long offset, byte[] bytes){
			int end = Math.toIntExact(offset + bytes.length);
			if(end>live.length) live = Arrays.copyOf(live, end);
			System.arraycopy(bytes, 0, live, (int)offset, bytes.length);
			pending.add(new Patch(offset, bytes.clone(), -1));
		}
		void resize(long size){
			live = Arrays.copyOf(live, Math.toIntExact(size));
			pending.add(new Patch(0, null, Math.toIntExact(size)));
		}
		void force(){
			stable = live.clone();
			pending.clear();
		}
		void powerLoss(Random random){
			// Any subset of unforced writes can reach storage, in any order, with torn bytes.
			var shuffled = new ArrayList<>(pending);
			Collections.shuffle(shuffled, random);
			for(var patch : shuffled){
				if(random.nextBoolean()) continue;
				if(patch.bytes == null){
					stable = Arrays.copyOf(stable, patch.size);
					continue;
				}
				int len   = patch.bytes.length;
				int start = random.nextInt(len + 1), end = start + random.nextInt(len - start + 1);
				if(end == start) continue;
				int target = Math.toIntExact(patch.offset) + start;
				if(target + end - start>stable.length) stable = Arrays.copyOf(stable, target + end - start);
				System.arraycopy(patch.bytes, start, stable, target, end - start);
			}
			live = stable.clone();
			pending.clear();
		}
	}
	
	private static final class Controller{
		final Disk main, wal;
		final Random random;
		int event, cutoff = Integer.MAX_VALUE;
		boolean dead;
		String  lastEvent;
		Controller(Disk main, Disk wal, long seed){
			this.main = main;
			this.wal = wal;
			random = new Random(seed);
		}
		void step(String label, Runnable operation){
			if(dead) return; // Frozen/crashed device: subsequent mutations are no-ops.
			lastEvent = label;
			if(++event == cutoff && random.nextBoolean()) crash();
			operation.run();
			if(event == cutoff) crash();
		}
		void force(String label, Disk disk){
			if(dead) throw new PowerLoss(); // Never acknowledge a barrier that did not persist its writes.
			step(label, disk::force);
		}
		void crash(){
			dead = true;
			main.powerLoss(random);
			wal.powerLoss(random);
			throw new PowerLoss();
		}
	}
	
	/**
	 * Uses the production CursorIOData, transaction state, FileIOData, and WAL; replaces only storage.
	 */
	private static final class FaultIO extends FileIOData{
		final Controller controller;
		FaultIO(Path name, Controller controller, boolean durable) throws IOException{
			super(null, name.toFile(), false, durable);
			this.controller = controller;
			try{
				used = getLength();
				initializeJournal(new FileWriteAheadLog(new DiskChannel(controller)));
			}catch(Throwable e){
				releaseFileOwnership();
				throw e;
			}
		}
		@Override
		protected long getLength(){ return controller.main.live.length; }
		@Override
		protected void resize(long size){ controller.step("main resize", () -> controller.main.resize(size)); }
		@Override
		protected void forceFile(){ controller.force("main force", controller.main); }
		@Override
		protected byte read1(long offset){ return controller.main.live[Math.toIntExact(offset)]; }
		@Override
		protected void readN(long offset, byte[] dest, int off, int len){ System.arraycopy(controller.main.live, Math.toIntExact(offset), dest, off, len); }
		@Override
		protected void write1(long offset, byte value){ writeN(offset, new byte[]{value}, 0, 1); }
		@Override
		protected void writeN(long offset, byte[] data, int off, int len){
			var bytes = Arrays.copyOfRange(data, off, off + len);
			controller.step("main write", () -> controller.main.write(offset, bytes));
		}
		@Override
		protected long readWord(long offset, int len){
			return WordIO.getWord(controller.main.live, Math.toIntExact(offset), len);
		}
		@Override
		protected void writeWord(long offset, long value, int len){
			var bytes = new byte[len];
			WordIO.setWord(value, bytes, 0, len);
			writeN(offset, bytes, 0, len);
		}
		@Override
		public FaultIO asReadOnly(){ throw new UnsupportedOperationException(); }
		@Override
		public boolean equals(Object other){ return this == other; }
		@Override
		public int hashCode(){ return System.identityHashCode(this); }
		@Override
		public void close(){
			// Simulate process death: release ownership without checkpointing or flushing on close.
			try(var ignored = closeAccess()){
				markClosed();
				releaseFileOwnership();
			}
		}
	}
	
	private static final class DiskChannel extends FileChannel{
		final Controller controller;
		long position;
		DiskChannel(Controller controller){ this.controller = controller; }
		@Override
		public int write(ByteBuffer source){
			int len   = source.remaining();
			var bytes = new byte[len];
			source.get(bytes);
			long at = position;
			controller.step("wal write", () -> controller.wal.write(at, bytes));
			position += len;
			return len;
		}
		@Override
		public int read(ByteBuffer target){
			int len = (int)Math.min(target.remaining(), controller.wal.live.length - position);
			if(len<=0) return -1;
			target.put(controller.wal.live, Math.toIntExact(position), len);
			position += len;
			return len;
		}
		@Override
		public long position(){ return position; }
		@Override
		public FileChannel position(long position){
			this.position = position;
			return this;
		}
		@Override
		public long size(){ return controller.wal.live.length; }
		@Override
		public FileChannel truncate(long size){
			if(size<size()) controller.step("wal truncate", () -> controller.wal.resize(size));
			position = Math.min(position, size);
			return this;
		}
		@Override
		public void force(boolean metadata){ controller.force("wal force", controller.wal); }
		@Override
		protected void implCloseChannel(){ }
		@Override
		public long write(ByteBuffer[] buffers, int off, int len){
			long total = 0;
			for(int i = off; i<off + len; i++)
				total += write(buffers[i]);
			return total;
		}
		@Override
		public long read(ByteBuffer[] buffers, int off, int len){ throw new UnsupportedOperationException(); }
		@Override
		public int read(ByteBuffer buffer, long position){ throw new UnsupportedOperationException(); }
		@Override
		public int write(ByteBuffer buffer, long position){ throw new UnsupportedOperationException(); }
		@Override
		public long transferTo(long position, long count, WritableByteChannel target){ throw new UnsupportedOperationException(); }
		@Override
		public long transferFrom(ReadableByteChannel source, long position, long count){ throw new UnsupportedOperationException(); }
		@Override
		public MappedByteBuffer map(MapMode mode, long position, long size){ throw new UnsupportedOperationException(); }
		@Override
		public FileLock lock(long position, long size, boolean shared){ throw new UnsupportedOperationException(); }
		@Override
		public FileLock tryLock(long position, long size, boolean shared){ throw new UnsupportedOperationException(); }
	}
	
	private static List<Integer> readValues(FaultIO io) throws IOException{
		ContiguousIOList<Integer> list   = new Cluster(io).roots().request("values", ContiguousIOList.class, Integer.class);
		var                       values = new ArrayList<Integer>();
		for(int i = 0; i<list.size(); i++) values.add(list.get(i));
		return values;
	}
	
	private static String campaign(long seed, int cutoff, boolean durable) throws Exception{
		var           name         = Files.createTempFile("wal-fault-model", ".db");
		var           main         = new Disk();
		var           wal          = new Disk();
		var           controller   = new Controller(main, wal, seed);
		var           acknowledged = new ArrayList<Integer>();
		List<Integer> inFlight     = acknowledged;
		try{
			try(var source = new FaultIO(name, controller, durable)){
				ContiguousIOList<Integer> list = Cluster.initOrOpen(source).roots().request("values", ContiguousIOList.class, Integer.class);
				source.checkpoint();
				controller.event = 0;
				controller.cutoff = cutoff;
				var choices = new Random(seed);
				try{
					for(int batch = 0; batch<20; batch++){
						var expected = new ArrayList<>(acknowledged);
						inFlight = expected;
						try(var tx = source.startTransaction()){
							for(int op = 0; op<1 + choices.nextInt(5); op++){
								int value = choices.nextInt();
								if(expected.isEmpty() || choices.nextInt(3) == 0){
									list.add(value);
									expected.add(value);
								}else if(choices.nextBoolean()){
									int index = choices.nextInt(expected.size());
									list.set(index, value);
									expected.set(index, value);
								}else{
									int index = choices.nextInt(expected.size());
									list.remove(index);
									expected.remove(index);
								}
							}
							tx.commit();
						}
						acknowledged = expected;
						if(batch%3 == 0) source.checkpoint();
					}
					controller.crash();
				}catch(PowerLoss expected){ }
			}
			// Open a new IO/provider against only persisted bytes, including real WAL replay.
			var recoveredController = new Controller(main, wal, seed);
			try(var source = new FaultIO(name, recoveredController, false)){
				var actual = readValues(source);
				assertThat(actual.equals(acknowledged) || actual.equals(inFlight))
					.as("seed=%s cutoff=%s event=%s phase=%s acknowledged=%s inFlight=%s actual=%s",
					    seed, cutoff, controller.event, controller.lastEvent, acknowledged, inFlight, actual).isTrue();
				assertThat(wal.stable).isEmpty();
				// Recovery is idempotent, and the recovered database remains writable.
				source.openIOTransaction(() -> {
					ContiguousIOList<Integer> list = new Cluster(source).roots().request("values", ContiguousIOList.class, Integer.class);
					list.add(12345);
				});
				source.checkpoint();
			}
			try(var source = new FaultIO(name, new Controller(main, wal, seed), false)){
				assertThat(readValues(source)).endsWith(12345);
			}
		}catch(Throwable failure){
			throw new AssertionError("Reproduce with seed=" + seed + " cutoff=" + cutoff + " durable=" + durable + " phase=" + controller.lastEvent, failure);
		}finally{ Files.deleteIfExists(name); }
		return controller.lastEvent;
	}
	
	@Test(timeOut = 120_000)
	public void largeWritesSurviveSectorTearsDuringWalForce() throws Exception{
		int cases = Integer.getInteger("wal.fault.largeCases", 2000);
		for(int trial = 0; trial<cases; trial++){
			long seed   = trial == 0? 1413828911L : 0x54454152L + trial; // Retain the torn-header regression seed.
			var  name   = Files.createTempFile("wal-large-tear", ".db");
			var  main   = new Disk();
			var  wal    = new Disk();
			var  random = new Random(seed);
			var  before = new byte[8192];
			random.nextBytes(before);
			main.write(0, before);
			main.force();
			var after  = before.clone();
			var first  = new byte[2048];
			var second = new byte[4096];
			random.nextBytes(first);
			random.nextBytes(second);
			System.arraycopy(first, 0, after, 0, first.length);
			System.arraycopy(second, 0, after, 4096, second.length);
			var controller = new Controller(main, wal, seed);
			try{
				try(var source = new FaultIO(name, controller, true)){
					controller.event = 0;
					controller.cutoff = 4; // Header, payload, trailer, WAL force.
					try{
						source.openIOTransaction(() -> {
							source.write(0, false, first);
							source.write(4096, false, second);
						});
						throw new AssertionError("Cut point was not reached");
					}catch(PowerLoss expected){ }
				}
				try(var source = new FaultIO(name, new Controller(main, wal, seed), false)){
					var actual = source.readAll();
					assertThat(Arrays.equals(actual, before) || Arrays.equals(actual, after)).as("large tear seed=%s", seed).isTrue();
				}
			}catch(Throwable failure){ throw new AssertionError("large tear seed=" + seed, failure); }finally{ Files.deleteIfExists(name); }
		}
	}
	
	@Test(timeOut = 30_000)
	public void sameCrashModelDetectsCorruptionWithoutWal() throws Exception{
		int detected = 0;
		for(int i = 0; i<32; i++){
			try{ campaign(0x57414CL + i, 1 + i%8, false); }catch(AssertionError expected){ detected++; }
		}
		assertThat(detected).as("negative control: model must detect broken atomicity without WAL").isPositive();
	}
	
	@Test(timeOut = 120_000)
	public void randomPowerLossRecoversWholeAcknowledgedPrefix() throws Exception{
		int  cases     = Integer.getInteger("wal.fault.cases", 300);
		long firstSeed = Long.getLong("wal.fault.seed", 0x57414CL);
		var  phases    = new HashSet<String>();
		for(int i = 0; i<cases; i++)
			phases.add(campaign(firstSeed + i, Integer.getInteger("wal.fault.cutoff", 1 + new Random(firstSeed + i).nextInt(180)), true));
		if(cases>=300 && System.getProperty("wal.fault.cutoff") == null)
			assertThat(phases).contains("wal write", "wal force", "main write", "main resize", "main force", "wal truncate");
	}
}
