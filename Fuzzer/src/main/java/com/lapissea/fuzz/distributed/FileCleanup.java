package com.lapissea.fuzz.distributed;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class FileCleanup{
	
	public static void deleteTree(Path directory) throws IOException{
		if(!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)){
			return;
		}
		Files.walkFileTree(directory, new SimpleFileVisitor<>(){
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException{
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}
			@Override
			public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException{
				// An entry that vanished mid-walk (e.g. a concurrent sweep) is already gone, not an error.
				if(error instanceof NoSuchFileException){ return FileVisitResult.CONTINUE; }
				throw error;
			}
			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException{
				if(error != null){ throw error; }
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
