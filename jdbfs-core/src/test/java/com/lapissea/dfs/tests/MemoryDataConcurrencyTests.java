package com.lapissea.dfs.tests;

import com.lapissea.dfs.io.impl.MemoryData;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class MemoryDataConcurrencyTests{
	@Test
	public void disjointWritesSurviveConcurrentBackingGrowth() throws Exception{
		try(var executor = Executors.newFixedThreadPool(5)){
			for(int round = 0; round<16; round++){
				var source  = MemoryData.of(new byte[4096]);
				var start   = new CountDownLatch(1);
				var futures = new ArrayList<Future<?>>();
				for(int region = 0; region<4; region++){
					int index = region;
					futures.add(executor.submit(() -> {
						start.await();
						for(int offset = index*1024; offset<(index + 1)*1024; offset++){
							try(var io = source.ioAt(offset)){ io.writeInt1(index + 1); }
						}
						return null;
					}));
				}
				futures.add(executor.submit(() -> {
					start.await();
					for(int offset = 4096; offset<32768; offset += 67){
						try(var io = source.ioAt(offset)){ io.writeInt1(123); }
					}
					return null;
				}));
				start.countDown();
				for(var future : futures) future.get(10, TimeUnit.SECONDS);
				var bytes = source.readAll();
				for(int offset = 0; offset<4096; offset++) assertEquals(bytes[offset], (byte)(offset/1024 + 1), "Lost write at " + offset);
			}
		}
	}
	@Test
	public void concurrentWritesNeverReduceLogicalEnd() throws Exception{
		var source = MemoryData.empty();
		var start  = new CountDownLatch(1);
		var active = new AtomicInteger(4);
		try(var executor = Executors.newFixedThreadPool(4)){
			var futures = new ArrayList<Future<?>>();
			for(int region = 0; region<4; region++){
				int index = region;
				futures.add(executor.submit(() -> {
					try{
						start.await();
						for(int offset = 0; offset<2048; offset++){
							try(var io = source.ioAt(index*2048L + offset)){ io.writeInt1(index + 1); }
						}
					}finally{ active.decrementAndGet(); }
					return null;
				}));
			}
			start.countDown();
			long previous = 0;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while(active.get() != 0){
				long end = source.getIOSize();
				assertTrue(end>=previous, "Logical EOF decreased from " + previous + " to " + end);
				previous = end;
				assertTrue(System.nanoTime()<deadline, "Concurrent writes timed out");
				Thread.onSpinWait();
			}
			for(var future : futures) future.get(10, TimeUnit.SECONDS);
		}
		assertEquals(source.getIOSize(), 8192L);
		var bytes = source.readAll();
		for(int offset = 0; offset<bytes.length; offset++) assertEquals(bytes[offset], (byte)(offset/2048 + 1));
	}
	@Test
	public void sparseWritePreservesRequestedPosition() throws IOException{
		var source = MemoryData.empty();
		try(var io = source.ioAt(32)){ io.writeInt8(12345); }
		assertEquals(source.getIOSize(), 40L);
		try(var io = source.ioAt(32)){ assertEquals(io.readInt8(), 12345L); }
	}
	@Test(timeOut = 10000)
	public void writeHookCanOpenTransactionAndReadSnapshot() throws IOException{
		var snapshots = new AtomicInteger();
		var source = MemoryData.builder().withRaw(new byte[4]).withOnWrite((data, changed) -> {
			try(var scope = data.openIOTransaction()){
				assertEquals(data.readAll(), new byte[]{1, 2, 3, 4});
				snapshots.incrementAndGet();
			}
		}).build();
		try(var io = source.io()){ io.write(new byte[]{1, 2, 3, 4}); }
		assertEquals(snapshots.get(), 1);
	}
}
