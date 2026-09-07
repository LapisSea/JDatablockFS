package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.fail;

/**
 * <h1>BUG-42: the free path mutates file/chunk state without the synchronization the concurrent append path relies on
 * (documented instance: {@code tryPopFree} invoked unlocked from {@code free()}, PersistentMemoryManager.java:320)</h1>
 *
 * <p><b>What the code should do.</b> Appends and frees must be mutually consistent. The append path
 * ({@code MemoryOperations.allocateAppendToFile}, MemoryOperations.java:752-773) runs under {@code fileSizeLock}
 * (PersistentMemoryManager.java:186-190) and spans: read the file tail (:755-756 via {@code walkToLastChunk}),
 * write the new chunk at that offset (:763-766), then extend the file to the new end ({@code correctIOsize},
 * :767-770). The free path's tail popping ({@code popFile}, PersistentMemoryManager.java:418-495) runs under
 * {@code fileSizeLock} (:278-283) and the free-list merge phase ({@code MemoryOperations.mergeChunks}) runs under
 * {@code freeChunksLock} (:295-318) while rewriting freed chunks' headers on disk. Under a consistent discipline,
 * an append always writes at a valid tail, and a chunk's cached/in-memory state always agrees with the disk header.
 *
 * <p><b>What the code actually does.</b> The free path is not fully serialized against the append path:
 * <ul>
 *  <li>At the end of every {@code free()}, {@code tryPopFree()} (PersistentMemoryManager.java:327-416, invoked
 *  <i>unlocked</i> from :320) re-reads the free list ({@code freeChunks.getLast()} :334), removes from it
 *  ({@code freeChunks.removeLast()} :338) and truncates the backing file ({@code io.setCapacity} :340-342) holding
 *  neither {@code fileSizeLock} nor {@code freeChunksLock}. When the just-freed tail chunk is popped, the
 *  truncation can land inside a concurrent append's read-tail&rarr;write&rarr;extend window, so the append
 *  materialises its chunk at a stale offset past the new file end: a hole is left between the live data and the
 *  appended chunk, or the just-written chunk is deleted/overwritten.</li>
 *  <li>The unlocked free-list read/remove (:334/:338) can interleave with the locked merge phase (:295-318) and
 *  with {@code REUSE_FREE_CHUNKS} allocation (:176-183), corrupting the free list (lost/duplicated pointers
 *  &rarr; double allocation &rarr; overwritten object data).</li>
 *  <li>Freeing rewrites a chunk's on-disk header (purge, {@code mergeChunks} :314-345) while the append path's
 *  physical walk reads it; in debug builds {@code DataProvider.ensureChunkValid} (DataProvider.java:129-141)
 *  detects the resulting cache/disk disagreement as a {@code CacheOutOfSync}.</li>
 * </ul>
 *
 * <p><b>Why the test fails.</b> Two appender threads provide new roots in a tight loop (each {@code provide}
 * grows the file tail via {@code APPEND_TO_FILE}); two freer threads drop the <i>most recently added</i> roots
 * (LIFO), so freed chunks sit at the file tail and the unlocked {@code tryPopFree} truncation branch fires
 * continuously against concurrent appends. Workers run for a fixed wall time; debug-assertion hits
 * (e.g. {@code CacheOutOfSync}) are recorded but do not stop them, so the race runs to completion. Afterwards the
 * exact file bytes are re-opened in a <i>fresh</i> session (no chunk cache to mask anything) and verified:
 * <ol>
 *  <li>{@code scanGarbage(ERROR)} (DefragmentManager.java:482-510) throws {@code MalformedFile} on any
 *  physically-present chunk that is neither referenced nor in the free list (hole/orphan/stale chunk);</li>
 *  <li>every surviving root is re-read and compared byte-for-byte with an in-memory oracle, which catches
 *  truncated, deleted or double-allocated (overwritten) objects.</li>
 * </ol>
 * A failure of (1)/(2) demonstrates the corrupted file state produced by the unsynchronized free path. If the
 * final bytes happen to be intact but workers hit integrity violations mid-run, those are reported instead as the
 * closest observable manifestation of the same defect.
 */
