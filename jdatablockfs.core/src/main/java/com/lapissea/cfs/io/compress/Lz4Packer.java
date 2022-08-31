package com.lapissea.cfs.io.compress;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.util.Arrays;

import static com.lapissea.cfs.io.compress.Packer.readSiz;
import static com.lapissea.cfs.io.compress.Packer.sizeBytes;
import static com.lapissea.cfs.io.compress.Packer.writeSiz;

public final class Lz4Packer implements Packer{
	
	private static final LZ4Compressor       COMPRESSOR  =LZ4Factory.fastestInstance().highCompressor();
	private static final LZ4FastDecompressor DECOMPRESSOR=LZ4Factory.fastestInstance().fastDecompressor();
	
	@Override
	public byte[] pack(byte[] data){
		var sizeBytes=sizeBytes(data.length);
		var dest     =new byte[COMPRESSOR.maxCompressedLength(data.length)+sizeBytes];
		writeSiz(dest, data.length);
		var compressedSize=COMPRESSOR.compress(data, 0, data.length, dest, sizeBytes);
		return Arrays.copyOf(dest, compressedSize+sizeBytes);
	}
	
	@Override
	public byte[] unpack(byte[] packedData){
		int orgLen=readSiz(packedData);
		return DECOMPRESSOR.decompress(packedData, sizeBytes(orgLen), orgLen);
	}
}
