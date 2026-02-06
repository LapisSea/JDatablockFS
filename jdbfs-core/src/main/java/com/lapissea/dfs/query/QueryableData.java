package com.lapissea.dfs.query;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.type.IOInstance;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
		
		default List<T> nextFullEntries(int count) throws IOException{
			var list = new ArrayList<T>(count);
			nextFullEntries(count, list::add);
			return list;
		}
		default int nextFullEntries(int count, Consumer<T> dest) throws IOException{
			int visitCount = 0;
			for(; visitCount<count; visitCount++){
				if(!step()) break;
				dest.accept(fullEntry());
			}
			return visitCount;
		}
		
		boolean step() throws IOException;
		T fullEntry() throws IOException;
		T fieldEntry() throws IOException;
	}
	
	QuerySource<T> openQuery(QueryFields queryFields) throws IOException;
}
