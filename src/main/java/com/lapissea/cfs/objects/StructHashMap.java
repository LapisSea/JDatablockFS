package com.lapissea.cfs.objects;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.util.NotImplementedException;

public class StructHashMap<K extends IOInstance, V extends IOInstance> extends IOInstance implements IOMap<K, V>{
	
	@Override
	public V getValue(K key){
		throw NotImplementedException.infer();//TODO: implement StructHashMap.getValue()
	}
	@Override
	public void putValue(K struct, V value){
		throw NotImplementedException.infer();//TODO: implement StructHashMap.putValue()
	}
	@Override
	public void clear(){
		throw NotImplementedException.infer();//TODO: implement StructHashMap.clear()
	}
}