public class ReproMemMgrUnlockedFreeTests {
	
	@IOValue
	static class Payload extends IOInstance.Managed<Payload>{
		private final long   seed;
		private final byte[] data;
		
		public Payload(long seed, byte[] data){
			this.seed = seed;
			this.data = data;
		}
		public long   seed(){ return seed; }
		public byte[] data(){ return data; }
	}
	
	private static final int    APPENDERS         = 2;
	private static final int    FREERS            = 2;
	private static final long   RUN_SECONDS       = 30;
	private static final int    MAX_WORKER_ERRORS = 100;
	
	@Test(timeOut = 85_000)
	public void memMgrUnlockedFree() throws Exception{
		var cluster = Cluster.emptyMem();
		
		//Long-lived prefix object: keeps a stable live head so the tail (where the LIFO frees land)
		//keeps churning between append and the unlocked tryPopFree truncation.
		var seed = makePayload(0, 64*1024);
		cluster.roots().provide(0, seed);
		
		var oracle   = new ConcurrentHashMap<Long, byte[]>();
		oracle.put(0L, seed.data());
		var dropped  = new ConcurrentLinkedDeque<Long>(); //LIFO stack: newest root is freed first
		var stop     = new CountDownLatch(1);
		var firstErr = new AtomicReference<Throwable>();
		var provides = new AtomicLong();
		var drops    = new AtomicLong();
		
		var threads = new Thread[APPENDERS + FREERS];
		for(int i = 0; i<threads.length; i++){
			final int idx       = i;
			final boolean appender = idx<APPENDERS;
			threads[idx] = new Thread(() -> {
				var errs = 0;
				try{
					while(!stop.await(1, TimeUnit.MILLISECONDS)){
						try{
							if(appender){
								appenderStep(cluster, oracle, dropped, provides);
							}else{
								freerStep(cluster, oracle, dropped, drops);
							}
						}catch(Throwable t){
							//An integrity violation (CacheOutOfSync, pointer/size failure) inside the
							//alloc/free paths is itself a manifestation of the unsynchronized free path;
							//record it and keep racing so a persistent file corruption can also materialise.
							firstErr.compareAndSet(null, t);
							if(++errs>=MAX_WORKER_ERRORS) break;
						}
					}
				}catch(InterruptedException e){
					Thread.currentThread().interrupt();
				}
			}, appender? "repro-appender-" + idx : "repro-freer-" + (idx - APPENDERS));
		}
		for(var t : threads){
			t.start();
		}
		
		var deadline = System.nanoTime() + RUN_SECONDS * 1_000_000_000L;
		while(System.nanoTime() < deadline && anyAlive(threads)){
			Thread.sleep(100);
		}
		stop.countDown();
		
		//Give the workers a bounded grace period to exit. If any is still blocked after the stop
		//signal, that hang is itself a manifestation of the racy lock discipline (e.g. a worker
		//deadlocked between fileSizeLock/freeChunksLock) and is recorded as evidence.
		var joinDeadline = System.nanoTime() + 10 * 1_000_000_000L;
		while(System.nanoTime() < joinDeadline && anyAlive(threads)){
			Thread.sleep(50);
		}
		var hung = new ArrayList<Thread>();
		for(var t : threads){
			if(t.isAlive()){
				hung.add(t);
			}
		}
		
		var evidence = new StringBuilder();
		if(!hung.isEmpty()){
			evidence.append("WARNING: ").append(hung.size()).append(" worker(s) did not exit after the stop "
			              + "signal (blocked inside the alloc/free paths - racy lock discipline):\n");
			for(var t : hung){
				evidence.append(t.getName()).append(" stuck at:\n");
				for(var el : t.getStackTrace()){
					evidence.append("\t").append(el).append('\n');
				}
			}
		}
		
		//--- Definitive verification: re-open the exact bytes in a fresh session (no chunk cache,
		//    independent of the live cluster, so it cannot be affected by the live workers' locks) ---
		byte[] snapshot;
		try{
			snapshot = cluster.getSource().readAll();
		}catch(Throwable t){
			fail("state corrupted so badly it cannot even be snapshotted: " + stack(t) + evidence);
			return;
		}
		
		Cluster fresh;
		try{
			fresh = new Cluster(MemoryData.of(snapshot));
		}catch(Throwable t){
			fail("file corrupted by the unsynchronized free path: cannot be re-opened: " + stack(t) + evidence);
			return;
		}
		
		//(1) Structural check: a hole/orphan/stale mid-file chunk surfaces as an unknown free chunk.
		try{
			fresh.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
		}catch(Throwable t){
			fail("scanGarbage found unreferenced/stale chunks (hole/orphan) after concurrent append+free: "
			     + stack(t) + evidence
			     + "\n[provides=" + provides.get() + ", drops=" + drops.get() + "]");
			return;
		}
		
		//(2) Content check: every surviving root must come back byte-identical to what was written.
		for(var entry : oracle.entrySet()){
			Payload got;
			try{
				got = fresh.roots().require(entry.getKey(), Payload.class);
			}catch(Throwable t){
				fail("surviving root " + entry.getKey() + " unreadable in fresh session (truncated/deleted chunk): "
				     + stack(t) + evidence
				     + "\n[provides=" + provides.get() + ", drops=" + drops.get() + "]");
				return;
			}
			if(got.seed() != entry.getKey() || !Arrays.equals(got.data(), entry.getValue())){
				fail("content corruption for root " + entry.getKey()
				     + " (expected " + entry.getValue().length + " bytes) - double allocation/overwrite"
				     + evidence
				     + "\n[provides=" + provides.get() + ", drops=" + drops.get() + "]");
				return;
			}
		}
		
		//No persistent corruption in the final bytes, but the race was still observed in-flight.
		if(!hung.isEmpty()){
			fail("workers deadlocked inside the unsynchronized alloc/free paths during concurrent "
			     + "append+free - closest observable of the defect:\n" + evidence
			     + "\n[provides=" + provides.get() + ", drops=" + drops.get() + "]");
			return;
		}
		if(firstErr.get() != null){
			fail("worker hit an integrity violation during concurrent append+free (free path mutates chunk/file "
			     + "state without synchronization against the append path) - closest observable of the defect: "
			     + stack(firstErr.get())
			     + "\n[provides=" + provides.get() + ", drops=" + drops.get() + "]");
			return;
		}
		
		fail("INCONCLUSIVE: " + provides.get() + " provides / " + drops.get() + " drops over " + RUN_SECONDS
		     + "s - no corruption detected" + evidence);
	}
	
