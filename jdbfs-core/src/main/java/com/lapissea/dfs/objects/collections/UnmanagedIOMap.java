package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.field.FieldSupport;
import com.lapissea.dfs.type.string.Doc;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.dfs.type.string.StringifyState;

import java.io.IOException;
import java.util.ArrayList;

public abstract class UnmanagedIOMap<K, V> extends IOInstance.Unmanaged<UnmanagedIOMap<K, V>> implements IOMap<K, V>{
	
	protected UnmanagedIOMap(DataProvider provider, Chunk identity, IOType typeDef, TypeCheck check){ super(provider, identity, typeDef, check); }
	public UnmanagedIOMap(DataProvider provider, Chunk identity, IOType typeDef)                    { super(provider, identity, typeDef); }
	
	@Override
	public boolean equals(Object o){
		if(o == this)
			return true;
		
		if(!(o instanceof IOMap<?, ?> m)) return false;
		
		try{
			//noinspection unchecked
			return equals((IOMap<K, V>)m);
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
	public Doc instanceToDoc(StringifySettings settings){
		if(!StringifyState.enter()){
			return Doc.text("{...}");
		}
		try{
			var  entries = new ArrayList<Doc>();
			var  iter    = iterator();
			long total   = size();
			int  shown   = 0;
			while(iter.hasNext() && shown<settings.maxCollectionPrint()){
				var e = iter.ioNext();
				entries.add(Doc.concat(
					FieldSupport.elementDoc(e.getKey(), settings),
					Doc.text(": "),
					FieldSupport.elementDoc(e.getValue(), settings)
				));
				shown++;
			}
			if(total>shown){
				entries.add(Doc.text("... " + (total - shown) + " more"));
			}
			return FieldSupport.mapDoc(entries);
		}catch(Throwable e){
			return Doc.text("CORRUPTED_MAP{" + (e.getMessage() == null? e.getClass().getSimpleName() : e.getMessage()) + "}");
		}finally{
			StringifyState.exit();
		}
	}
}
