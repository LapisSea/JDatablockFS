package com.lapissea.dfs.io.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class GzipPacker implements Packer{
	@Override
	public byte[] pack(byte[] data){
		try{
			var res = new ByteArrayOutputStream();
			try(var gzip = new GZIPOutputStream(res)){
				gzip.write(data);
			}
			return res.toByteArray();
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	@Override
	public byte[] unpack(byte[] packedData) throws IOException{
		try(var gis = new GZIPInputStream(new ByteArrayInputStream(packedData))){
			return gis.readAllBytes();
		}catch(IOException e){
			throw new IOException("Failed to unpack packed data", e);
		}
	}
}
