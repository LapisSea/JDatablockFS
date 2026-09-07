package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.exceptions.FreeWhileUsed;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.type.IOInstance;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-40 memmgr-xthread-free.
 *
 * {@code PersistentMemoryManager.free(Collection<Chunk>)} is supposed to refuse to free a
 * chunk that is currently in use, raising {@link FreeWhileUsed}. But the guard
 * (jdbfs-core/.../core/memory/PersistentMemoryManager.java:261-275) only walks the
 * CURRENT thread's open-chain stack via {@code getStack()} (line 262). Chains opened on
 * OTHER threads are tracked in {@code allStacks} (line 101) yet are ignored by the guard.
 *
 * Consequence: a chunk held open by an {@code ChunkChainIO} on thread A can be freed from
 * thread B without any {@code FreeWhileUsed}. Because the freed chunk sits at the end of
 * the file, {@code popFile} truncates it away and the space is immediately reused by the
 * next allocation, so thread A's still-open chain silently reads whatever was written over
 * it — a use-after-free that the in-use guard was supposed to catch.
 *
 * Both tests below FAIL while the bug is present and PASS once the guard inspects the
 * stacks of all live threads ({@code allStacks}) instead of only the current thread.
 */
public class ReproMemMgrXThreadFreeTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	private static final int SIZE = 64;
	
	private static byte[] pattern(byte fill){
		byte[] data = new byte[SIZE];
		Arrays.fill(data, fill);
		return data;
	}
	
	/**
	 * Thread A opens a {@link ChunkChainIO} on X and holds it. Thread B (a different
	 * thread) frees X. The guard only inspects thread B's own (empty) stack, so no
	 * {@link FreeWhileUsed} is raised even though X is locked open on thread A. The test
	 * asserts the guard DID reject the free, so it fails while the cross-thread miss is
	 * present and passes once the guard walks every live thread's stack.
	 */
	@Test(timeOut = 120_000)
	void crossThreadFreeIsNotBlockedByFreeWhileUsedGuard() throws Exception{
		var cluster = Cluster.emptyMem();
		byte[] dataA = pattern((byte)0x41);
		
		var  x    = cluster.roots().request("victim", Blob.class);
		x.write(true, dataA);
		var head = x.getPointer().dereference(cluster);
		
		// Thread A: open the chain on X and keep it held open for the whole test.
		var aHolding = new CountDownLatch(1);
		var aRelease = new CountDownLatch(1);
		var aError   = new AtomicReference<Throwable>();
		var holder   = new Thread(() -> {
			try{
				// notifyStart() registers this chain on thread A's stack (the stack the
				// guard should be looking at).
				new ChunkChainIO(head);
				aHolding.countDown();   // signal: chain is open and held
				aRelease.await();       // hold until the test releases us
				// Intentionally left open: thread A still "uses" X.
			}catch(Throwable t){
				aError.set(t);
			}
		}, "holder-A");
		holder.setDaemon(true);
		holder.start();
		
		assertThat(aHolding.await(30, TimeUnit.SECONDS))
		               .as("holder thread opened the chain on X")
		               .isTrue();
		
		try{
			// Thread B: free X. X is in use on thread A, so the guard must throw
			// FreeWhileUsed. It only walks thread B's stack, so it does not.
			var bError = new AtomicReference<Throwable>();
			var freer  = new Thread(() -> {
				try{
					x.free(); // PersistentMemoryManager.free({X}) executed on thread B
				}catch(Throwable t){
					bError.set(t);
				}
			}, "freer-B");
			freer.start();
			freer.join(60_000);
			
			// The guard must have rejected the free: X is open on another thread.
			assertThat(bError.get())
			               .as("free() of a chunk held open on another thread must be rejected with FreeWhileUsed")
			               .isInstanceOf(FreeWhileUsed.class); // fails while the bug is present (bError is null)
		}finally{
			aRelease.countDown();
		}
	}
	
	/**
	 * Shows the consequence of the guard miss. Thread A holds X open; thread B frees X
	 * (no {@link FreeWhileUsed}); the freed tail chunk is truncated and its space reused
	 * by a new blob. The data region that thread A's still-open chain points to now holds
	 * the new blob's bytes — a silent use-after-free. The test asserts the region is still
	 * the original data, so it fails while the bug is present.
	 */
	@Test(timeOut = 120_000)
	void crossThreadFreeThenReallocOverwritesOpenChainData() throws Exception{
		var cluster    = Cluster.emptyMem();
		byte[] dataA   = pattern((byte)0x41);
		
		var x          = cluster.roots().request("victim", Blob.class);
		x.write(true, dataA);
		var head       = x.getPointer().dereference(cluster);
		var xDataStart = head.dataStart();
		
		// Thread A: open the chain on X and keep it held open.
		var aHolding = new CountDownLatch(1);
		var aRelease = new CountDownLatch(1);
		var aError   = new AtomicReference<Throwable>();
		var holder   = new Thread(() -> {
			try{
				new ChunkChainIO(head); // A holds X open
				aHolding.countDown();
				aRelease.await();
			}catch(Throwable t){
				aError.set(t);
			}
		}, "holder-A");
		holder.setDaemon(true);
		holder.start();
		
		assertThat(aHolding.await(30, TimeUnit.SECONDS))
		               .as("holder thread opened the chain on X")
		               .isTrue();
		
		try{
			// Thread B frees X. Whether or not the guard blocks it, we record the outcome
			// and continue: the point is what happens to the region A's chain points at.
			var bError = new AtomicReference<Throwable>();
			var freer  = new Thread(() -> {
				try{
					x.free();
				}catch(Throwable t){
					bError.set(t);
				}
			}, "freer-B");
			freer.start();
			freer.join(60_000);
			
			// Reallocate over the (possibly) freed region. X was the tail chunk, so when the
			// free is not blocked it is truncated and this new blob is appended at X's old
			// position, overwriting the bytes A's open chain still references.
			byte[] dataB = pattern((byte)0x42);
			var    y     = cluster.roots().request("reuser", Blob.class);
			y.write(true, dataB);
			
			// Read the raw bytes where X's data used to be (where A's open chain points).
			byte[] raw = cluster.getSource().ioMapAt(xDataStart, io -> {
				byte[] buf = new byte[SIZE];
				io.readFully(buf);
				return buf;
			});
			
			// A's still-open chain expects the original data; the region now holds the
			// reuser's bytes (use-after-free). Fails while the bug is present.
			assertThat(raw)
			               .as("data region of the still-open chain must not have been reallocated over")
			               .containsExactly(dataA);
		}finally{
			aRelease.countDown();
		}
	}
}
