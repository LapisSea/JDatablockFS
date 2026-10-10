package com.lapissea.fuzz.distributed.bootstrap;

import com.lapissea.fuzz.distributed.master.RemoteConfig;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.*;

public class RuntimeBundleTest{
	
	@Test
	public void machinePathsAreNotForwardedToLinux(){
		for(var name : List.of("java.io.tmpdir", "java.library.path", "user.home", "user.dir", "fuzz.remote.config")){
			assertFalse(RuntimeBundle.forwardProperty("-D" + name + "=C:\\Users\\Fuzz User\\tmp"));
		}
		assertTrue(RuntimeBundle.forwardProperty("-Ddfs.debug=true"));
		assertTrue(RuntimeBundle.forwardProperty("-Dfuzz.iterationMultiplier=0.2"));
	}
	
	@Test
	public void snapshotsAreImmutableAndContentAddressed() throws Exception{
		var dir  = Files.createTempDirectory("runtime-bundle-test-");
		var file = dir.resolve("resource.txt");
		var old  = System.getProperty("surefire.test.class.path");
		try{
			Files.writeString(file, "before");
			System.setProperty("surefire.test.class.path", dir.toString());
			try(var first = RuntimeBundle.capture(); var same = RuntimeBundle.capture()){
				assertEquals(first.id(), same.id());
				Files.writeString(file, "after");
				try(var changed = RuntimeBundle.capture()){
					assertNotEquals(first.id(), changed.id());
				}
				try(var jar = new JarFile(first.artifacts().getFirst().file().toFile())){
					assertEquals(
						new String(jar.getInputStream(jar.getJarEntry("resource.txt")).readAllBytes(), UTF_8),
						"before");
				}
			}
		}finally{
			if(old == null){
				System.clearProperty("surefire.test.class.path");
			}else{
				System.setProperty("surefire.test.class.path", old);
			}
			Files.deleteIfExists(file);
			Files.deleteIfExists(dir);
		}
	}
	
	@Test
	public void parallelCaptureOfIdenticalArtifactsSucceeds() throws Exception{
		var root    = Files.createTempDirectory("runtime-dup-");
		var content = new byte[16<<20];
		new Random(1).nextBytes(content);
		var a = Files.createDirectory(root.resolve("a")).resolve("same.jar");
		var b = Files.createDirectory(root.resolve("b")).resolve("same.jar");
		Files.write(a, content);
		Files.write(b, content);
		
		var old = System.getProperty("surefire.test.class.path");
		try{
			System.setProperty("surefire.test.class.path", a + File.pathSeparator + b);
			
			try(var bundle = RuntimeBundle.capture()){
				assertFalse(bundle.artifacts().isEmpty());
			}
		}finally{
			if(old == null){
				System.clearProperty("surefire.test.class.path");
			}else{
				System.setProperty("surefire.test.class.path", old);
			}
			try(var paths = Files.walk(root)){
				for(var p : paths.sorted(Comparator.reverseOrder()).toList()){
					try{ Files.deleteIfExists(p); }catch(IOException ignored){ }
				}
			}
		}
	}
	
	@Test
	public void jsonConfigurationLoadsDefaultsAndRejectsCollisions() throws Exception{
		var config = Files.createTempFile("fuzz-config-", ".json");
		var old    = System.getProperty("fuzz.remote.config");
		try{
			System.setProperty("fuzz.remote.config", config.toString());
			Files.writeString(config, "{\"hosts\":[{\"sshHost\":\"pc\",\"workDirectory\":\"/tmp/fuzz work\"}]}");
			
			var host = RemoteConfig.load().config().hosts().getFirst();
			assertEquals(host.parallelism(), 0);
			assertEquals(host.workDirectory(), "/tmp/fuzz work");
			Files.writeString(config, "{\"hosts\":[{\"sshHost\":\"pc\",\"name\":\"local\"}]}");
			
			assertThrows(IOException.class, RemoteConfig::load);
			Files.writeString(config, "{\"hosts\":[{\"sshHost\":\"pc\"},{\"sshHost\":\"pc\"}]}");
			
			assertThrows(IOException.class, RemoteConfig::load);
			Files.writeString(config, "invalid");
			
			assertThrows(IOException.class, RemoteConfig::load);
			Files.delete(config);
			assertTrue(RemoteConfig.load().config().hosts().isEmpty());
		}finally{
			if(old == null){
				System.clearProperty("fuzz.remote.config");
			}else{
				System.setProperty("fuzz.remote.config", old);
			}
			Files.deleteIfExists(config);
		}
	}
	
