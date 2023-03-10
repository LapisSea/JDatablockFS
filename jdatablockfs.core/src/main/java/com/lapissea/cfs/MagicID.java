package com.lapissea.cfs;

import com.lapissea.cfs.exceptions.InvalidMagicID;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;

import java.io.IOException;
import java.nio.ByteBuffer;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MagicID{
	
	private static final ByteBuffer MAGIC_ID = ByteBuffer.wrap("BYT-BAE".getBytes(UTF_8)).asReadOnlyBuffer();
	
	public static int size(){
		return MAGIC_ID.limit();
	}
	public static ByteBuffer get(){
		return MAGIC_ID.asReadOnlyBuffer();
	}
	
	public static void read(ContentReader src) throws InvalidMagicID{
		ByteBuffer magicId;
		try{
			magicId = ByteBuffer.wrap(src.readInts1(MAGIC_ID.limit()));
			if(!magicId.equals(MAGIC_ID)){
				throw new InvalidMagicID("ID: " + UTF_8.decode(magicId));
			}
		}catch(IOException e){
			throw new InvalidMagicID("There is no valid magic id, was Cluster.init called?", e);
		}
	}
	
	public static void write(ContentWriter dest) throws IOException{
		dest.write(get());
	}
}
