package com.lapissea.fuzz.distributed.bootstrap;

import com.google.gson.Gson;
import com.lapissea.fuzz.distributed.FileCleanup;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.testng.Assert.*;

/**
 * Covers the {@link HelperOps} phases (install and run) against local directories only, with no ssh.
 */
public class HelperOpsTest{
	private static final Gson GSON = new Gson();
	
	@Test
	public void prepareReservesSessionAndProbesArtifacts() throws Exception{
		var root = Files.createTempDirectory("helper-ops-prepare-");
		try{
			var cache     = Files.createDirectory(root.resolve("cache"));
			var alias     = Files.createSymbolicLink(root.resolve("cache alias"), cache);
			var parent    = root.resolve("work ' directory");
			var hash      = "a".repeat(64);
			var directory = Files.createDirectory(cache.resolve(hash));
			Files.writeString(directory.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			var cached = Files.writeString(directory.resolve("cached '.jar"), "keep");
			Files.setLastModifiedTime(cached, FileTime.fromMillis(0));
			var artifacts = List.of(hash + "/cached '.jar", "b".repeat(64) + "/missing.jar");
			var session   = UUID.randomUUID();
			
			var probe = HelperOps.prepare(new HelperProtocol.Install(alias.toString(), parent.toString(), session, artifacts));
			
			assertEquals(probe.cache(), cache.toRealPath().toString());
			assertEquals(probe.parent(), parent.toRealPath().toString());
			assertEquals(probe.missing(), List.of(1));
			assertTrue(Files.exists(cache.resolve(".preparing/" + session)));
			assertTrue(Files.isDirectory(parent));
			assertEquals(Files.getLastModifiedTime(cached).toMillis(), 0);
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void prepareRejectsUnmanagedArtifactDirectory() throws Exception{
		var root = Files.createTempDirectory("helper-ops-unmanaged-");
		try{
			var cache     = Files.createDirectory(root.resolve("cache"));
			var directory = Files.createDirectory(cache.resolve("c".repeat(64)));
			var file      = Files.writeString(directory.resolve("user-file.jar"), "user");
			assertThrows(IOException.class, () -> {
				             HelperOps.prepare(new HelperProtocol.Install(
						             cache.toString(), root.resolve("runs").toString(), UUID.randomUUID(),
						             List.of("c".repeat(64) + "/dependency.jar")));
			             }
			);
			assertEquals(Files.readString(file), "user");
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void uploadPublishesWithFreshTimestampsAndMarkers() throws Exception{
		var root = Files.createTempDirectory("helper-ops-upload-");
		try{
			var cache   = Files.createDirectory(root.resolve("cache"));
			var source  = Files.writeString(root.resolve("input.jar"), "artifact");
			var name    = sha256("artifact") + "/dependency jar.jar";
			var archive = new ByteArrayOutputStream();
			TarIO.write(archive, List.of(new RuntimeBundle.Artifact(name, source)));
			
			var extracted = HelperOps.extractArtifacts(cache, UUID.randomUUID(), new ByteArrayInputStream(archive.toByteArray()));
			
			assertEquals(extracted, List.of(name));
			var published = cache.resolve(name);
			assertTrue(Files.isRegularFile(published));
			assertEquals(Files.readString(published), "artifact");
			assertEquals(Files.readString(cache.resolve(name).getParent().resolve(".fuzzer-managed")), RemoteWorkspace.MARKER);
			assertTrue(Files.getLastModifiedTime(published).toMillis()>System.currentTimeMillis() - 60_000);
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void uploadRejectsUnmanagedDirectoryAndRemovesStaging() throws Exception{
		var root = Files.createTempDirectory("helper-ops-upload-reject-");
		try{
			var cache = Files.createDirectory(root.resolve("cache"));
			var name  = sha256("new data") + "/dependency.jar";
			Files.createDirectory(cache.resolve(name).getParent());
			var file    = Files.writeString(cache.resolve(name), "user data");
			var source  = Files.writeString(root.resolve("input.jar"), "new data");
			var archive = new ByteArrayOutputStream();
			TarIO.write(archive, List.of(new RuntimeBundle.Artifact(name, source)));
			
			var session = UUID.randomUUID();
			
			assertThrows(IOException.class, () -> HelperOps.extractArtifacts(cache, session, new ByteArrayInputStream(archive.toByteArray())));
			assertEquals(Files.readString(file), "user data");
			assertFalse(Files.exists(cache.resolve(".upload-" + session)));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void tarReaderRoundTripsLongNamesAndPadding() throws Exception{
		var root = Files.createTempDirectory("helper-ops-tar-");
		try{
			var bytes = new byte[1027];
			new Random(17).nextBytes(bytes);
			var shortFile = Files.write(root.resolve("source-a"), bytes);
			var longFile  = Files.writeString(root.resolve("source-b"), "second");
			var longName  = "a".repeat(64) + "/a long dependency name with spaces.jar";
			var archive   = new ByteArrayOutputStream();
			TarIO.write(archive, List.of(new RuntimeBundle.Artifact("hash/a.jar", shortFile),
			                             new RuntimeBundle.Artifact(longName, longFile)));
			
			var destination = Files.createDirectory(root.resolve("extracted"));
			var names       = TarIO.extract(new ByteArrayInputStream(archive.toByteArray()), destination);
			assertEquals(names, List.of("hash/a.jar", longName));
			assertEquals(Files.readAllBytes(destination.resolve("hash/a.jar")), bytes);
			assertEquals(Files.readString(destination.resolve(longName)), "second");
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void uploadRejectsCorruptArtifactAndLeavesCacheUntouched() throws Exception{
		var root = Files.createTempDirectory("helper-ops-corrupt-");
		try{
			var cache  = Files.createDirectory(root.resolve("cache"));
			var source = Files.writeString(root.resolve("input.jar"), "actual");
			// The name claims one content, the tar carries another
			var name   = sha256("expected") + "/dependency.jar";
			var archive = new ByteArrayOutputStream();
			TarIO.write(archive, List.of(new RuntimeBundle.Artifact(name, source)));
			
			var session = UUID.randomUUID();
			
			assertThrows(IOException.class,
			             () -> HelperOps.extractArtifacts(cache, session, new ByteArrayInputStream(archive.toByteArray())));
			assertFalse(Files.exists(cache.resolve(name)));
			assertFalse(Files.exists(cache.resolve(".upload-" + session)));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void uploadRejectsPathEscaping() throws Exception{
		var root = Files.createTempDirectory("helper-ops-tar-escape-");
		try{
			var cache   = Files.createDirectory(root.resolve("cache"));
			var source  = Files.writeString(root.resolve("input.jar"), "x");
			var archive = new ByteArrayOutputStream();
			// A valid checksum with an escaping name must still be rejected by the name check
			TarIO.write(archive, List.of(new RuntimeBundle.Artifact("a/../../../../escape.jar", source)));
			assertThrows(IOException.class,
			             () -> HelperOps.extractArtifacts(cache, UUID.randomUUID(), new ByteArrayInputStream(archive.toByteArray())));
			assertFalse(Files.exists(root.resolve("escape.jar")));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void runSetsUpWorkspaceAndPassesPortToHelper() throws Exception{
		var root = Files.createTempDirectory("helper-ops-run-");
		try{
			var cache   = Files.createDirectory(root.resolve("cache"));
			var parent  = Files.createDirectory(root.resolve("runs"));
			var session = UUID.randomUUID();
			var working = parent.resolve(session.toString());
			Files.createDirectory(cache.resolve(".preparing"));
			Files.writeString(cache.resolve(".preparing/" + session), "");
			var portFile = root.resolve("port.txt");
			var manifest = List.of("a".repeat(64) + "/one.jar", "b".repeat(64) + "/two.jar");
			var request = new HelperProtocol.Run(cache.toString(), parent.toString(), working.toString(),
			                                     RecordingHelper.class.getName(),
			                                     List.of("127.0.0.1", HelperOps.PORT_SENTINEL, portFile.toString()),
			                                     43210, manifest);
			
			HelperOps.run(request);
			
			assertEquals(Files.readString(portFile), "43210");
			assertEquals(Files.readString(working.resolve(".fuzzer-managed")), RemoteWorkspace.MARKER);
			assertEquals(Files.readAllLines(working.resolve(".artifacts")), manifest);
			assertEquals(Files.readString(cache.resolve(".sessions/" + session)), working.toAbsolutePath().normalize().toString());
			// The reservation is deliberately left for RemoteWorkspace.open to delete once it holds the lease.
			assertTrue(Files.exists(cache.resolve(".preparing/" + session)));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void runRejectsBadPortOrMissingSentinel() throws Exception{
		var root = Files.createTempDirectory("helper-ops-run-reject-");
		try{
			var cache   = Files.createDirectory(root.resolve("cache"));
			var parent  = Files.createDirectory(root.resolve("runs"));
			var working = parent.resolve(UUID.randomUUID().toString());
			assertThrows(IOException.class, () -> HelperOps.run(new HelperProtocol.Run(
				cache.toString(), parent.toString(), working.toString(), RecordingHelper.class.getName(),
				List.of("127.0.0.1", HelperOps.PORT_SENTINEL), 0, List.of())));
			assertThrows(IllegalArgumentException.class, () -> HelperOps.run(new HelperProtocol.Run(
				cache.toString(), parent.toString(), working.toString(), RecordingHelper.class.getName(),
				List.of("127.0.0.1", "0"), 43210, List.of())));
			assertFalse(Files.exists(working));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void runPropagatesHelperFailure() throws Exception{
		var root = Files.createTempDirectory("helper-ops-run-fail-");
		try{
			var cache   = Files.createDirectory(root.resolve("cache"));
			var parent  = Files.createDirectory(root.resolve("runs"));
			var working = parent.resolve(UUID.randomUUID().toString());
			
			try{
				HelperOps.run(new HelperProtocol.Run(
					cache.toString(), parent.toString(), working.toString(), FailingHelper.class.getName(),
					List.of("127.0.0.1", HelperOps.PORT_SENTINEL), 43210, List.of()));
				fail("Expected the helper failure to propagate");
			}catch(IOException expected){
				assertEquals(expected.getMessage(), "helper failed");
			}
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test
	public void installProbesAndUploadsMissingArtifactsInOneRoundTrip() throws Exception{
		var root = Files.createTempDirectory("helper-ops-install-");
		try{
			var cache   = Files.createDirectory(root.resolve("cache"));
			var parent  = Files.createDirectory(root.resolve("runs"));
			var session = UUID.randomUUID();
			
			var hash    = "a".repeat(64);
			var hashDir = Files.createDirectory(cache.resolve(hash));
			Files.writeString(hashDir.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			var cached = Files.writeString(hashDir.resolve("cached.jar"), "keep");
			Files.setLastModifiedTime(cached, FileTime.fromMillis(0));
			
			var cachedName  = hash + "/cached.jar";
			var missingName = sha256("new") + "/missing.jar";
			var names       = List.of(cachedName, missingName);
			
			var missingSource = Files.writeString(root.resolve("missing.jar"), "new");
			var tar           = new ByteArrayOutputStream();
			TarIO.write(tar, List.of(new RuntimeBundle.Artifact(missingName, missingSource)));
			
			var response = new ByteArrayOutputStream();
			HelperOps.install(
				new HelperProtocol.Install(cache.toString(), parent.toString(), session, names),
				new ByteArrayInputStream(tar.toByteArray()),
				new PrintStream(response, true, StandardCharsets.UTF_8)
			);
			
			var probe = GSON.fromJson(response.toString(StandardCharsets.UTF_8).strip(), HelperProtocol.CacheProbe.class);
			assertEquals(probe.cache(), cache.toRealPath().toString());
			assertEquals(probe.parent(), parent.toRealPath().toString());
			assertEquals(probe.missing(), List.of(1));
			assertEquals(Files.readString(cache.resolve(cachedName)), "keep");
			assertEquals(Files.getLastModifiedTime(cached).toMillis(), 0);
			assertEquals(Files.readString(cache.resolve(missingName)), "new");
			assertTrue(Files.exists(cache.resolve(".preparing/" + session)));
			assertFalse(Files.exists(cache.resolve(".upload-" + session)));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	private static String sha256(String content) throws Exception{
		var digest = MessageDigest.getInstance("SHA-256");
		return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
	}
	
	public static final class RecordingHelper{
		public static void main(String[] args) throws IOException{
			Files.writeString(Path.of(args[2]), args[1]);
		}
	}
	
	public static final class FailingHelper{
		public static void main(String[] args) throws IOException{
			throw new IOException("helper failed");
		}
	}
}
