package com.lapissea.fuzz.distributed.bootstrap;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

public class CacheGateTest{
	
	private static final long NO_WAIT = 0;
	private static final long WAIT    = TimeUnit.SECONDS.toMillis(5);
	
	@Test
	public void localGateExcludesConcurrentAcquirers() throws Exception{
		var cache = newCache();
		try{
			try(var gate = CacheGate.acquire(cache, false)){
				assertNotNull(gate);
				try(var other = CacheGate.acquire(cache, false)){
					assertNull(other, "Gate must not be granted while another holds it");
				}
			}
			try(var gate = CacheGate.acquire(cache, false)){
				assertNotNull(gate, "Gate must be granted again after release");
			}
		}finally{
			deleteRoot(cache);
		}
	}
	
	@Test
	public void fileLockGateExcludesConcurrentAcquirers() throws Exception{
		var cache = newCache();
		try{
			try(var gate = WindowsBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT)){
				assertNotNull(gate);
				assertNull(WindowsBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT), "Gate must not be granted while another holds it");
			}
			try(var gate = WindowsBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT)){
				assertNotNull(gate, "Gate must be granted again after release");
			}
		}finally{
			deleteRoot(cache);
		}
	}
	
	@Test
	public void fileLockGateTimesOutWhileHeld() throws Exception{
		var cache = newCache();
		try{
			try(var gate = WindowsBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT)){
				assertNotNull(gate);
				var started = System.nanoTime();
				assertThrows(IOException.class, () -> WindowsBootstrap.INSTANCE.acquireCacheGate(cache, 1500));
				assertTrue(System.nanoTime() - started>=TimeUnit.MILLISECONDS.toNanos(1000), "Timeout must be respected");
			}
		}finally{
			deleteRoot(cache);
		}
	}
	
	@Test
	public void fileLockGateWaitsForRelease() throws Exception{
		var cache = newCache();
		try{
			try(var gate = WindowsBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT)){
				assertNotNull(gate);
				var release = Thread.ofVirtual().start(() -> {
					try{
						Thread.sleep(300);
					}catch(InterruptedException ignored){
						Thread.currentThread().interrupt();
					}finally{
						try{ gate.close(); }catch(IOException ignored){ }
					}
				});
				var started = System.nanoTime();
				try(var acquired = WindowsBootstrap.INSTANCE.acquireCacheGate(cache, WAIT)){
					assertNotNull(acquired, "Gate must be granted once the holder releases");
					assertTrue(System.nanoTime() - started>=TimeUnit.MILLISECONDS.toNanos(200), "Acquire must wait for the release");
				}
				release.join(5000);
			}
		}finally{
			deleteRoot(cache);
		}
	}
	
	@Test
	public void fileLockGateExcludesOtherJvmAndRecoversAfterKill() throws Exception{
		var cache = newCache();
		Process child = null;
		try{
			String cp = Path.of(CacheGateTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + File.pathSeparator +
			            Path.of(CacheGate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			child = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", cp, GateHolder.class.getName(), cache.toString()
			).redirectError(ProcessBuilder.Redirect.INHERIT)
			 .start();
			
			try(var output = new BufferedReader(new InputStreamReader(child.getInputStream()))){
				assertEquals(output.readLine(), "ready");
			}
			
			assertNull(WindowsBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT), "Gate must not be granted while another JVM holds it");
			child.destroyForcibly();
			assertTrue(child.waitFor(10, TimeUnit.SECONDS));
			try(var gate = WindowsBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT)){
				assertNotNull(gate, "Gate must be granted after the holder JVM dies");
			}
		}finally{
			if(child != null && child.isAlive()){
				child.destroyForcibly();
			}
			deleteRoot(cache);
		}
	}
	
	@Test
	public void flockGateExcludesConcurrentAcquirers() throws Exception{
		if(HostOs.local() != HostOs.LINUX){
			throw new SkipException("flock is only available on Linux");
		}
		var cache = newCache();
		try{
			try(var gate = PosixBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT)){
				assertNotNull(gate);
				assertNull(PosixBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT), "Gate must not be granted while another holds it");
			}
			try(var gate = PosixBootstrap.INSTANCE.acquireCacheGate(cache, NO_WAIT)){
				assertNotNull(gate, "Gate must be granted again after release");
			}
		}finally{
			deleteRoot(cache);
		}
	}
	
	private static Path newCache() throws IOException{
		return Files.createDirectories(Files.createTempDirectory("fuzz-gate-").resolve("cache"));
	}
	
	public static final class GateHolder{
		public static void main(String[] args) throws Exception{
			try(var gate = WindowsBootstrap.INSTANCE.acquireCacheGate(Path.of(args[0]), NO_WAIT)){
				if(gate == null){
					System.exit(1);
				}
				System.out.println("ready");
				System.out.flush();
				Thread.sleep(Long.MAX_VALUE);
			}
		}
	}
	
	private static void deleteRoot(Path cache) throws IOException{
		try(var paths = Files.walk(cache.getParent())){
			for(var p : paths.sorted(Comparator.reverseOrder()).toList()){
				Files.deleteIfExists(p);
			}
		}
	}
}
