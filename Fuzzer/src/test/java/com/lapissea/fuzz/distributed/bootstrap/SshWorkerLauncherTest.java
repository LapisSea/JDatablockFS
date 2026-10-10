package com.lapissea.fuzz.distributed.bootstrap;

import com.lapissea.fuzz.distributed.master.RemoteConfig;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

public class SshWorkerLauncherTest{
	
	static void requireLinuxHelperTools(){
		if(!System.getProperty("os.name").equals("Linux")){
			throw new SkipException("Exercises Linux helper shell tools locally");
		}
	}
	
	@Test
	public void helperTunnelNeedsNoLocalShellOrControlSocket(){
		for(int port : new int[]{0, 39173}){
			var    host    = new RemoteConfig.Host(null, "user@helper", null, null, 0, 0, port, null, null);
			String remote  = "printf '%s' 'remote path with spaces'";
			var    command = SshWorkerLauncher.helperCommand(host, 12345, remote);
			
			assertEquals(command.getFirst(), "ssh");
			assertEquals(command.getLast(), remote);
			assertEquals(command.get(command.indexOf("-R") + 1), "127.0.0.1:" + port + ":127.0.0.1:12345");
			assertTrue(command.contains("ExitOnForwardFailure=yes"));
			assertTrue(command.contains("ControlPath=none"));
			assertFalse(command.contains("-M"));
			assertFalse(command.contains("-N"), "Tunnel must end with the remote command");
		}
	}
	
	@Test
	public void allocatedPortsAreValidated(){
		assertEquals(SshWorkerLauncher.allocatedPort("Allocated port 43210 for remote forward to 127.0.0.1:12345").orElseThrow(), 43210);
		for(var line : List.of("banner", "Allocated port 0 for remote forward to x", "Allocated port 65536 for remote forward to x",
		                       "Allocated port 999999999999999999999 for remote forward to x")){
			assertTrue(SshWorkerLauncher.allocatedPort(line).isEmpty());
		}
	}
	
	@Test
	public void dynamicPortIsDefaultAndLegacyExplicitPortStillWorks(){
		assertEquals(new RemoteConfig.Host(null, "localhost", null, null, 0, 0, 0, null, null).tunnelPort(), 0);
		assertEquals(new RemoteConfig.Host(null, "localhost", null, null, 0, 0, 39173, null, null).tunnelPort(), 39173);
		assertThrows(IllegalArgumentException.class, () -> new RemoteConfig.Host(null, "localhost", null, null, 0, 0, -1, null, null));
	}
	
	@Test
	public void hostOsPathRules(){
		assertEquals(HostOs.LINUX.pathSeparator(), ":");
		assertEquals(HostOs.WINDOWS.pathSeparator(), ";");
		assertThrows(IllegalArgumentException.class, () -> HostOs.parse(null));
		assertThrows(IllegalArgumentException.class, () -> HostOs.parse(" "));
		assertEquals(HostOs.parse("Windows"), HostOs.WINDOWS);
		assertThrows(IllegalArgumentException.class, () -> HostOs.parse("plan9"));
		assertEquals(HostOs.WINDOWS.join("C:\\fuzz", "abc/def.jar"), "C:\\fuzz\\abc/def.jar");
		assertTrue(HostOs.WINDOWS.isAbsolute("C:\\work"));
		assertTrue(HostOs.WINDOWS.isAbsolute("C:/work"));
		assertTrue(HostOs.WINDOWS.isAbsolute("\\\\server\\share"));
		assertFalse(HostOs.WINDOWS.isAbsolute("relative\\path"));
		assertFalse(HostOs.WINDOWS.isAbsolute("/home/x"));
		assertTrue(HostOs.LINUX.isAbsolute("/home/x"));
		assertFalse(HostOs.LINUX.isAbsolute("C:\\x"));
		assertEquals(HostOs.LINUX.other(), HostOs.WINDOWS);
		assertEquals(HostOs.WINDOWS.other(), HostOs.LINUX);
	}
	
	@Test
	public void aPathFromTheWrongOsSaysWhyTheHelperWillNotConnect() throws IOException{
		var host = new RemoteConfig.Host(null, "user@helper", null, null, 0, 0, 0, null, "linux");
		SshWorkerLauncher.requireRemotePath(host, HostOs.LINUX, "/home/me/.cache/jdatablockfs/fuzzer", "cache directory");
		
		var mismatch = rejection(host, HostOs.LINUX, "C:\\Users\\me\\.cache\\jdatablockfs\\fuzzer");
		assertTrue(mismatch.contains("will not connect"), mismatch);
		assertTrue(mismatch.contains("is an absolute windows path"), mismatch);
		assertTrue(mismatch.contains("\"os\": \"windows\""), mismatch);
		
		var reverse = rejection(host, HostOs.WINDOWS, "/home/me/.cache/jdatablockfs/fuzzer");
		assertTrue(reverse.contains("\"os\": \"linux\""), reverse);
		
		var neither = rejection(host, HostOs.LINUX, "relative/cache");
		assertTrue(neither.contains("not an absolute linux path"), neither);
		assertTrue(neither.contains("cacheDirectory"), neither);
		assertFalse(neither.contains("\"os\":"), "must not blame the os setting when the path is simply relative: " + neither);
	}
	
