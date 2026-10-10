package com.lapissea.fuzz.distributed.bootstrap;

import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * OS lock regression: closing another descriptor must not unlock an already owned snapshot.
 */
public final class SnapshotLeaseTest{
	
	private static void check(boolean condition){
		if(!condition){
			throw new AssertionError();
		}
	}
	
	public static final class Child{
		public static void main(String[] args) throws Exception{
			if(args[0].equals("owner")){
				try(var first = SnapshotLease.open(Path.of(args[1])); var second = SnapshotLease.open(Path.of(args[1]))){
					System.out.println(first.directory);
					System.out.println(second.directory);
					System.out.flush();
					Thread.sleep(Long.MAX_VALUE);
				}
			}else{
				try(var capture = SnapshotLease.open(Path.of(args[1]))){
					System.out.println("swept");
				}
			}
		}
	}
	
	private static Process child(String mode, Path root) throws Exception{
		String cp = Path.of(SnapshotLeaseTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + File.pathSeparator +
		            Path.of(SnapshotLease.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", cp, Child.class.getName(), mode,
		                          root.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
	}
	
	@Test
	public void secondCapturePreservesCrossProcessLeaseUntilOwnerKilled() throws Exception{
		Path    root  = Files.createTempDirectory("snapshot-os-lock");
		Process owner = null, sweeper = null;
		try{
			owner = child("owner", root);
			Path first, second;
			try(var output = new BufferedReader(new InputStreamReader(owner.getInputStream()))){
				first = Path.of(output.readLine());
				second = Path.of(output.readLine());
			}
			
			sweeper = child("sweep", root);
			check(sweeper.waitFor(10, TimeUnit.SECONDS));
			check(sweeper.exitValue() == 0);
			check(Files.exists(first));
			check(Files.exists(second));
			
			owner.destroyForcibly();
			check(owner.waitFor(10, TimeUnit.SECONDS));
			
			sweeper = child("sweep", root);
			check(sweeper.waitFor(10, TimeUnit.SECONDS));
			check(sweeper.exitValue() == 0);
			check(!Files.exists(first));
			check(!Files.exists(second));
		}finally{
			if(owner != null && owner.isAlive()){
				owner.destroyForcibly();
				owner.waitFor(10, TimeUnit.SECONDS);
			}
			if(sweeper != null && sweeper.isAlive()){
				sweeper.destroyForcibly();
				sweeper.waitFor(10, TimeUnit.SECONDS);
			}
			try(var entries = Files.walk(root)){
				for(Path p : entries.sorted(Comparator.reverseOrder()).toList()){
					Files.delete(p);
				}
			}
		}
	}
}
