package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;

import java.io.IOException;

public abstract class AbstractUnmanagedIOMap<K, V> extends IOInstance.Unmanaged<AbstractUnmanagedIOMap<K, V>> implements IOMap<K, V>{
	
	@IOValue
	@IODependency.VirtualNumSize
	private long size;
	
	private final IOFieldPrimitive.FLong<AbstractUnmanagedIOMap<K, V>> sizeField=getThisStruct().getFields().requireExactLong("size");
	
	protected AbstractUnmanagedIOMap(DataProvider provider, Reference reference, TypeLink typeDef, TypeLink.Check check){super(provider, reference, typeDef, check);}
	public AbstractUnmanagedIOMap(DataProvider provider, Reference reference, TypeLink typeDef)                         {super(provider, reference, typeDef);}
	
	@Override
	public long size(){return size;}
	
	protected void deltaSize(long delta) throws IOException{
		this.size+=delta;
		getDataProvider().getSource().openIOTransaction(()->writeManagedField(sizeField));
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
	@Override
	public String toShortString(){
		return IOMap.toString(this);
	}
}
