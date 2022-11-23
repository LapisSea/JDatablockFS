package com.lapissea.cfs.query;

import com.lapissea.cfs.objects.collections.IOIterator;
import com.lapissea.util.ZeroArrays;

import java.io.IOException;
import java.util.Optional;

public interface Query<T>{
	
	Class<T> elementType();
	
	long count() throws IOException;
	
	default Optional<T> first() throws IOException{
		return any();//TODO: ordered execution
	}
	Optional<T> any() throws IOException;
	IOIterator<T> all();
	
	default Query<T> filter(String expression){
		return filter(expression, ZeroArrays.ZERO_OBJECT);
	}
	default Query<T> filter(String expression, Object... args){
		var result=QueryExpressionParser.filter(elementType(), expression);
		return filter(result.check(), args);
	}
	
	default Query<T> filter(QueryCheck check){
		return filter(check, ZeroArrays.ZERO_OBJECT);
	}
	Query<T> filter(QueryCheck check, Object... args);
}
