package com.lapissea.fuzz.distributed.bootstrap;

import com.lapissea.fuzz.distributed.FileCleanup;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

public class PosixBootstrapTest{
	
	@BeforeClass
	public void requireLinux(){ SshWorkerLauncherTest.requireLinuxHelperTools(); }
	
	@Test(timeOut = 15000)
	public void preparationResolvesQuotedPathsAndReservesCachedArtifacts() throws Exception{
		var root = Files.createTempDirectory("fuzz scripts ' $()-");
		try{
			var cache = Files.createDirectory(root.resolve("cache"));
			var alias = Files.createSymbolicLink(root.resolve("cache alias"), cache);
			var parent = root.resolve("work ' directory");
			var hash = "a".repeat(64);
			var directory = Files.createDirectory(cache.resolve(hash));
			Files.writeString(directory.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			var cached = Files.writeString(directory.resolve("cached '.jar"), "keep");
			Files.setLastModifiedTime(cached, FileTime.fromMillis(0));
			var artifactNames = List.of(hash + "/cached '.jar", "b".repeat(64) + "/missing.jar");
			var session = UUID.randomUUID();
			var output = run(root, PosixBootstrap.INSTANCE.prepare(alias.toString(), parent.toString(), session, artifactNames).command());
			
			assertEquals(output.lines().toList(), List.of(cache.toRealPath().toString(), parent.toRealPath().toString(), "1"));
			assertTrue(Files.exists(cache.resolve(".preparing/" + session)));
			RemoteWorkspace.cleanup(cache, parent);
			assertEquals(Files.readString(cached), "keep");
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test(timeOut = 15000)
	public void failedRemovesOnlyItsOwnReservationAndUpload() throws Exception{
		var root = Files.createTempDirectory("fuzz cleanup ' $()-");
		try{
			var cache = Files.createDirectory(root.resolve("cache"));
			Files.createDirectory(cache.resolve(".preparing"));
			var session = UUID.randomUUID();
			var other = UUID.randomUUID();
			for(var id : List.of(session, other)){
				Files.writeString(cache.resolve(".preparing/" + id), "");
				Files.createDirectory(cache.resolve(".upload-" + id));
				Files.writeString(cache.resolve(".upload-" + id + "/partial"), "partial");
			}
			var sentinel = Files.writeString(cache.resolve("user-data"), "keep");
			run(root, PosixBootstrap.INSTANCE.failed(cache.toString(), session).command());
			
			assertFalse(Files.exists(cache.resolve(".preparing/" + session)));
			assertFalse(Files.exists(cache.resolve(".upload-" + session)));
			assertTrue(Files.exists(cache.resolve(".preparing/" + other)));
			assertEquals(Files.readString(cache.resolve(".upload-" + other + "/partial")), "partial");
			assertEquals(Files.readString(sentinel), "keep");
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	private static String run(Path root, String script) throws Exception{
		var output = Files.createTempFile(root, "shell-output-", ".txt");
		var process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).redirectOutput(output.toFile()).start();
		
		try{
			process.getOutputStream().close();
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Shell operation timed out");
			assertEquals(process.exitValue(), 0, Files.readString(output));
			return Files.readString(output);
		}finally{
			if(process.isAlive()){
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
	}
}
