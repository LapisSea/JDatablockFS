package com.lapissea.cfs.query;

import com.lapissea.cfs.OptionalPP;
import com.lapissea.cfs.objects.collections.IOIterator;
import com.lapissea.util.ZeroArrays;

import java.io.IOException;

public interface Query<T>{
	
	Class<T> elementType();
	
	long count() throws IOException;
	
	default OptionalPP<T> first() throws IOException{
		return any();//TODO: ordered execution
	}
	OptionalPP<T> any() throws IOException;
	IOIterator<T> all();
	
	long deleteAll() throws IOException;
	
	
	default <R> Query<R> map(String expression, Object... args){
		var result = QueryExpressionParser.mapping(elementType(), expression);
		return map(result, args);
	}
	<R> Query<R> map(QueryValueSource field, Object... args);
	
	default Query<T> filter(String expression){
		return filter(expression, ZeroArrays.ZERO_OBJECT);
	}
	default Query<T> filter(String expression, Object... args){
		var result = QueryExpressionParser.filter(elementType(), expression);
		return filter(result.check(), args);
	}
	
	default Query<T> filter(QueryCheck check){
		return filter(check, ZeroArrays.ZERO_OBJECT);
	}
	Query<T> filter(QueryCheck check, Object... args);
}
