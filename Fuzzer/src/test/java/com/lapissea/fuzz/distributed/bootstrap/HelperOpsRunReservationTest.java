package com.lapissea.fuzz.distributed.bootstrap;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

/**
 * Regression for the sweep window in {@link HelperOps#run}: a session registered there but not yet
 * leased (its helper main is still starting) must not be evicted by a concurrent sweep. The
 * {@code .preparing} reservation is the protection, so {@code run} must leave it for
 * {@link RemoteWorkspace#open} to delete once the lease is held.
 */
public class HelperOpsRunReservationTest{
	
	public static class PausingHelper{
		static final CountDownLatch entered = new CountDownLatch(1);
		static final CountDownLatch release = new CountDownLatch(1);
		public static void main(String[] args) throws InterruptedException{
			entered.countDown();
			release.await();
		}
	}
	
	@Test(timeOut = 30000)
	public void registeredWorkspaceIsProtectedUntilItsLeaseIsTaken() throws Exception{
		var cache   = Files.createTempDirectory("helper-run-cache-");
		var parent  = Files.createDirectory(cache.resolve("runs"));
		var session = UUID.randomUUID();
		var working = parent.resolve(session.toString());
		// The reservation that HelperOps.prepare would have created for this session.
		Files.createDirectories(cache.resolve(".preparing"));
		Files.createFile(cache.resolve(".preparing").resolve(session.toString()));
		
		var error = new AtomicReference<Throwable>();
		var thread = new Thread(() -> {
			try{
				HelperOps.run(new HelperProtocol.Run(
					cache.toString(), parent.toString(), working.toString(), PausingHelper.class.getName(),
					List.of("127.0.0.1", HelperOps.PORT_SENTINEL, session.toString(), "name", "1", "bundle"),
					7272, List.of()));
			}catch(Throwable e){
				error.set(e);
			}
		});
		thread.start();
		
		try{
			assertTrue(PausingHelper.entered.await(10, TimeUnit.SECONDS), "Helper main did not start");
			
			// Four newer inactive managed sessions: the target is the oldest and would be evicted unless
			// its reservation keeps it protected.
			for(int i = 0; i<4; i++){
				var sibling = Files.createDirectory(parent.resolve(UUID.randomUUID().toString()));
				Files.writeString(sibling.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			}
			
			RemoteWorkspace.cleanup(cache, parent);
			assertTrue(Files.exists(working.resolve(".fuzzer-managed")),
			           "A registered-but-not-yet-leased workspace must survive a sweep");
		}finally{
			PausingHelper.release.countDown();
			thread.join(10000);
			try(var paths = Files.walk(cache)){
				for(var p : paths.sorted(Comparator.reverseOrder()).toList()){
					try{ Files.deleteIfExists(p); }catch(Exception ignored){ }
				}
			}
		}
		assertNull(error.get());
	}
}
