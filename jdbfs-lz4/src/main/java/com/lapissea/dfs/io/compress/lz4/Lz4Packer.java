package com.lapissea.dfs.io.compress.lz4;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.compress.Packer;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.objects.NumberSize;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.IOException;
import java.util.Arrays;


public abstract sealed class Lz4Packer implements Packer{
	
	static int sizeBytes(int siz){
		return 1 + NumberSize.bySize(siz).bytes;
	}
	
	static void writeSiz(byte[] dest, int siz){
		var num = NumberSize.bySize(siz);
		try(var io = new ContentOutputStream.BA(dest)){
			FlagWriter.writeSingle(io, NumberSize.FLAG_INFO, num);
			num.write(io, siz);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	static int readSiz(byte[] dest){
		try(var io = new ContentInputStream.BA(dest)){
			return io.readUnsignedInt4Dynamic();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	private static LZ4Factory getFactory(){
		return switch(ConfigDefs.LZ4_COMPATIBILITY.resolveLocking()){
			case ANY -> LZ4Factory.fastestInstance();
			case JAVA_ONLY -> LZ4Factory.fastestJavaInstance();
			case SAFE_ONLY -> LZ4Factory.safeInstance();
		};
	}
	
	
	public static final class High extends Lz4Packer{
		
		@Override
		LZ4Compressor getCompressor(){
			class Compressor{
				private static final LZ4Compressor INST = getFactory().highCompressor();
			}
			return Compressor.INST;
		}
		@Override
		public String name(){
			return "LZ4-High";
		}
	}
	
	public static final class Fast extends Lz4Packer{
		
		@Override
		LZ4Compressor getCompressor(){
			class Compressor{
				private static final LZ4Compressor INST = getFactory().fastCompressor();
			}
			return Compressor.INST;
		}
		@Override
		public String name(){
			return "LZ4-Fast";
		}
	}
	
	abstract LZ4Compressor getCompressor();
	
	@Override
	public byte[] pack(byte[] data){
		var sizeBytes  = sizeBytes(data.length);
		var compressor = getCompressor();
		var dest       = new byte[compressor.maxCompressedLength(data.length) + sizeBytes];
		writeSiz(dest, data.length);
		var compressedSize = compressor.compress(data, 0, data.length, dest, sizeBytes);
		return Arrays.copyOf(dest, compressedSize + sizeBytes);
	}
	
	@Override
	public byte[] unpack(byte[] packedData){
		class Decompressor{
			private static final LZ4FastDecompressor INST = getFactory().fastDecompressor();
		}
		int orgLen = readSiz(packedData);
		return Decompressor.INST.decompress(packedData, sizeBytes(orgLen), orgLen);
	}
}
