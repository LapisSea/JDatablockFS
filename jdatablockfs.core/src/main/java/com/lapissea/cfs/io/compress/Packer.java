package com.lapissea.cfs.io.compress;

import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;

public sealed interface Packer permits GzipPacker, RlePacker, Lz4Packer, BruteBestPacker{
	byte[] pack(byte[] data);
	byte[] unpack(byte[] packedData);
	
	static int sizeBytes(int siz){
		return 1+NumberSize.bySize(siz).bytes;
	}
	
	static void writeSiz(byte[] dest, int siz){
		var num=NumberSize.bySize(siz);
		try(var io=new ContentOutputStream.BA(dest)){
			FlagWriter.writeSingle(io, NumberSize.FLAG_INFO, num);
			num.write(io, siz);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	static int readSiz(byte[] dest){
		try(var io=new ContentInputStream.BA(dest)){
			var siz=FlagReader.readSingle(io, NumberSize.FLAG_INFO);
			return Math.toIntExact(siz.read(io));
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
}
