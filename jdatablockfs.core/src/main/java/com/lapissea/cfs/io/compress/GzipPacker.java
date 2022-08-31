package com.lapissea.cfs.io.compress;

import com.lapissea.util.UtilL;

public final class GzipPacker implements Packer{
	@Override
	public byte[] pack(byte[] data){
		return UtilL.compress(data);
	}
	@Override
	public byte[] unpack(byte[] packedData){
		return UtilL.decompress(packedData);
	}
}
