package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.TypeCheck;

import java.io.IOException;

public abstract class UnmanagedIOMap<K, V> extends IOInstance.Unmanaged<UnmanagedIOMap<K, V>> implements IOMap<K, V>{
	
	protected UnmanagedIOMap(DataProvider provider, Chunk identity, IOType typeDef, TypeCheck check){ super(provider, identity, typeDef, check); }
	public UnmanagedIOMap(DataProvider provider, Chunk identity, IOType typeDef)                    { super(provider, identity, typeDef); }
	
	@Override
	public boolean equals(Object o){
		if(o == this)
			return true;
		
		if(!(o instanceof IOMap m)) return false;
		
		try{
			return equals(m);
		}catch(IOException e){
			throw new RuntimeException();
		}
	}
	
	public boolean equals(IOMap<K, V> m) throws IOException{
		var siz  = size();
		var mSiz = m.size();
		if(siz != mSiz){
			return false;
		}
		
		try{
			for(IOEntry<K, V> e : this){
				K key   = e.getKey();
				V value = e.getValue();
				if(value == null){
					if(!(m.get(key) == null && m.containsKey(key)))
						return false;
				}else{
					if(!value.equals(m.get(key)))
						return false;
				}
			}
		}catch(ClassCastException|NullPointerException unused){
			return false;
		}
		
		return true;
	}
	
	@Override
	public String toString(){
		return IOMap.toString(this);
	}
	@Override
	public String toShortString(){
		return IOMap.toString(this);
	}
}