	@Test
	public void reconstructedJvmLoadsBundledTestClasses() throws Exception{
		SshWorkerLauncherTest.requireLinuxHelperTools();
		try(var bundle = RuntimeBundle.capture()){
			var cache = Files.createTempDirectory("bundle-launch-");
			try{
				for(var artifact : bundle.artifacts()){
					var destination = cache.resolve(artifact.name());
					Files.createDirectories(destination.getParent());
					Files.copy(artifact.file(), destination);
				}
				
				var host = new RemoteConfig.Host(null, "localhost", Path.of(System.getProperty("java.home"), "bin/java").toString(),
				                                 null, 0, 0, 0, null, null);
				// The "remote" machine here is this one, so join the classpath with this machine's rules.
				var localOs = File.separatorChar == '\\'? HostOs.WINDOWS : HostOs.LINUX;
				var output  = Files.createTempFile("bundle-launch-output-", ".txt");
				try{
					var process = new ProcessBuilder(SshWorkerLauncher.javaInvocation(host, localOs, bundle, cache.toString(), Probe.class.getName(),
					                                                                  List.of())).redirectErrorStream(true)
					                                                                             .redirectOutput(output.toFile()).start();
					if(!process.waitFor(30, TimeUnit.SECONDS)){
						process.destroyForcibly();
						fail("Bundled JVM timed out");
					}
					assertEquals(process.exitValue(), 0, Files.readString(output));
					assertTrue(Files.readString(output).contains("BUNDLE_PROBE_OK"));
				}finally{ Files.deleteIfExists(output); }
			}finally{
				try(var paths = Files.walk(cache)){
					for(var p : paths.sorted(Comparator.reverseOrder()).toList()){ Files.deleteIfExists(p); }
				}
			}
		}
	}
	
	public static class Probe{
		public static void main(String[] args) throws Exception{
			Class.forName("com.lapissea.fuzz.FuzzSequence");
			Class.forName("com.google.gson.Gson");
			Class.forName("org.testng.Assert");
			System.out.println("BUNDLE_PROBE_OK");
		}
	}
	
	@Test
	public void configDefaultsAndShellQuoting(){
		var host = new RemoteConfig.Host(null, "user@workstation", null, null, 0, 0, 0, null, null);
		
		assertEquals(host.parallelism(), 0);
		assertEquals(host.javaExecutable(), "java");
		assertTrue(host.declaredOs().isEmpty(), "an unset os means detect it, not assume linux");
		assertTrue(new RemoteConfig.Host(null, "user@workstation", null, null, 0, 0, 0, null, "  ").declaredOs().isEmpty());
		assertEquals(new RemoteConfig.Host(null, "user@workstation", null, null, 0, 0, 0, null, "windows").declaredOs(), Optional.of(HostOs.WINDOWS));
		assertThrows(IllegalArgumentException.class, () -> new RemoteConfig.Host(null, "user@workstation", null, null, 0, 0, 0, null, "plan9"));
		assertEquals(PosixBootstrap.quote("a'b"), "'a'\\''b'");
		assertThrows(IllegalArgumentException.class, () -> new RemoteConfig.Host(null, "-oProxyCommand=bad", null, null, 0, 0, 0, null, null));
	}
}
