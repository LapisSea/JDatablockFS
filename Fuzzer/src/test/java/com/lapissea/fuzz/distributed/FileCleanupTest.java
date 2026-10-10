package com.lapissea.fuzz.distributed;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.file.FileSystemException;
import java.nio.file.Files;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class FileCleanupTest{
	
	@Test
	public void deletionNeverFollowsLinksOutsideOwnedTree() throws Exception{
		var root = Files.createTempDirectory("fuzz-tree-cleanup-");
		try{
			var outside  = Files.createDirectory(root.resolve("outside"));
			var sentinel = Files.writeString(outside.resolve("user-data"), "keep");
			var owned    = Files.createDirectories(root.resolve("owned/nested"));
			Files.writeString(owned.resolve("temporary"), "delete");
			try{
				Files.createSymbolicLink(owned.resolve("external"), outside);
			}catch(FileSystemException e){
				if(System.getProperty("os.name").startsWith("Windows")){
					throw new SkipException("Symbolic link creation requires Windows privilege or developer mode", e);
				}
				throw e;
			}
			
			Files.createSymbolicLink(owned.resolve("dangling"), root.resolve("missing"));
			FileCleanup.deleteTree(root.resolve("owned"));
			
			assertFalse(Files.exists(root.resolve("owned")));
			assertEquals(Files.readString(sentinel), "keep");
			
			var rootLink = Files.createSymbolicLink(root.resolve("link"), outside);
			FileCleanup.deleteTree(rootLink);
			
			assertEquals(Files.readString(sentinel), "keep");
			FileCleanup.deleteTree(root.resolve("owned"));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
}
