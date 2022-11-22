package com.lapissea.cfs.query;

import java.io.IOException;
import java.util.Optional;

public interface Query<T>{
	
	Class<T> elementType();
	
	long count() throws IOException;
	
	default Optional<T> first() throws IOException{
		return any();//TODO: ordered execution
	}
	Optional<T> any() throws IOException;
	
	default Query<T> filter(String expression, Object... args){
		var result=QueryExpressionParser.filter(elementType(), expression);
		return filter(result.check(), args);
//		return filter(result.readFields(), o->ReflectionExecutor.executeCheck(new QueryContext(args, o), check));
	}
	
	Query<T> filter(QueryCheck check, Object... args);
}
