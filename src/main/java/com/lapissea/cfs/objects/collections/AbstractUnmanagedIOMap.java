package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;

public abstract class AbstractUnmanagedIOMap<K, V, SELF extends AbstractUnmanagedIOMap<K, V, SELF>> extends IOInstance.Unmanaged<SELF> implements IOMap<K, V>{
	
	@IOValue
	private long size;
	
	private final IOField<SELF, ?> sizeField=getThisStruct().getFields().byName("size").orElseThrow();
	
	protected AbstractUnmanagedIOMap(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef, TypeDefinition.Check check){super(provider, reference, typeDef, check);}
	public AbstractUnmanagedIOMap(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef)                               {super(provider, reference, typeDef);}
	
	@Override
	public long size(){return size;}
	
	protected void deltaSize(long delta) throws IOException{
		this.size+=delta;
		writeManagedField(sizeField);
	}
	
	@Override
	public boolean equals(Object o){
		if(o==this)
			return true;
		
		if(!(o instanceof IOMap m)) return false;
		
		try{
			return equals(m);
		}catch(IOException e){
			throw new RuntimeException();
		}
	}
	
	public boolean equals(IOMap<K, V> m) throws IOException{
		var siz =size();
		var mSiz=m.size();
		if(siz!=mSiz){
			return false;
		}
		
		try{
			for(Entry<K, V> e : entries()){
				K key  =e.getKey();
				V value=e.getValue();
				if(value==null){
					if(!(m.get(key)==null&&m.containsKey(key)))
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
}