	private static String rejection(RemoteConfig.Host host, HostOs os, String path){
		try{
			SshWorkerLauncher.requireRemotePath(host, os, path, "cache directory");
			fail("Expected " + path + " to be rejected as a " + os + " path");
			return null;
		}catch(IOException e){ return e.getMessage(); }
	}
	
	@Test(timeOut = 60000)
	public void javaInvocationJoinsRemotePathsPerHostOs() throws Exception{
		try(var bundle = RuntimeBundle.capture()){
			var linux   = new RemoteConfig.Host(null, "user@helper", null, null, 0, 0, 0, null, null);
			var windows = new RemoteConfig.Host(null, "user@helper", null, null, 0, 0, 0, null, "windows");
			var linuxCp = SshWorkerLauncher.javaInvocation(linux, HostOs.LINUX, bundle, "/cache", "com.example.Helper", List.of());
			var linuxEntry = linuxCp.get(linuxCp.indexOf("-cp") + 1);
			assertFalse(linuxEntry.contains(";"), linuxEntry);
			for(var part : linuxEntry.split(":")){
				assertTrue(part.startsWith("/cache/"), part);
			}
			
			var windowsCp = SshWorkerLauncher.javaInvocation(windows, HostOs.WINDOWS, bundle, "C:\\cache", "com.example.Helper", List.of());
			var entry     = windowsCp.get(windowsCp.indexOf("-cp") + 1);
			assertTrue(entry.contains(";"), entry);
			for(var part : entry.split(";")){
				assertTrue(part.startsWith("C:\\cache\\"), part);
			}
		}
	}
	
	@Test(timeOut = 15000)
	public void publishedArtifactsReceiveGracePeriod() throws Exception{
		requireLinuxHelperTools();
		var root = Files.createTempDirectory("fuzz-publish-test-");
		try{
			var cache     = Files.createDirectory(root.resolve("cache"));
			var source    = Files.writeString(root.resolve("input.jar"), "artifact");
			var name      = "a".repeat(64) + "/dependency.jar";
			var artifacts = List.of(new RuntimeBundle.Artifact(name, source));
			var process = new ProcessBuilder("sh", "-c", PosixBootstrap.INSTANCE.upload(cache.toString(), UUID.randomUUID(), List.of(name)).command())
				              .redirectError(ProcessBuilder.Redirect.INHERIT).start();
			
			try{
				try(var input = process.getOutputStream()){ TarIO.write(input, artifacts); }
				assertTrue(process.waitFor(10, TimeUnit.SECONDS));
				assertEquals(process.exitValue(), 0);
			}finally{ process.destroyForcibly(); }
			var published = cache.resolve(name);
			RemoteWorkspace.cleanup(cache, root.resolve("runs"));
			assertEquals(Files.readString(published), "artifact");
			Files.setLastModifiedTime(published, FileTime.fromMillis(System.currentTimeMillis() - 7_200_000));
			RemoteWorkspace.cleanup(cache, root.resolve("runs"));
			assertFalse(Files.exists(published));
		}finally{
			try(var paths = Files.walk(root)){
				for(var path : paths.sorted(Comparator.reverseOrder()).toList()){ Files.deleteIfExists(path); }
			}
		}
	}
	
