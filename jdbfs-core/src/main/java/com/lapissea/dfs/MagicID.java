package com.lapissea.dfs;

import com.lapissea.dfs.exceptions.InvalidMagicID;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.US_ASCII;

public final class MagicID{
	
	private static final byte[]     BYTES    = "BYT-BAE".getBytes(US_ASCII);
	private static final ByteBuffer MAGIC_ID = ByteBuffer.wrap(BYTES);
	
	public static int size(){
		return BYTES.length;
	}
	public static ByteBuffer get(){
		return MAGIC_ID.asReadOnlyBuffer();
	}
	
	public static void read(ContentReader src) throws InvalidMagicID{
		try{
			byte[] readIdBytes = src.readInts1(size());
			if(!Arrays.equals(readIdBytes, BYTES)){
				throw new InvalidMagicID("ID: " + new String(readIdBytes, US_ASCII));
			}
		}catch(IOException e){
			throw new InvalidMagicID("There is no valid magic id, was Cluster.init called?", e);
		}
	}
	
	public static void write(ContentWriter dest) throws IOException{
		dest.write(get());
	}
}
