package com.lapissea.dfs.io.compress;

import java.util.Map;

//Stop it, get some help
public final class BruteBestPacker implements Packer{
	private static final Map<Byte, Packer> PACKERS = Map.of(
		(byte)0, new Lz4Packer.High(),
		(byte)1, new RlePacker(),
		(byte)2, new GzipPacker()
	);
	@Override
	public byte[] pack(byte[] data){
		if(data.length == 0) return data;
		var es = PACKERS.entrySet();
		var result = (data.length<200? es.stream() : es.parallelStream())
			             .map(e -> Map.entry(e.getValue().pack(data), e.getKey()))
			             .reduce((a, b) -> a.getKey().length<=b.getKey().length? a : b)
			             .orElseThrow();
		byte[] arr = new byte[result.getKey().length + 1];
		arr[0] = result.getValue();
		System.arraycopy(result.getKey(), 0, arr, 1, result.getKey().length);
		return arr;
	}
	@Override
	public byte[] unpack(byte[] packedData){
		if(packedData.length == 0) return packedData;
		var    p   = PACKERS.get(packedData[0]);
		byte[] arr = new byte[packedData.length - 1];
		System.arraycopy(packedData, 1, arr, 0, arr.length);
		return p.unpack(arr);
	}
}
