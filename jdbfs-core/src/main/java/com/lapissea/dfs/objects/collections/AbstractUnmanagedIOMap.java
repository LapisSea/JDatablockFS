package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;

import java.io.IOException;

public abstract class AbstractUnmanagedIOMap<K, V> extends IOInstance.Unmanaged<AbstractUnmanagedIOMap<K, V>> implements IOMap<K, V>{
	
	@IOValue
	@IOValue.Unsigned
	@IODependency.VirtualNumSize
	protected long size;
	
	private IOFieldPrimitive.FLong<AbstractUnmanagedIOMap<K, V>> sizeField;
	
	protected AbstractUnmanagedIOMap(DataProvider provider, Reference reference, IOType typeDef, TypeCheck check){ super(provider, reference, typeDef, check); }
	public AbstractUnmanagedIOMap(DataProvider provider, Reference reference, IOType typeDef)                    { super(provider, reference, typeDef); }
	
	@Override
	public long size(){ return size; }
	
	protected void deltaSize(long delta) throws IOException{
		if(sizeField == null) sizeField = getThisStruct().getFields().requireExactLong("size");
		this.size += delta;
		writeManagedField(sizeField);
	}
	
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
