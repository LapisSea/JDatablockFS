package com.lapissea.fuzz.distributed.bootstrap;

import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class RemoteWorkspaceTest{
	
	private static void check(boolean value){
		if(!value){
			throw new AssertionError();
		}
	}
	
	private static Path session(Path parent, String name, long time, String artifact) throws Exception{
		Path p = Files.createDirectories(parent.resolve(name));
		
		Files.writeString(p.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
		Files.setLastModifiedTime(p.resolve(".fuzzer-managed"), FileTime.fromMillis(time));
		Files.writeString(p.resolve(".artifacts"), artifact + "\n");
		
		return p;
	}
	
	@Test
	public void retainsThreeInactiveAndActiveReferences() throws Exception{
		Path root   = Files.createTempDirectory("workspace-test");
		Path cache  = Files.createDirectories(root.resolve("cache"));
		Path parent = Files.createDirectories(root.resolve("runs"));
		try{
			long   old         = System.currentTimeMillis() - 7_200_000;
			String hash        = "a".repeat(64);
			Path   artifactDir = Files.createDirectories(cache.resolve(hash));
			Files.writeString(artifactDir.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			Path keep = Files.writeString(artifactDir.resolve("keep.jar"), "keep");
			Path stale = Files.writeString(artifactDir.resolve("old.jar"),
			                               "old");
			Path part = Files.writeString(
				artifactDir.resolve("x.part"), "partial");
			for(Path p : new Path[]{keep, stale, part}){
				Files.setLastModifiedTime(p, FileTime.fromMillis(old));
			}
			
			Path active = session(parent, "active", 1, hash + "/keep.jar");
			Path oldest = session(parent, "oldest", 2, hash + "/old.jar");
			
			for(int i = 0; i<3; i++){
				session(parent, "retained" + i, 10 + i, hash + "/keep.jar");
			}
			
			Path arbitrary = Files.createDirectories(parent.resolve("my-user-data"));
			try(var ch = FileChannel.open(active.resolve(".lease"), CREATE, WRITE);
			    var lock = ch.lock()){
				RemoteWorkspace.cleanup(cache, parent);
				check(Files.exists(active));
				check(!Files.exists(oldest));
				check(Files.exists(arbitrary));
				check(Files.exists(keep));
				check(!Files.exists(stale));
				check(!Files.exists(part));
				RemoteWorkspace.cleanup(cache, parent);
				check(Files.exists(active));
			}
			
			RemoteWorkspace.cleanup(cache, parent);
			check(!Files.exists(active));
		}finally{
			try(var paths = Files.walk(root)){
				for(Path p : paths.sorted(Comparator.reverseOrder()).toList()){ Files.delete(p); }
			}
		}
	}
	
	@Test
	public void reservationProtectsPreparationAndLeaseCloseRecovers() throws Exception{
		Path root   = Files.createTempDirectory("workspace-reservation");
		Path cache  = Files.createDirectories(root.resolve("cache"));
		Path parent = Files.createDirectories(root.resolve("runs"));
		try{
			Path preparing = session(parent, "preparing", 1, "");
			for(int i = 0; i<4; i++){
				session(parent, "done" + i, 10 + i, "");
			}
			
			Files.createDirectories(cache.resolve(".preparing"));
			Files.writeString(cache.resolve(".preparing/preparing"), "");
			
			RemoteWorkspace.cleanup(cache, parent);
			check(Files.exists(preparing));
			
			try(var workspace = RemoteWorkspace.open(cache, parent, preparing)){
				check(!Files.exists(cache.resolve(".preparing/preparing")));
				RemoteWorkspace.cleanup(cache, parent);
				check(Files.exists(preparing));
			}
			check(Files.exists(preparing.resolve(".ended")));
		}finally{
			try(var paths = Files.walk(root)){
				for(Path p : paths.sorted(Comparator.reverseOrder()).toList()){ Files.delete(p); }
			}
		}
	}
	
	@Test
	public void sharedCachePreservesOtherWorkDirectoryAndRemovesStaging() throws Exception{
		Path root   = Files.createTempDirectory("workspace-shared");
		Path cache  = Files.createDirectories(root.resolve("cache"));
		Path parent = Files.createDirectories(root.resolve("runs"));
		Path other  = Files.createDirectories(root.resolve("other"));
		
		try{
			long   old         = System.currentTimeMillis() - 7_200_000;
			String hash        = "b".repeat(64);
			Path   artifactDir = Files.createDirectories(cache.resolve(hash));
			Files.writeString(artifactDir.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			Path artifact = Files.writeString(artifactDir.resolve("keep.jar"), "keep");
			Files.setLastModifiedTime(artifact, FileTime.fromMillis(old));
			Path active  = session(other, "active", 1, hash + "/keep.jar");
			Path staging = session(cache, ".upload-abandoned", old, "");
			Files.writeString(staging.resolve("partial"), "partial");
			
			try(var workspace = RemoteWorkspace.open(cache, other, active)){
				RemoteWorkspace.cleanup(cache, parent);
				check(Files.exists(active));
				check(Files.exists(artifact));
				check(!Files.exists(staging));
				workspace.close();
				workspace.close();
			}
		}finally{
			try(var paths = Files.walk(root)){
				for(Path p : paths.sorted(Comparator.reverseOrder()).toList()){ Files.delete(p); }
			}
		}
	}
	
	public static final class LeaseHolder{
		public static void main(String[] args) throws Exception{
			try(var workspace = RemoteWorkspace.open(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]))){
				System.out.println("ready");
				System.out.flush();
				Thread.sleep(Long.MAX_VALUE);
			}
		}
	}
	
	@Test
	public void killedHelperLeaseIsRecovered() throws Exception{
		Path    root   = Files.createTempDirectory("workspace-killed");
		Path    cache  = Files.createDirectories(root.resolve("cache"));
		Path    parent = Files.createDirectories(root.resolve("runs"));
		Process child  = null;
		try{
			Path active = session(parent, "killed", 1, "");
			for(int i = 0; i<3; i++){
				session(parent, "done" + i, 10 + i, "");
			}
			String cp = Path.of(RemoteWorkspaceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + File.pathSeparator +
			            Path.of(RemoteWorkspace.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			child = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", cp, LeaseHolder.class.getName(),
				cache.toString(), parent.toString(), active.toString()
			).redirectError(ProcessBuilder.Redirect.INHERIT)
			 .start();
			
			try(var output = new BufferedReader(new InputStreamReader(child.getInputStream()))){
				check("ready".equals(output.readLine()));
			}
			
			RemoteWorkspace.cleanup(cache, parent);
			check(Files.exists(active));
			child.destroyForcibly();
			check(child.waitFor(10, TimeUnit.SECONDS));
			RemoteWorkspace.cleanup(cache, parent);
			check(!Files.exists(active));
			check(!Files.exists(cache.resolve(".sessions/killed")));
		}finally{
			if(child != null && child.isAlive()){
				child.destroyForcibly();
			}
			
			try(var paths = Files.walk(root)){
				for(Path p : paths.sorted(Comparator.reverseOrder()).toList()){ Files.delete(p); }
			}
		}
	}
	
}
