package com.lapissea.dfs.query;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.type.IOInstance;

import java.io.Closeable;
import java.io.IOException;

public interface QueryableData<T>{
	
	interface QuerySource<T> extends Closeable{
		
		static <T> QuerySource<T> fromIter(IOIterator<T> iter){
			return new QuerySource<>(){
				
				private T       val;
				private boolean closed;
				
				private void checkClosed(){
					if(closed) throw new IllegalStateException("Query closed");
				}
				
				@Override
				public boolean step() throws IOException{
					checkClosed();
					if(!iter.hasNext()) return false;
					val = iter.ioNext();
					return true;
				}
				@Override
				public T fullEntry(){
					checkClosed();
					return val;
				}
				@Override
				public T fieldEntry(){
					checkClosed();
					if(val != null && !(val instanceof IOInstance<?>)){
						throw new UnsupportedOperationException();
					}
					return val;
				}
				@Override
				public void close(){ closed = true; }
			};
		}
		
		boolean step() throws IOException;
		T fullEntry() throws IOException;
		T fieldEntry() throws IOException;
	}
	
	QuerySource<T> openQuery(QueryFields queryFields) throws IOException;
}