	private static boolean anyAlive(Thread[] threads){
		for(var t : threads){
			if(t.isAlive()) return true;
		}
		return false;
	}
	
	private static String stack(Throwable t){
		var sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}
	
	private static Payload makePayload(long seed, int size){
		var data = new byte[size];
		new Random(seed * 0x9E3779B97F4A7C15L).nextBytes(data);
		return new Payload(seed, data);
	}
	
	/**One append step: a new root at the file tail (APPEND_TO_FILE path, the racing side).*/
	private static void appenderStep(Cluster cluster, Map<Long, byte[]> oracle,
	                                 ConcurrentLinkedDeque<Long> dropped, AtomicLong provides)
			throws Exception{
		var id     = provides.getAndIncrement() + 1000;
		var size   = 64 + (int)(id % 16) * 128; //64B .. 2KB payloads
		var payload = makePayload(id, size);
		cluster.roots().provide(id, payload); //allocates + writes at the tail, extends the file
		oracle.put(id, payload.data());
		dropped.push(id);
	}
	
	/**One free step: drop the newest root (free() -> popFile + unlocked tryPopFree, the truncating side).*/
	private static void freerStep(Cluster cluster, Map<Long, byte[]> oracle,
	                              ConcurrentLinkedDeque<Long> dropped, AtomicLong drops)
			throws Exception{
		var id = dropped.pollFirst();
		if(id == null) return; //nothing to free yet
		drops.incrementAndGet();
		if(oracle.remove(id) != null){
			cluster.roots().drop(ObjectID.of(id));
		}
	}
}
