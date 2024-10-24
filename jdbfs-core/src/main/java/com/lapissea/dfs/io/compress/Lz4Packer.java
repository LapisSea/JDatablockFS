package com.lapissea.dfs.io.compress;

import com.lapissea.dfs.config.ConfigDefs;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.util.Arrays;

import static com.lapissea.dfs.io.compress.Packer.readSiz;
import static com.lapissea.dfs.io.compress.Packer.sizeBytes;
import static com.lapissea.dfs.io.compress.Packer.writeSiz;

public abstract sealed class Lz4Packer implements Packer{
	
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
	}
	
	public static final class Fast extends Lz4Packer{
		
		@Override
		LZ4Compressor getCompressor(){
			class Compressor{
				private static final LZ4Compressor INST = getFactory().fastCompressor();
			}
			return Compressor.INST;
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
