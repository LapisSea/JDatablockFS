package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;

public class HashIOMap<K, V> extends IOInstance.Unmanaged<HashIOMap<K, V>> implements IOMap<K, V>{
	
	public HashIOMap(ChunkDataProvider provider, Reference reference){
		super(provider, reference);
	}
	
	
	@Override
	public Entry<K, V> getEntry(K key) throws IOException{
		throw NotImplementedException.infer();//TODO: implement HashIOMap.getEntry()
	}
}
