package com.lapissea.dfs.run.checked;

import com.lapissea.dfs.query.Query;
import com.lapissea.dfs.query.QueryFields;
import com.lapissea.dfs.query.QueryableData;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class CheckQuery<T> implements Query<T>{
	
	private final Query<T>    data;
	private final Iterable<T> reference;
	
	public CheckQuery(Query<T> data, Iterable<T> reference){
		this.data = data;
		this.reference = reference;
	}
	
	@Override
	public QueryableData.QuerySource<T> open(QueryFields queryFields) throws IOException{
		var a = data.open(queryFields);
		var b = reference.iterator();
		return new QueryableData.QuerySource<>(){
			private T       toCheck;
			private boolean closed;
			
			@Override
			public void close() throws IOException{
				checkClosed();
				a.close();
				closed = true;
			}
			private void checkClosed(){
				if(closed) throw new IllegalStateException("Query closed");
			}
			@Override
			public boolean step() throws IOException{
				checkClosed();
				var hasNext = a.step();
				var hnRef   = b.hasNext();
				assertThat(hasNext).as("Step has data").isEqualTo(hnRef);
				if(hnRef){
					toCheck = b.next();
				}
				return hasNext;
			}
			@Override
			public T fullEntry() throws IOException{
				checkClosed();
				var val = a.fullEntry();
				assertThat(val).as("Full entry equality").isEqualTo(toCheck);
				return val;
			}
			@Override
			public T fieldEntry() throws IOException{
				checkClosed();
				var val = a.fieldEntry();
				assertThat(val).as("Field entry equality").isEqualTo(toCheck);
				return val;
			}
		};
	}
}
