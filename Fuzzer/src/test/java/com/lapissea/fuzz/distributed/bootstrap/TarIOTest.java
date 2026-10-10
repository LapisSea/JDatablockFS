package com.lapissea.fuzz.distributed.bootstrap;

import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

import static org.testng.Assert.assertEquals;

/**
 * Regression for extraction from a stream whose {@code skip} is unusable, as on Windows where
 * {@code FileInputStream.skip} on the stdin pipe throws instead of falling back to reading.
 */
public class TarIOTest{
	
	@Test
	public void extractionDoesNotRelyOnStreamSkip() throws Exception{
		var root  = Files.createTempDirectory("tar-reader-");
		var entry = root.resolve("payload.bin");
		try{
			// A size that is not a multiple of 512 forces a non-empty padding block.
			var content = new byte[600];
			for(int i = 0; i<content.length; i++) content[i] = (byte)i;
			Files.write(entry, content);
			
			var artifact = new RuntimeBundle.Artifact("ab".repeat(32) + "/payload.bin", entry);
			var buffer   = new ByteArrayOutputStream();
			TarIO.write(buffer, List.of(artifact));
			var tar = buffer.toByteArray();
			
			InputStream noSkip = new FilterInputStream(new ByteArrayInputStream(tar)){
				@Override
				public long skip(long n) throws IOException{
					throw new IOException("The parameter is incorrect");
				}
			};
			
			var dest  = Files.createDirectory(root.resolve("out"));
			var names = TarIO.extract(noSkip, dest);
			
			assertEquals(names, List.of(artifact.name()));
			assertEquals(Files.readAllBytes(dest.resolve(artifact.name())), content);
		}finally{
			try(var paths = Files.walk(root)){
				for(var p : paths.sorted(Comparator.reverseOrder()).toList()){
					try{ Files.deleteIfExists(p); }catch(IOException ignored){ }
				}
			}
		}
	}
}