	@Test(timeOut = 15000)
	public void preparationAndCleanupShareTheSameLock() throws Exception{
		requireLinuxHelperTools();
		var root = Files.createTempDirectory("fuzz-cache-gate-");
		try{
			var cache = Files.createDirectory(root.resolve("cache"));
			var dir   = Files.createDirectory(cache.resolve("b".repeat(64)));
			Files.writeString(dir.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			var artifact = Files.writeString(dir.resolve("old.jar"), "cached");
			Files.setLastModifiedTime(artifact, FileTime.fromMillis(0));
			var shell = new ProcessBuilder("sh", "-c", PosixBootstrap.cacheLock(cache.toString()) + "printf x; exec cat >/dev/null")
				            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
			try{
				assertEquals(shell.getInputStream().read(), (int)'x');
				RemoteWorkspace.cleanup(cache, root.resolve("runs"));
				assertTrue(Files.exists(artifact), "Cleanup must not inspect a cache while preparation holds its gate");
			}finally{
				shell.getOutputStream().close();
				if(!shell.waitFor(5, TimeUnit.SECONDS)){ shell.destroyForcibly(); }
			}
			try(var gate = CacheGate.acquire(cache, true)){
				var probe = new ProcessBuilder("flock", "-n", cache.resolve(".cleanup-lock").toString(), "true").start();
				assertTrue(probe.waitFor(5, TimeUnit.SECONDS));
				assertEquals(probe.exitValue(), 1, "Shell preparation must contend with the Java cleanup gate");
				// Register preparation before unlocking, as the launcher does before probing.
				Files.createDirectory(cache.resolve(".preparing"));
				Files.writeString(cache.resolve(".preparing/session"), "");
			}
			RemoteWorkspace.cleanup(cache, root.resolve("runs"));
			assertTrue(Files.exists(artifact), "Reservation must protect the gap between probe and publication");
			Files.delete(cache.resolve(".preparing/session"));
			RemoteWorkspace.cleanup(cache, root.resolve("runs"));
			assertFalse(Files.exists(artifact));
		}finally{
			try(var paths = Files.walk(root)){
				for(var path : paths.sorted(Comparator.reverseOrder()).toList()){ Files.deleteIfExists(path); }
			}
		}
	}
	
	@Test(timeOut = 15000)
	public void unownedArtifactDirectoriesAreNeverAdopted() throws Exception{
		requireLinuxHelperTools();
		var root = Files.createTempDirectory("fuzz-unowned-cache-");
		try{
			var source = Files.writeString(root.resolve("input.jar"), "new data");
			for(boolean incorrectMarker : new boolean[]{false, true}){
				var cache = Files.createDirectory(root.resolve("cache-" + incorrectMarker));
				var name  = "c".repeat(64) + "/dependency.jar";
				var dir   = Files.createDirectory(cache.resolve("c".repeat(64)));
				var file  = Files.writeString(cache.resolve(name), "user data");
				Files.setLastModifiedTime(file, FileTime.fromMillis(0));
				var marker = dir.resolve(".fuzzer-managed");
				if(incorrectMarker){ Files.writeString(marker, "belongs to user"); }
				var artifacts = List.of(new RuntimeBundle.Artifact(name, source));
				
				var probe = new ProcessBuilder("sh", "-c", "set -e; cache=" + PosixBootstrap.quote(cache.toString()) + "; " +
				                                           PosixBootstrap.cacheLock(cache.toString()) + PosixBootstrap.cacheProbe(List.of(name)))
					            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
				assertTrue(probe.waitFor(5, TimeUnit.SECONDS));
				assertNotEquals(probe.exitValue(), 0);
				var upload = new ProcessBuilder("sh", "-c", PosixBootstrap.INSTANCE.upload(cache.toString(), UUID.randomUUID(), List.of(name)).command())
					             .redirectError(ProcessBuilder.Redirect.DISCARD).start();
				try(var out = upload.getOutputStream()){ TarIO.write(out, artifacts); }
				assertTrue(upload.waitFor(5, TimeUnit.SECONDS));
				assertNotEquals(upload.exitValue(), 0);
				RemoteWorkspace.cleanup(cache, root.resolve("runs"));
				assertEquals(Files.readString(file), "user data");
				if(incorrectMarker){ assertEquals(Files.readString(marker), "belongs to user"); }else{ assertFalse(Files.exists(marker)); }
			}
		}finally{
			try(var paths = Files.walk(root)){
				for(var path : paths.sorted(Comparator.reverseOrder()).toList()){ Files.deleteIfExists(path); }
			}
		}
	}
	
	@Test
	public void tarTransferPreservesFilesNamesAndPadding() throws Exception{
		requireLinuxHelperTools();
		var dir = Files.createTempDirectory("fuzz-tar-test-");
		try{
			var shortFile = dir.resolve("source-a");
			var longFile  = dir.resolve("source-b");
			var bytes     = new byte[1027];
			new Random(17).nextBytes(bytes);
			Files.write(shortFile, bytes);
			Files.writeString(longFile, "second");
			var name    = "a".repeat(64) + "/a long dependency name with spaces.jar";
			var archive = dir.resolve("bundle.tar");
			try(var out = Files.newOutputStream(archive)){
				TarIO.write(out,
				                           List.of(new RuntimeBundle.Artifact("hash/a.jar", shortFile), new RuntimeBundle.Artifact(name, longFile)));
			}
			
			var destination = Files.createDirectory(dir.resolve("extracted"));
			var process = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", destination.toString()).redirectError(
				ProcessBuilder.Redirect.INHERIT).start();
			if(!process.waitFor(10, TimeUnit.SECONDS)){
				process.destroyForcibly();
				fail("Tar extraction timed out");
			}
			
			assertEquals(process.exitValue(), 0);
			assertEquals(Files.readAllBytes(destination.resolve("hash/a.jar")), bytes);
			assertEquals(Files.readString(destination.resolve(name)), "second");
		}finally{
			try(var paths = Files.walk(dir)){
				for(var path : paths.sorted(Comparator.reverseOrder()).toList()){ Files.deleteIfExists(path); }
			}
		}
	}
}
