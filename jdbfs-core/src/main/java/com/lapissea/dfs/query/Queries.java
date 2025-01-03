package com.lapissea.dfs.query;


import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.IOField;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class Queries{
	
	public static class All<T> implements Query<T, T>{
		@Override
		public Optional<T> findFirst(QueryableData<T> data) throws IOException{
			try(var source = data.openQuery()){
				while(source.step()){
					var e = source.fullEntry();
					if(e == null) continue;
					return Optional.of(e);
				}
			}
			return Optional.empty();
		}
	}
	
	public static class ByField<T extends IOInstance<T>> implements Query<T, T>{
		
		private final IOField<T, ?>     field;
		private final Predicate<Object> match;
		
		public ByField(IOField<T, ?> field, Predicate<?> match){
			this.field = Objects.requireNonNull(field);
			//noinspection unchecked
			this.match = (Predicate<Object>)Objects.requireNonNull(match);
		}
		
		@Override
		public Optional<T> findFirst(QueryableData<T> data) throws IOException{
			try(var source = data.openQuery()){
				while(source.step()){
					var fe = source.fieldEntry();
					if(fe == null) continue;
					var val = field.get(null, fe);
					if(!match.test(val)){
						continue;
					}
					var e = source.fullEntry();
					return Optional.of(e);
				}
			}
			return Optional.empty();
		}
	}
	
	
}
