package com.lapissea.dfs.query;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.type.IOInstance;

import java.io.Closeable;
import java.io.IOException;

public interface QueryableData<T>{
	
	interface QuerySource<T> extends Closeable{
		
		static <T> QuerySource<T> fromIter(IOIterator<T> iter){
			return new QuerySource<>(){
				
				private T val;
				
				@Override
				public boolean step() throws IOException{
					if(!iter.hasNext()) return false;
					val = iter.ioNext();
					return true;
				}
				@Override
				public T fullEntry(){
					return val;
				}
				@Override
				public T fieldEntry(){
					if(val != null && !(val instanceof IOInstance<?>)){
						throw new UnsupportedOperationException();
					}
					return val;
				}
				@Override
				public void close(){ }
			};
		}
		
		boolean step() throws IOException;
		T fullEntry() throws IOException;
		T fieldEntry() throws IOException;
	}
	
	QuerySource<T> openQuery(QueryFields queryFields) throws IOException;
}
